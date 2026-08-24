package ai.genwhy.nobonk.model

/**
 * A pure-Kotlin axis-aligned bounding box in **normalized frame coordinates**
 * (0f‥1f, origin top-left).  Deliberately has NO Android/Compose dependency so
 * the entire detection → alert pipeline can be unit-tested on a plain JVM.
 *
 * Replaces the previous use of `androidx.compose.ui.geometry.Rect`, which pulled
 * the whole safety pipeline into Android-only compilation and blocked testing.
 */
data class NormBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float  get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float   get() = width * height
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    /** Intersection-over-Union with another box. Returns 0 when disjoint. */
    fun iou(other: NormBox): Float {
        val il = maxOf(left, other.left)
        val it = maxOf(top, other.top)
        val ir = minOf(right, other.right)
        val ib = minOf(bottom, other.bottom)
        if (il >= ir || it >= ib) return 0f
        val inter = (ir - il) * (ib - it)
        val union = area + other.area - inter
        return if (union > 0f) inter / union else 0f
    }

    /** True when [px],[py] falls inside this box. */
    fun contains(px: Float, py: Float): Boolean =
        px in left..right && py in top..bottom

    companion object {
        /** Build from center + size (all normalized). */
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float): NormBox =
            NormBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}
