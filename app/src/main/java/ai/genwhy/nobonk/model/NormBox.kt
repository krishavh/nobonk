package ai.genwhy.nobonk.model

/**
 * A pure-Kotlin axis-aligned bounding box in **normalized frame coordinates**
 * (0f‥1f, origin top-left). Deliberately has NO Android/Compose dependency so
 * the entire detection → alert pipeline can be unit-tested on a plain JVM.
 *
 * Replaces the previous use of `androidx.compose.ui.geometry.Rect`, which pulled
 * the whole safety pipeline into Android-only compilation and blocked testing.
 *
 * All derived properties ([width], [height], [area]) are non-negative and safe
 * for degenerate (inverted or zero-size) inputs; [iou] guards against
 * division by zero when both boxes are degenerate.
 */
data class NormBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    /** Non-negative horizontal extent; 0 when [right] ≤ [left]. */
    val width: Float get() = (right - left).coerceAtLeast(0f)

    /** Non-negative vertical extent; 0 when [bottom] ≤ [top]. */
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    /** [width] × [height]; always non-negative. */
    val area: Float get() = width * height

    /** Midpoint of the horizontal edges. */
    val centerX: Float get() = (left + right) * 0.5f

    /** Midpoint of the vertical edges. */
    val centerY: Float get() = (top + bottom) * 0.5f

    /**
     * Intersection-over-Union with [other]. Returns 0f when the boxes are
     * disjoint, touch only at an edge/corner, or when the union area is zero
     * (both boxes degenerate).
     */
    fun iou(other: NormBox): Float {
        val il = maxOf(left, other.left)
        val it = maxOf(top, other.top)
        val ir = minOf(right, other.right)
        val ib = minOf(bottom, other.bottom)
        // Empty overlap (touching edges count as no intersection).
        if (il >= ir || it >= ib) return 0f
        val inter = (ir - il) * (ib - it)
        val union = area + other.area - inter
        return if (union > 0f) inter / union else 0f
    }

    /**
     * True when ([px], [py]) falls inside this box, inclusive of the edges.
     * Note: for inverted boxes (right < left) this is always false, matching
     * the empty range semantics of `in`.
     */
    fun contains(px: Float, py: Float): Boolean =
        px in left..right && py in top..bottom

    companion object {
        /**
         * Build a box from its center ([cx], [cy]) and size ([w], [h]),
         * all in normalized coordinates. Negative sizes produce inverted
         * edges; use [width]/[height] (which clamp to 0) for extents.
         */
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float): NormBox =
            NormBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}
