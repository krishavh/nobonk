package com.persondetection.android.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.persondetection.android.model.Detection
import com.persondetection.android.model.NormBox
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
    modelName: String = "yolo11s.onnx",
    requestedInputSize: Int = 416,
    val skipNms: Boolean = false
) {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    /**
     * The execution provider actually verified to run inference (via a warm-up pass):
     * "NNAPI" (device NPU/GPU/DSP), "XNNPACK" (optimized CPU) or "CPU" (plain).
     * This is set only after a real inference succeeded, so it never over-claims.
     */
    var activeExecutionProvider: String = "CPU"
        private set

    /**
     * True ONLY when inference is verified to run on a hardware accelerator (NNAPI).
     * XNNPACK is a CPU provider, so it does NOT count as hardware acceleration — this
     * keeps the UI "NPU" chip honest (fixes the false-NPU concern in T-PERF-INFER).
     */
    val isHardwareAccelerated: Boolean get() = activeExecutionProvider == "NNAPI"

    private val confidenceThreshold = 0.40f
    private val iouThreshold = 0.45f

    val inputSize: Int

    // Pre-allocated per-frame buffers — sized after inputSize is resolved.
    private val pixels: IntArray
    private val floatBuffer: FloatBuffer
    // Reused letterbox input bitmap (avoids a per-frame ARGB allocation).
    private var lbBitmap: Bitmap? = null
    private val lbPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    companion object {
        private const val TAG = "ObjectDetector"

        /** COCO class ids we actually map to a display name — scanning only these
         *  (instead of all 80) shortens the per-box post-processing loop ~10× (PERF-P04). */
        private val RELEVANT_CLASS_IDS = intArrayOf(0, 1, 2, 3, 5, 7, 16, 17)

        /** COCO id → display name. Only the classes we care about for a walker are named. */
        fun classNameFor(classId: Int): String = when (classId) {
            0 -> "person"
            1 -> "bicycle"
            2 -> "car"
            3 -> "motorcycle"
            5 -> "bus"
            7 -> "truck"
            16 -> "dog"
            17 -> "cat"
            else -> "object"
        }
    }

    init {
        val modelBytes = context.assets.open(modelName).use { it.readBytes() }

        // Try execution providers in order of preference. Each candidate is not just
        // *configured* but actually *verified* with a warm-up inference before we claim
        // it — so the reported EP (and the "NPU" chip) reflects reality, never intent.
        //   1. NNAPI    — device accelerator (NPU/GPU/DSP), best-effort.
        //   2. XNNPACK  — optimized CPU kernels (reliable everywhere on ARM).
        //   3. CPU      — plain reference kernels (always works).
        var built: OrtSession? = null
        var builtEp = "CPU"
        var resolvedInput = requestedInputSize

        for (ep in listOf("NNAPI", "XNNPACK", "CPU")) {
            try {
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    when (ep) {
                        "NNAPI"   -> { setIntraOpNumThreads(4); addNnapi() }
                        // XNNPACK manages its own threadpool — force a single ORT
                        // intra-op thread and hand the worker count to the provider.
                        "XNNPACK" -> { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to "4")) }
                        else      -> { setIntraOpNumThreads(4) }
                    }
                }
                val candidate = ortEnvironment.createSession(modelBytes, opts)
                val dim = readInputSize(candidate, modelName, requestedInputSize)
                warmUp(candidate, dim)   // throws if this EP can't actually run the graph
                built = candidate
                builtEp = ep
                resolvedInput = dim
                Log.i(TAG, "Execution provider verified: $ep for $modelName")
                break
            } catch (e: Exception) {
                Log.w(TAG, "EP '$ep' unavailable — trying next. Reason: ${e.message}")
            }
        }

        ortSession = built ?: ortEnvironment.createSession(
            modelBytes,
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        )
        activeExecutionProvider = if (built != null) builtEp else "CPU"
        inputSize = resolvedInput

        pixels = IntArray(inputSize * inputSize)
        floatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)

        val family = if (skipNms) "YOLO26 (NMS-free)" else "YOLO11"
        Log.i(TAG, "Model ready: $modelName | family: $family | input: ${inputSize}px | EP: $activeExecutionProvider | HW accel: $isHardwareAccelerated")
    }

    private fun readInputSize(session: OrtSession, modelName: String, requested: Int): Int = try {
        val shape = (session.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape
        val modelDim = if (shape.size >= 4 && shape[2] > 0) shape[2].toInt() else requested
        if (modelDim != requested) {
            Log.w(TAG, "Model $modelName requires ${modelDim}px input (requested ${requested}px) — auto-correcting")
        }
        modelDim
    } catch (e: Exception) {
        Log.w(TAG, "Could not read model input shape — using ${requested}px")
        requested
    }

    /** Runs one dummy inference so we only claim an EP that genuinely executes the graph. */
    private fun warmUp(session: OrtSession, dim: Int) {
        val buf = FloatBuffer.allocate(3 * dim * dim)
        val shape = longArrayOf(1, 3, dim.toLong(), dim.toLong())
        val name = session.inputNames.iterator().next()
        OnnxTensor.createTensor(ortEnvironment, buf, shape).use { t ->
            session.run(mapOf(name to t)).use { /* discard */ }
        }
    }

    /**
     * Run detection on a full-frame bitmap (any aspect ratio). Boxes are returned in the
     * ORIGINAL frame's normalized coordinates (0‥1), already letterbox-corrected + NMS'd.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        return try {
            val t = Letterbox.compute(bitmap.width, bitmap.height, inputSize)
            val input = letterbox(bitmap, t)
            val floatBuffer = preprocessImage(input)
            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            val inputName = ortSession.inputNames.iterator().next()

            OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape).use { inputTensor ->
                ortSession.run(mapOf(inputName to inputTensor)).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val rawOutput = results[0].value as Array<Array<FloatArray>>
                    val dim2 = rawOutput[0].size
                    val dim3 = rawOutput[0][0].size
                    val isYolo26Format = dim3 == 6 && dim2 > dim3

                    val detections = if (isYolo26Format) {
                        parseYolo26(rawOutput, t)
                    } else {
                        val isStandard = dim2 < dim3
                        val numChannels = if (isStandard) dim2 else dim3
                        val numClasses = (numChannels - 4).coerceAtLeast(1)
                        parseAllObjects(rawOutput, isStandard, numClasses, t)
                    }

                    if (skipNms || isYolo26Format) detections else Nms.apply(detections, iouThreshold)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            emptyList()
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
        output: Array<Array<FloatArray>>, isStandard: Boolean, numClasses: Int, t: Letterbox.Transform
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numBoxes = if (isStandard) output[0][0].size else output[0].size
        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var classId = -1
            // Only score the handful of COCO classes we display, not all ~80.
            for (c in RELEVANT_CLASS_IDS) {
                if (c >= numClasses) continue
                val score = if (isStandard) output[0][4 + c][i] else output[0][i][4 + c]
                if (score > maxScore) { maxScore = score; classId = c }
            }
            if (maxScore >= confidenceThreshold) {
                val xc = if (isStandard) output[0][0][i] else output[0][i][0]
                val yc = if (isStandard) output[0][1][i] else output[0][i][1]
                val w  = if (isStandard) output[0][2][i] else output[0][i][2]
                val h  = if (isStandard) output[0][3][i] else output[0][i][3]
                // center/size in model px → corner px → inverse-map to original normalized
                val box = Letterbox.boxToOriginalNorm(xc - w / 2f, yc - h / 2f, xc + w / 2f, yc + h / 2f, t)
                detections.add(makeDetection(box, maxScore, classId))
            }
        }
        return detections
    }

    private fun parseYolo26(output: Array<Array<FloatArray>>, t: Letterbox.Transform): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in output[0].indices) {
            val row = output[0][i]
            val confidence = row[4]
            if (confidence < confidenceThreshold) continue
            val classId = row[5].toInt()
            val box = Letterbox.boxToOriginalNorm(row[0], row[1], row[2], row[3], t)
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
    private fun estimateDistance(boxHeight: Float, boxWidth: Float, className: String): Float {
        val focalLength = 0.87f
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
        ortSession.close()
        ortEnvironment.close()
    }
}
