package ai.genwhy.nobonk.ml

import kotlin.math.roundToInt

/**
 * Pure geometry for the per-frame "rotate to upright, then downscale so the longest
 * edge == inputSize" step. Kept free of android.graphics so it is unit-testable; the
 * engine feeds these numbers into one Matrix and one Canvas draw into a REUSABLE
 * bitmap, replacing three per-frame Bitmap allocations (toBitmap → rotate → scale).
 *
 * Rotation is CameraX `rotationDegrees` (0/90/180/270, clockwise). After
 * `Matrix.postRotate(rot)` about the origin the image lands in a negative quadrant;
 * ([shiftX],[shiftY]) translates it back so its top-left is (0,0) at rotated size.
 */
object FrameGeometry {
    data class Plan(
        val rotW: Int, val rotH: Int,   // upright (rotated) dimensions
        val shiftX: Float, val shiftY: Float,
        val scale: Float,                // <= 1, never upscales
        val outW: Int, val outH: Int,    // work bitmap size
    )

    fun compute(srcW: Int, srcH: Int, rotationDeg: Int, inputSize: Int): Plan {
        val rot = ((rotationDeg % 360) + 360) % 360
        val (rotW, rotH) = if (rot == 90 || rot == 270) srcH to srcW else srcW to srcH
        val (shiftX, shiftY) = when (rot) {
            90 -> srcH.toFloat() to 0f
            180 -> srcW.toFloat() to srcH.toFloat()
            270 -> 0f to srcW.toFloat()
            else -> 0f to 0f
        }
        val maxEdge = maxOf(rotW, rotH).coerceAtLeast(1)
        val scale = if (maxEdge <= inputSize) 1f else inputSize.toFloat() / maxEdge
        val outW = (rotW * scale).roundToInt().coerceAtLeast(1)
        val outH = (rotH * scale).roundToInt().coerceAtLeast(1)
        return Plan(rotW, rotH, shiftX, shiftY, scale, outW, outH)
    }
}
