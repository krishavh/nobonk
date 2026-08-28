package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection

/**
 * Pure non-maximum suppression (fixes audit ML-07/08/13/15).
 *
 * The old NMS used `iouThreshold = 0.70` (far too loose → duplicate boxes on one
 * person → alert spam) and grouped by the *collapsed display name* ("object"), which
 * lumped every unmapped COCO class into one bucket so distinct overlapping objects
 * suppressed each other. Here we group by the **true class id** and use a sane
 * `iouThreshold ≈ 0.45`.
 */
object Nms {
    /**
     * Greedy class-aware NMS.
     *
     * Detections are partitioned by [Detection.classId] so boxes of different
     * classes never suppress each other. Within each class, boxes are processed
     * in descending confidence order; any later box whose IoU with an already
     * kept box exceeds [iouThreshold] is dropped.
     *
     * The input list is not mutated. Ties in confidence keep their relative
     * input order (stable sort), so results are deterministic.
     *
     * @param detections detections to filter; may be empty
     * @param iouThreshold overlap above which a lower-confidence box is
     *   suppressed; values outside 0..1 (including NaN) are clamped to 0..1
     * @return the surviving detections, ordered by class group then descending
     *   confidence; never null
     */
    fun apply(detections: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        // Clamp so a caller passing e.g. 1.5f or -0.1f can't silently disable NMS
        // or suppress everything. NaN coerces to the low bound (0f), which is the
        // most conservative (most suppressive) behavior.
        val threshold = iouThreshold.coerceIn(0f, 1f)
        return detections
            .groupBy { it.classId }
            .flatMap { (_, group) ->
                val sorted = group.sortedByDescending { it.confidence }
                val keep = ArrayList<Detection>(sorted.size)
                val suppressed = BooleanArray(sorted.size)
                for (i in sorted.indices) {
                    if (suppressed[i]) continue
                    keep.add(sorted[i])
                    for (j in i + 1 until sorted.size) {
                        if (suppressed[j]) continue
                        // Strictly greater: a box exactly at the threshold survives.
                        if (sorted[i].boundingBox.iou(sorted[j].boundingBox) > threshold) {
                            suppressed[j] = true
                        }
                    }
                }
                keep
            }
    }
}
