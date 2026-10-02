package ai.genwhy.nobonk.service

/** Keep a draggable overlay within the usable display, including after rotation. */
object OverlayPosition {
    data class Point(val x: Int, val y: Int)
    fun clamp(x: Int, y: Int, screenWidth: Int, screenHeight: Int, width: Int, height: Int,
              leftInset: Int, topInset: Int, rightInset: Int, bottomInset: Int): Point {
        val left = leftInset.coerceAtLeast(0)
        val top = topInset.coerceAtLeast(0)
        val right = (screenWidth - rightInset.coerceAtLeast(0) - width).coerceAtLeast(left)
        val bottom = (screenHeight - bottomInset.coerceAtLeast(0) - height).coerceAtLeast(top)
        return Point(x.coerceIn(left, right), y.coerceIn(top, bottom))
    }
}
