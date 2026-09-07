package ai.genwhy.nobonk.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.genwhy.nobonk.util.Dbg
import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import java.nio.FloatBuffer
import java.util.UUID

/**
 * Object detector using ONNX Runtime — supports both YOLO11 and YOLO26 model families.
 *
 * Pre-processing now uses an **aspect-preserving letterbox** (bilinear) instead of the
 * old squish-to-square nearest-neighbour resize (fixes ML-04/ML-10): boxes are decoded
 * in letterboxed model-pixel space and inverse-mapped back to the ORIGINAL frame's
 * normalized coordinates via [Letterbox], so they line up on the preview and thin/distant
 * pedestrians are no longer distorted away.
 *
 * NMS is grouped by **true class id** with `iouThreshold ≈ 0.45` and `confidence ≈ 0.4`
 * (fixes ML-07/08/13/15) — see [Nms].
 */
class ObjectDetector(
    context: Context,
    modelName: String = "yolo26s_416.onnx",
    requestedInputSize: Int = 416,
    val skipNms: Boolean = false
) {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val preparedModel: PreparedModel

    /** A benchmark candidate already owns the same reusable buffers used for real frames. */
    private class PreparedModel(
        val session: OrtSession, val size: Int, val input: FloatBuffer, val runner: OrtFloatRunner
    ) : AutoCloseable {
        fun infer() = runner.run { _, _ -> Unit }
        override fun close() { try { runner.close() } finally { session.close() } }
    }

    /**
     * The execution provider actually verified to run inference (via a warm-up pass):
     * "NNAPI" (device NPU/GPU/DSP), "XNNPACK" (optimized CPU) or "CPU" (plain).
     * This is set only after a real inference succeeded, so it never over-claims.
     */
    var activeExecutionProvider: String = "CPU"
        private set

    private val confidenceThreshold = 0.40f
    private val iouThreshold = 0.45f

    val inputSize: Int

    // Pre-allocated per-frame buffers — sized after inputSize is resolved.
    private val pixels: IntArray
    private val floatBuffer: FloatBuffer
    private val inference: OrtFloatRunner
    // Reused letterbox input bitmap (avoids a per-frame ARGB allocation).
    private var lbBitmap: Bitmap? = null
    private val lbPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    companion object {
        private const val TAG = "ObjectDetector"

        /** COCO id → display name. Only the classes we care about for a walker are named. */
        fun classNameFor(classId: Int): String = CocoRawHeadDecoder.classNameFor(classId)
    }

    init {
        val modelBytes = context.assets.open(modelName).use { it.readBytes() }

        // Cache a verified measured choice, not a hardware assumption. Invalidate after
        // model/app/runtime/OS changes; a failed cached warm-up triggers benchmarking.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val modelHash = digest.digest(modelBytes).joinToString("") { "%02x".format(it) }
        val identity = "$modelHash|${android.os.Build.FINGERPRINT}|${ortEnvironment.version}|${ai.genwhy.nobonk.BuildConfig.VERSION_CODE}|ep-v2"
        val key = digest.digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("nobonk_execution", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = if (ProviderSelection.validCache(prefs.getLong("$key.time", 0), now)) prefs.getString(key, null) else null
        val choice = ProviderSelection.select(
            providers = listOf("XNNPACK", "NNAPI", "CPU"), cached = cached,
            create = { ep ->
                OrtSession.SessionOptions().use { options ->
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    when (ep) {
                        "NNAPI" -> {
                            options.setIntraOpNumThreads(4)
                            // Avoid NNAPI's slow CPU reference implementation. ORT can
                            // still execute unsupported graph nodes on CPU; NNAPI != NPU.
                            options.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
                        }
                        "XNNPACK" -> { options.setIntraOpNumThreads(1); options.addXnnpack(mapOf("intra_op_num_threads" to "4")) }
                        else -> options.setIntraOpNumThreads(4)
                    }
                    val session = ortEnvironment.createSession(modelBytes, options)
                    try {
                        val size = readInputSize(session, modelName, requestedInputSize)
                        val input = java.nio.ByteBuffer.allocateDirect(4 * 3 * size * size)
                            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                        PreparedModel(session, size, input, OrtFloatRunner(ortEnvironment, session, input,
                            longArrayOf(1, 3, size.toLong(), size.toLong())))
                    } catch (failure: Exception) {
                        session.close()
                        throw failure
                    }
                }
            },
            verify = { it.infer() },
            measure = {
                val t0 = System.nanoTime()
                it.infer()
                (System.nanoTime() - t0) / 1e6
            }
        )
        preparedModel = choice.resource
        activeExecutionProvider = choice.name
        inputSize = preparedModel.size
        if (!choice.cached) prefs.edit().putString(key, choice.name).putLong("$key.time", now).apply()
        Dbg.i(TAG, "Execution provider: ${choice.name} (${if (choice.cached) "cached + verified" else "measured"}) for $modelName")

        pixels = IntArray(inputSize * inputSize)
        floatBuffer = preparedModel.input
        inference = preparedModel.runner

        val family = if (skipNms) "YOLO26 end-to-end (NMS-free)" else "YOLO26 raw head + in-app NMS"
        Dbg.i(TAG, "Model ready: $modelName | family: $family | input: ${inputSize}px | EP: $activeExecutionProvider")
    }

    private fun readInputSize(session: OrtSession, modelName: String, requested: Int): Int = try {
        val shape = (session.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape
        val modelDim = if (shape.size >= 4 && shape[2] > 0) shape[2].toInt() else requested
        if (modelDim != requested) {
            Dbg.w(TAG, "Model $modelName requires ${modelDim}px input (requested ${requested}px) — auto-correcting")
        }
        modelDim
    } catch (e: Exception) {
        Dbg.w(TAG, "Could not read model input shape — using ${requested}px")
        requested
    }

    /**
     * Run detection on a full-frame bitmap (any aspect ratio). Boxes are returned in the
     * ORIGINAL frame's normalized coordinates (0‥1), already letterbox-corrected + NMS'd.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        return try {
            val t = Letterbox.compute(bitmap.width, bitmap.height, inputSize)
            val input = letterbox(bitmap, t)
            preprocessImage(input)
            inference.run { rawOutput, shape ->
                require(shape.size == 3 && shape[0] == 1L) { "Expected a batch-one YOLO output" }
                val dim2 = shape[1].toInt()
                val dim3 = shape[2].toInt()
                val isYolo26Format = dim3 == 6 && dim2 > dim3

                val detections = if (isYolo26Format) {
                    parseYolo26(rawOutput, dim2, t)
                } else {
                    val isStandard = dim2 < dim3
                    val numChannels = if (isStandard) dim2 else dim3
                    val numClasses = (numChannels - 4).coerceAtLeast(1)
                    parseAllObjects(rawOutput, isStandard, numClasses, if (isStandard) dim3 else dim2, t)
                }

                if (skipNms || isYolo26Format) detections else Nms.apply(detections, iouThreshold)
            }
        } catch (e: Exception) {
            Dbg.e(TAG, "Detection error: ${e.message}", e)
            throw e // an inference failure is not an empty, successfully scanned scene
        }
    }

    // ── Letterbox pre-processing ────────────────────────────────────────────────

    private fun letterbox(src: Bitmap, t: Letterbox.Transform): Bitmap {
        val out = lbBitmap ?: Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            .also { lbBitmap = it }
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114))   // standard YOLO gray pad
        val m = android.graphics.Matrix().apply {
            postScale(t.scale, t.scale)
            postTranslate(t.padX, t.padY)
        }
        canvas.drawBitmap(src, m, lbPaint)
        return out
    }

    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val pixelCount = inputSize * inputSize
        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.put(pixelCount + i, ((pixel shr 8) and 0xFF) / 255.0f)
            floatBuffer.put(2 * pixelCount + i, (pixel and 0xFF) / 255.0f)
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    // ── Parsers (return boxes in ORIGINAL normalized coords) ────────────────────

    private fun parseAllObjects(
        output: FloatBuffer, isStandard: Boolean, numClasses: Int, numBoxes: Int, t: Letterbox.Transform
    ): List<Detection> = CocoRawHeadDecoder.decode(output, isStandard, numClasses, numBoxes, t, confidenceThreshold) { box, name ->
        estimateDistance(box.height, box.width, name)
    }

    private fun parseYolo26(output: FloatBuffer, numBoxes: Int, t: Letterbox.Transform): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in 0 until numBoxes) {
            val row = i * 6
            val confidence = output.get(row + 4)
            if (confidence < confidenceThreshold) continue
            val classId = output.get(row + 5).toInt()
            val box = Letterbox.boxToOriginalNorm(output.get(row), output.get(row + 1), output.get(row + 2), output.get(row + 3), t)
            detections.add(makeDetection(box, confidence, classId))
        }
        return detections
    }

    private fun makeDetection(box: NormBox, score: Float, classId: Int): Detection {
        val cls = classNameFor(classId)
        return Detection(
            id = UUID.randomUUID().toString(),
            boundingBox = box,
            confidence = score,
            distance = estimateDistance(box.height, box.width, cls),  // informational only
            className = cls,
            classId = classId
        )
    }

    /**
     * Rough monocular distance for the on-screen label / history only. The alarm ladder
     * does NOT use this (it saturates) — see [AlertPolicy].
     */
    /** Normalized focal length (focal / sensor extent along frame height). Set from the
     *  bound camera's characteristics via [CameraIntrinsics]; defaults to a typical phone. */
    @Volatile var focalNorm: Float = CameraIntrinsics.DEFAULT_FOCAL_NORM

    private fun estimateDistance(boxHeight: Float, boxWidth: Float, className: String): Float {
        val focalLength = focalNorm
        val (realHeightM, typicalAspect) = when (className) {
            "person"     -> Pair(1.70f, 0.40f)
            "dog", "cat" -> Pair(0.45f, 1.40f)
            "car", "truck", "bus" -> Pair(1.50f, 1.80f)
            "motorcycle" -> Pair(1.10f, 0.90f)
            "bicycle"    -> Pair(1.00f, 0.75f)
            else         -> Pair(1.20f, 0.80f)
        }
        val aspectRatio = if (boxHeight > 0.001f) boxWidth / boxHeight else typicalAspect
        val partialBodyCorrection = if (className == "person" && aspectRatio > typicalAspect) {
            (typicalAspect / aspectRatio).coerceIn(0.50f, 1.0f)
        } else 1.0f
        val k = realHeightM * focalLength * partialBodyCorrection
        val distance = k / maxOf(boxHeight, 0.005f)
        return distance.coerceIn(0.1f, 15.0f)
    }

    fun close() {
        lbBitmap?.recycle(); lbBitmap = null
        try { preparedModel.close() } finally { ortEnvironment.close() }
    }
}
