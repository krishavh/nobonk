package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import java.util.UUID
import java.nio.FloatBuffer

/** Shared class selection and raw YOLO decoding, independent of Android/model loading. */
internal object CocoRawHeadDecoder {
    // COCO IDs come from the shipped model's names metadata. Cat is 15; 17 is horse.
    // Keep selection and labels together so they cannot drift independently.
    private val names = linkedMapOf(
        0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle",
        5 to "bus", 7 to "truck", 16 to "dog", 15 to "cat"
    )
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
