package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import java.util.UUID
import java.nio.FloatBuffer

/** Shared class selection and raw YOLO decoding, independent of Android/model loading. */
internal object CocoRawHeadDecoder {
    // COCO IDs come from the shipped model's names metadata. Cat is 15; 17 is horse.
    // Keep selection and labels together so they cannot drift independently.
    private val names = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
        "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
        "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
    ).withIndex().associate { it.index to it.value }
    private val classIds = names.keys.toIntArray()

    fun classNameFor(classId: Int): String = names[classId] ?: "object"

    fun decode(
        output: Array<Array<FloatArray>>,
        isStandard: Boolean,
        numClasses: Int,
        transform: Letterbox.Transform,
        confidenceThreshold: Float,
        distanceFor: (NormBox, String) -> Float = { _, _ -> Float.NaN }
    ): List<Detection> {
        val numBoxes = if (isStandard) output[0][0].size else output[0].size
        return decodeValues(numBoxes, numClasses, transform, confidenceThreshold, distanceFor) { channel, box ->
            if (isStandard) output[0][channel][box] else output[0][box][channel]
        }
    }

    fun decode(
        output: FloatBuffer, isStandard: Boolean, numClasses: Int, numBoxes: Int,
        transform: Letterbox.Transform, confidenceThreshold: Float,
        distanceFor: (NormBox, String) -> Float = { _, _ -> Float.NaN }
    ): List<Detection> {
        val channels = numClasses + 4
        require(numBoxes > 0 && numClasses > 0 && channels.toLong() * numBoxes <= output.remaining())
        val offset = output.position()
        return decodeValues(numBoxes, numClasses, transform, confidenceThreshold, distanceFor) { channel, box ->
            output.get(offset + if (isStandard) channel * numBoxes + box else box * channels + channel)
        }
    }

    private inline fun decodeValues(
        numBoxes: Int, numClasses: Int, transform: Letterbox.Transform, confidenceThreshold: Float,
        distanceFor: (NormBox, String) -> Float, value: (Int, Int) -> Float
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var classId = -1
            for (c in classIds) {
                if (c >= numClasses) continue
                val score = value(4 + c, i)
                if (score > maxScore) { maxScore = score; classId = c }
            }
            if (maxScore >= confidenceThreshold) {
                val xc = value(0, i)
                val yc = value(1, i)
                val w = value(2, i)
                val h = value(3, i)
                val box = Letterbox.boxToOriginalNorm(xc - w / 2f, yc - h / 2f, xc + w / 2f, yc + h / 2f, transform)
                val name = classNameFor(classId)
                detections.add(Detection(
                    id = UUID.randomUUID().toString(), boundingBox = box, confidence = maxScore,
                    distance = distanceFor(box, name), className = name, classId = classId
                ))
            }
        }
        return detections
    }
}
