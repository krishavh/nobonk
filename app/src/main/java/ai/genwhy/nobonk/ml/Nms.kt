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
 *
 * This object has no mutable state and performs no I/O, so it is safe to call from
 * any thread, including the inference loop.
 */
object Nms {
    /**
     * Default IoU overlap above which a lower-confidence box is suppressed.
     * Chosen as a middle ground: loose enough that genuinely distinct objects
     * survive, tight enough that duplicate boxes on the same object collapse.
     */
    private const val DEFAULT_IOU_THRESHOLD = 0.45f

    /**
     * Greedy class-aware NMS.
     *
     * Detections are partitioned by [Detection.classId] so boxes of different
     * classes never suppress each other. Within each class, boxes are processed
     * in descending confidence order; any later box whose IoU with an already
     * kept box exceeds [iouThreshold] is dropped.
     *
     * The input list is not mutated. [sortedByDescending] is a stable sort, so
     * ties in confidence keep their relative input order and results are
     * deterministic for a given input.
     *
     * Complexity is O(C · n²) worst case for n boxes in C class groups; typical
     * per-frame detection counts (tens of boxes) make this negligible.
     *
     * @param detections detections to filter; may be empty
     * @param iouThreshold overlap above which a lower-confidence box is
     *   suppressed; values outside 0..1 (including NaN) are clamped to 0..1
     * @return the surviving detections, ordered by class group then descending
     *   confidence; never null
     */
    fun apply(
        detections: List<Detection>,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    ): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        // Clamp so a caller passing e.g. 1.5f or -0.1f can't silently disable NMS
        // or suppress everything. NaN coerces to the low bound (0f), which is the
        // most conservative (most suppressive) behavior.
        val threshold = iouThreshold.coerceIn(0f, 1f)
        // Pre-size the sink to the input size: the result can never exceed it,
        // so the backing array is never grown mid-flatMap.
        return detections
            .groupBy { it.classId }
            .flatMapTo(ArrayList(detections.size)) { (_, group) ->
                suppressWithinClass(group, threshold)
            }
    }

    /**
     * Greedy NMS within a single class group: sort by descending confidence,
     * then repeatedly keep the highest-confidence surviving box and suppress
     * every remaining box whose IoU with it exceeds [threshold].
     *
     * @param group detections of a single class; may be empty
     * @param threshold already-clamped IoU threshold in 0..1
     * @return the surviving detections in descending confidence order
     */
    private fun suppressWithinClass(group: List<Detection>, threshold: Float): List<Detection> {
        if (group.isEmpty()) return emptyList()
        val sorted = group.sortedByDescending { it.confidence }
        val keep = ArrayList<Detection>(sorted.size)
        // suppressed[i] marks boxes already dropped by an earlier keep;
        // skipping them keeps the inner loop O(n) per kept box.
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val kept = sorted[i]
            keep.add(kept)
            // Suppress every remaining lower-confidence box that overlaps the
            // newly kept one. Strictly greater: a box exactly at the threshold
            // survives.
            val keptBox = kept.boundingBox
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j] && keptBox.iou(sorted[j].boundingBox) > threshold) {
                    suppressed[j] = true
                }
            }
        }
        return keep
    }
}
