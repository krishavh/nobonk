package com.persondetection.android.ml

import com.persondetection.android.model.Detection

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
    fun apply(detections: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        if (detections.isEmpty()) return emptyList()
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
                        if (sorted[i].boundingBox.iou(sorted[j].boundingBox) > iouThreshold) {
                            suppressed[j] = true
                        }
                    }
                }
                keep
            }
    }
}
