package com.persondetection.android.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Rect
import com.persondetection.android.model.Detection
import java.nio.FloatBuffer
import java.util.UUID

/**
 * Object detector using ONNX Runtime — supports both YOLO11 and YOLO26 model families.
 *
 * Execution provider priority:
 *   1. NNAPI  — hardware NPU/DSP (3-5× faster, lower battery)
 *   2. CPU    — multi-threaded fallback if NNAPI is unavailable
 *
 * [isHardwareAccelerated] exposes which path is active so the UI can show it.
 *
 * ── Model family differences ────────────────────────────────────────────────
 * YOLO11: output [1, 84, 8400] — 8400 anchor proposals, layout cx,cy,w,h + 80
 *         class probability scores per proposal; NMS required.
 * YOLO26: output [1, 300, 6]   — 300 final detections (NMS-free architecture),
 *         layout x1,y1,x2,y2,confidence,class_id per detection.
 *         DFL head removed → simpler ONNX graph → better NNAPI compatibility.
 * The two formats are auto-detected by shape: dim3 == 6 triggers the YOLO26
 * corner-coordinate parser; otherwise the YOLO11 center+size parser is used.
 */
class ObjectDetector(
    context: Context,
    modelName: String = "yolo11s.onnx",
    requestedInputSize: Int = 416,
    /**
     * Set to true for YOLO26 models: their one-to-one assignment head produces
     * non-overlapping predictions so NMS is unnecessary and can be skipped to
     * shave a few ms off post-processing.
     */
    val skipNms: Boolean = false
) {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    /** True when inference is running on the NPU/DSP via NNAPI. */
    var isHardwareAccelerated: Boolean = false
        private set

    private val confidenceThreshold = 0.25f
    private val iouThreshold = 0.70f

    /**
     * Effective input resolution.  Auto-detected from the ONNX model's actual
     * input tensor shape so we never feed a 416-px tensor to a model compiled
     * for 640-px (which causes a silent shape-mismatch error and zero detections).
     * Falls back to [requestedInputSize] only when the model uses dynamic dims.
     */
    val inputSize: Int

    // Pre-allocated per-frame buffers — sized after inputSize is resolved.
    private val pixels: IntArray
    private val floatBuffer: FloatBuffer

    companion object {
        private const val TAG = "ObjectDetector"
    }

    init {
        val modelBytes = context.assets.open(modelName).use { it.readBytes() }

        // ── Attempt 1: NNAPI hardware acceleration ───────────────────────────
        var session: OrtSession? = null
        try {
            val nnOpts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                addNnapi()
            }
            session = ortEnvironment.createSession(modelBytes, nnOpts)
            isHardwareAccelerated = true
            Log.i(TAG, "NNAPI hardware acceleration enabled for $modelName")
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI session failed — falling back to CPU. Reason: ${e.message}")
            isHardwareAccelerated = false
        }

        // ── Attempt 2: CPU-only fallback ─────────────────────────────────────
        if (session == null) {
            try {
                val cpuOpts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(4)
                }
                session = ortEnvironment.createSession(modelBytes, cpuOpts)
                Log.i(TAG, "CPU fallback session created for $modelName")
            } catch (e: Exception) {
                Log.e(TAG, "CPU session creation also failed: ${e.message}", e)
                throw e
            }
        }

        ortSession = session!!

        // ── Auto-detect actual input size from the ONNX graph ────────────────
        // Ultralytics YOLO11 models are exported at 640 px with static input
        // shape.  Feeding a 416-px tensor silently throws a shape mismatch and
        // returns zero detections.  Reading the real shape fixes this.
        inputSize = try {
            @Suppress("UNCHECKED_CAST")
            val shape = (ortSession.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape
            val modelDim = if (shape.size >= 4 && shape[2] > 0) shape[2].toInt() else requestedInputSize
            if (modelDim != requestedInputSize) {
                Log.w(TAG, "Model $modelName requires ${modelDim}px input (requested ${requestedInputSize}px) — auto-correcting")
            }
            modelDim
        } catch (e: Exception) {
            Log.w(TAG, "Could not read model input shape — using ${requestedInputSize}px")
            requestedInputSize
        }

        pixels = IntArray(inputSize * inputSize)
        floatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)

        val family = if (skipNms) "YOLO26 (NMS-free)" else "YOLO11"
        Log.i(TAG, "Model ready: $modelName | family: $family | input: ${inputSize}px | HW accel: $isHardwareAccelerated")
    }
    
    fun detectPeople(bitmap: Bitmap): List<Detection> {
        return try {
            val resized = if (bitmap.width == inputSize && bitmap.height == inputSize) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
            }
            val floatBuffer = preprocessImage(resized)
            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            val inputName = ortSession.inputNames.iterator().next()

            OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape).use { inputTensor ->
                ortSession.run(mapOf(inputName to inputTensor)).use { results ->
                    val outputValue = results[0].value
                    @Suppress("UNCHECKED_CAST")
                    val rawOutput = outputValue as Array<Array<FloatArray>>

                    val dim2 = rawOutput[0].size
                    val dim3 = rawOutput[0][0].size

                    // YOLO26 NMS-free models output [1, N, 6]: x1,y1,x2,y2,conf,classId
                    val isYolo26Format = dim3 == 6 && dim2 > dim3

                    Log.d(TAG, "Output: [1, $dim2, $dim3] yolo26=$isYolo26Format skipNms=$skipNms")

                    val detections = if (isYolo26Format) {
                        parseYolo26(rawOutput)
                    } else {
                        // YOLO11: [1, channels, N] (dim2 < dim3) or [1, N, channels]
                        val isStandard = dim2 < dim3
                        val numChannels = if (isStandard) dim2 else dim3
                        val numClasses = (numChannels - 4).coerceAtLeast(1)
                        parseAllObjects(rawOutput, isStandard, numClasses)
                    }

                    val result = if (skipNms || isYolo26Format) detections else applyNMS(detections, iouThreshold)
                    Log.d(TAG, "Detections: ${detections.size} raw -> ${result.size} final")
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        // Reuse the pre-allocated pixel and float arrays — no GC allocation per frame.
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val pixelCount = inputSize * inputSize
        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatBuffer.put(i,               ((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.put(pixelCount + i,  ((pixel shr  8) and 0xFF) / 255.0f)
            floatBuffer.put(2 * pixelCount + i, (pixel and 0xFF)       / 255.0f)
        }
        floatBuffer.rewind()
        return floatBuffer
    }
    
    /**
     * Unified parser — numClasses is read from the actual ONNX output shape
     * so this works whether the model has 80 COCO classes, 1 class, or any
     * other count without throwing ArrayIndexOutOfBoundsException.
     */
    private fun parseAllObjects(output: Array<Array<FloatArray>>, isStandard: Boolean, numClasses: Int = 80): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numBoxes = if (isStandard) output[0][0].size else output[0].size

        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var classId = -1

            for (c in 0 until numClasses) {
                val score = if (isStandard) output[0][4 + c][i] else output[0][i][4 + c]
                if (score > maxScore) {
                    maxScore = score
                    classId = c
                }
            }
            
            if (maxScore >= confidenceThreshold) {
                val xc = if (isStandard) output[0][0][i] else output[0][i][0]
                val yc = if (isStandard) output[0][1][i] else output[0][i][1]
                val w = if (isStandard) output[0][2][i] else output[0][i][2]
                val h = if (isStandard) output[0][3][i] else output[0][i][3]
                
                val className = when(classId) {
                    0 -> "person"
                    1 -> "bicycle"
                    2 -> "car"
                    3 -> "motorcycle"
                    16 -> "dog"
                    else -> "object"
                }
                
                detections.add(createDetection(xc, yc, w, h, maxScore, className))
            }
        }
        return detections
    }

    /**
     * YOLO26 NMS-free parser for [1, N, 6] output.
     * Each row: [x1, y1, x2, y2, confidence, class_id] in pixel coordinates.
     */
    private fun parseYolo26(output: Array<Array<FloatArray>>): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numBoxes = output[0].size

        for (i in 0 until numBoxes) {
            val row = output[0][i]
            val confidence = row[4]
            if (confidence < confidenceThreshold) continue

            val classId = row[5].toInt()
            val className = when (classId) {
                0 -> "person"
                1 -> "bicycle"
                2 -> "car"
                3 -> "motorcycle"
                16 -> "dog"
                else -> "object"
            }

            // x1,y1,x2,y2 in pixel coords → normalize to [0,1]
            val left   = (row[0] / inputSize).coerceIn(0f, 1f)
            val top    = (row[1] / inputSize).coerceIn(0f, 1f)
            val right  = (row[2] / inputSize).coerceIn(0f, 1f)
            val bottom = (row[3] / inputSize).coerceIn(0f, 1f)
            val nw = right - left
            val nh = bottom - top

            detections.add(
                Detection(
                    id = UUID.randomUUID().toString(),
                    boundingBox = Rect(left = left, top = top, right = right, bottom = bottom),
                    confidence = confidence,
                    distance = estimateDistance(nh, nw, className),
                    className = className
                )
            )
        }
        return detections
    }

    private fun createDetection(xc: Float, yc: Float, w: Float, h: Float, score: Float, cls: String): Detection {
        val x = (xc - w / 2) / inputSize
        val y = (yc - h / 2) / inputSize
        val nw = w / inputSize
        val nh = h / inputSize

        return Detection(
            id = UUID.randomUUID().toString(),
            boundingBox = Rect(
                left = x.coerceIn(0f, 1f),
                top = y.coerceIn(0f, 1f),
                right = (x + nw).coerceIn(0f, 1f),
                bottom = (y + nh).coerceIn(0f, 1f)
            ),
            confidence = score,
            distance = estimateDistance(nh, nw, cls),  // Pass width for aspect-ratio correction
            className = cls
        )
    }
    
    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        // Group by class so NMS never suppresses detections of different object types
        // (e.g. a person partially behind a car should not be eliminated by the car)
        return detections
            .groupBy { it.className }
            .flatMap { (_, classDetections) ->
                val sorted = classDetections.sortedByDescending { it.confidence }
                val keep = mutableListOf<Detection>()
                val suppressed = mutableSetOf<Int>()
                for (i in sorted.indices) {
                    if (suppressed.contains(i)) continue
                    keep.add(sorted[i])
                    for (j in (i + 1) until sorted.size) {
                        if (suppressed.contains(j)) continue
                        if (calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold) {
                            suppressed.add(j)
                        }
                    }
                }
                keep
            }
    }
    
    private fun calculateIoU(box1: Rect, box2: Rect): Float {
        val intersectLeft = maxOf(box1.left, box2.left)
        val intersectTop = maxOf(box1.top, box2.top)
        val intersectRight = minOf(box1.right, box2.right)
        val intersectBottom = minOf(box1.bottom, box2.bottom)
        if (intersectLeft >= intersectRight || intersectTop >= intersectBottom) return 0f
        val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val unionArea = (box1.width * box1.height) + (box2.width * box2.height) - intersectionArea
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * Pinhole camera model: distance = (realHeight × focalLengthNormalized) / boxHeight
     *
     * focalLengthNormalized = 1 / (2 × tan(vFOV/2))
     * For a typical smartphone at ~60° vertical FOV: focalLengthNormalized ≈ 0.87
     *
     * realHeight is the average standing/shoulder height for each class.
     * k = realHeight × focalLengthNormalized
     *
     * Old formula: k = 1.0 × 1.1 = 1.1  →  underestimates every reading by ~25%
     * Correct k for a person: 1.70m × 0.87 = 1.48  →  accurate at all ranges
     *
     * Aspect-ratio correction (persons only):
     * A standing person's bounding box has aspect ≈ 0.40 (width/height).
     * If the box is wider than that, only a partial body (torso/face) is visible.
     * The box height then under-represents the person's real height, causing the
     * formula to over-estimate distance.  We scale k down proportionally:
     *   correction = min(1, typicalAspect / actualAspect)
     * This brings the estimate back in line for partial views.
     */
    private fun estimateDistance(boxHeight: Float, boxWidth: Float, className: String): Float {
        // Focal length (normalized to frame height) for ~60° vFOV smartphone camera
        val focalLength = 0.87f

        // Real-world height and typical bounding-box aspect ratio (width / height) per class
        val (realHeightM, typicalAspect) = when (className) {
            "person"     -> Pair(1.70f, 0.40f)   // avg adult standing height
            "dog"        -> Pair(0.45f, 1.40f)   // avg shoulder height; dogs are wider than tall
            "car"        -> Pair(1.50f, 1.80f)   // avg roof height; cars are much wider than tall
            "motorcycle" -> Pair(1.10f, 0.90f)
            "bicycle"    -> Pair(1.00f, 0.75f)
            else         -> Pair(1.20f, 0.80f)
        }

        // Aspect-ratio correction for persons:
        // A high aspect ratio signals a partial view (torso/face only).
        // The visible fraction of the person's height ≈ typicalAspect / actualAspect.
        // A smaller visible fraction means boxHeight represents less of the real height,
        // so the raw formula would over-estimate distance — scale k down to compensate.
        val aspectRatio = if (boxHeight > 0.001f) boxWidth / boxHeight else typicalAspect
        val partialBodyCorrection = if (className == "person" && aspectRatio > typicalAspect) {
            (typicalAspect / aspectRatio).coerceIn(0.50f, 1.0f)
        } else {
            1.0f
        }

        val k = realHeightM * focalLength * partialBodyCorrection
        val distance = k / maxOf(boxHeight, 0.005f)
        return distance.coerceIn(0.1f, 15.0f)
    }
    
    fun close() {
        ortSession.close()
        ortEnvironment.close()
    }
}
