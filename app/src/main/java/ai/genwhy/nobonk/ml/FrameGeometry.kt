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
        val cropLeft: Float = 0f, val cropTop: Float = 0f,   // source crop origin (ViewPort)
    )

    fun compute(srcW: Int, srcH: Int, rotationDeg: Int, inputSize: Int): Plan =
        compute(srcW, srcH, rotationDeg, inputSize, 0, 0, srcW, srcH)

    /**
     * Crop-aware variant: only the [cropW]×[cropH] region at ([cropLeft],[cropTop]) of the
     * source is rotated and scaled into the work bitmap. CameraX hands the ImageAnalysis
     * frame a crop rect matching the shared ViewPort, so normalising detections against
     * the cropped, upright frame makes them line up with the PreviewView 1:1.
     */
    fun compute(srcW: Int, srcH: Int, rotationDeg: Int, inputSize: Int,
                cropLeft: Int, cropTop: Int, cropW: Int, cropH: Int): Plan {
        val rot = ((rotationDeg % 360) + 360) % 360
        val cw = cropW.coerceIn(1, srcW.coerceAtLeast(1)); val ch = cropH.coerceIn(1, srcH.coerceAtLeast(1))
        val cl = cropLeft.coerceIn(0, (srcW - cw).coerceAtLeast(0)); val ct = cropTop.coerceIn(0, (srcH - ch).coerceAtLeast(0))
        val (rotW, rotH) = if (rot == 90 || rot == 270) ch to cw else cw to ch
        val (shiftX, shiftY) = when (rot) {
            90 -> ch.toFloat() to 0f
            180 -> cw.toFloat() to ch.toFloat()
            270 -> 0f to cw.toFloat()
            else -> 0f to 0f
        }
        val maxEdge = maxOf(rotW, rotH).coerceAtLeast(1)
        val scale = if (maxEdge <= inputSize) 1f else inputSize.toFloat() / maxEdge
        val outW = (rotW * scale).roundToInt().coerceAtLeast(1)
        val outH = (rotH * scale).roundToInt().coerceAtLeast(1)
        return Plan(rotW, rotH, shiftX, shiftY, scale, outW, outH, cl.toFloat(), ct.toFloat())
    }
}
