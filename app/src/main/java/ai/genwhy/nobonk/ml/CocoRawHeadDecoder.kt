package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import java.util.UUID

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
        val detections = mutableListOf<Detection>()
        val numBoxes = if (isStandard) output[0][0].size else output[0].size
        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var classId = -1
            for (c in classIds) {
                if (c >= numClasses) continue
                val score = if (isStandard) output[0][4 + c][i] else output[0][i][4 + c]
                if (score > maxScore) { maxScore = score; classId = c }
            }
            if (maxScore >= confidenceThreshold) {
                val xc = if (isStandard) output[0][0][i] else output[0][i][0]
                val yc = if (isStandard) output[0][1][i] else output[0][i][1]
                val w = if (isStandard) output[0][2][i] else output[0][i][2]
                val h = if (isStandard) output[0][3][i] else output[0][i][3]
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
