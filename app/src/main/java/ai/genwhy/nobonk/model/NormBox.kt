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

    /** Midpoint of the horizontal edges; well-defined even for inverted boxes. */
    val centerX: Float get() = (left + right) * 0.5f

    /** Midpoint of the vertical edges; well-defined even for inverted boxes. */
    val centerY: Float get() = (top + bottom) * 0.5f

    /**
     * Intersection-over-Union with [other], in 0f‥1f.
     *
     * Returns 0f when the boxes are disjoint, touch only at an edge/corner
     * (zero-area intersection), or when the union area is zero (both boxes
     * degenerate) — avoiding division by zero in the last case.
     */
    fun iou(other: NormBox): Float {
        val interLeft = maxOf(left, other.left)
        val interTop = maxOf(top, other.top)
        val interRight = minOf(right, other.right)
        val interBottom = minOf(bottom, other.bottom)
        // Empty overlap (touching edges count as no intersection).
        if (interLeft >= interRight || interTop >= interBottom) return 0f
        val inter = (interRight - interLeft) * (interBottom - interTop)
        // Union = sum of areas minus the double-counted intersection.
        val union = area + other.area - inter
        // Union can only be ≤ 0 when both boxes are degenerate (zero area).
        return if (union > 0f) inter / union else 0f
    }

    /**
     * True when ([px], [py]) falls inside this box, inclusive of the edges.
     *
     * Note: for inverted boxes (right < left or bottom < top) the `..` range
     * is empty, so this is always false — matching empty-range semantics.
     */
    fun contains(px: Float, py: Float): Boolean =
        px in left..right && py in top..bottom

    companion object {
        /**
         * Build a box from its center ([cx], [cy]) and size ([w], [h]),
         * all in normalized coordinates. Negative sizes produce inverted
         * edges; use [width]/[height] (which clamp to 0) for extents.
         */
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float): NormBox {
            val halfW = w * 0.5f
            val halfH = h * 0.5f
            return NormBox(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
    }
}
