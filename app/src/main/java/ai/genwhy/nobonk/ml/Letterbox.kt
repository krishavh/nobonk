package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.NormBox

/**
 * Pure aspect-preserving letterbox geometry (fixes audit ML-04/ML-10).
 *
 * The old preprocessing squished the frame to a square with nearest-neighbour
 * sampling, distorting aspect ratio (recall loss on thin/distant pedestrians) and
 * mis-placing boxes on the preview. Here we scale by the min factor, pad the short
 * axis, and record the transform so decoded boxes can be inverse-mapped back to the
 * ORIGINAL frame's normalized coordinates — which then line up on the PreviewView.
 */
object Letterbox {
    /**
     * Records the letterbox transform for one frame.
     *
     * @property srcW source frame width in pixels (must be > 0)
     * @property srcH source frame height in pixels (must be > 0)
     * @property size model input edge length in pixels (must be > 0)
     * @property scale factor applied to the source to fit inside [size]×[size]
     * @property padX  left/right padding in model pixels
     * @property padY  top/bottom padding in model pixels
     */
    data class Transform(
        val srcW: Int,
        val srcH: Int,
        val size: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float
    ) {
        /** Source width after scaling (before padding); 0 if [scale] is 0. */
        val scaledW: Float get() = srcW * scale

        /** Source height after scaling (before padding); 0 if [scale] is 0. */
        val scaledH: Float get() = srcH * scale

        /** True when this transform can map coordinates in both directions. */
        val isUsable: Boolean get() = scaledW > 0f && scaledH > 0f
    }

    /**
     * Compute the letterbox transform that fits a [srcW]×[srcH] frame inside a
     * [size]×[size] model input, preserving aspect ratio and centering the content.
     *
     * Degenerate inputs (non-positive dimensions) fall back to a no-op transform
     * (scale 0, full padding) rather than throwing or producing NaN.
     */
    fun compute(srcW: Int, srcH: Int, size: Int): Transform {
        if (srcW <= 0 || srcH <= 0 || size <= 0) {
            return Transform(srcW, srcH, size, scale = 0f, padX = 0f, padY = 0f)
        }
        // min factor keeps the whole frame inside the square input (aspect preserved).
        val scale = minOf(size.toFloat() / srcW, size.toFloat() / srcH)
        // Center the scaled content: leftover space on each axis is split evenly.
        val padX = (size - srcW * scale) / 2f
        val padY = (size - srcH * scale) / 2f
        return Transform(srcW, srcH, size, scale, padX, padY)
    }

    /**
     * Map a box given in MODEL pixel coordinates (0‥size, as YOLO emits) back to the
     * original frame's normalized coordinates (0‥1), removing pad + scale.
     *
     * Coordinates are clamped to 0‥1 and re-ordered so left ≤ right, top ≤ bottom.
     * If the transform is degenerate (zero scaled extent), the result is all zeros.
     */
    fun boxToOriginalNorm(
        left: Float, top: Float, right: Float, bottom: Float, t: Transform
    ): NormBox {
        if (!t.isUsable) return NormBox(0f, 0f, 0f, 0f)
        val invW = 1f / t.scaledW
        val invH = 1f / t.scaledH
        val ol = ((left - t.padX) * invW).coerceIn(0f, 1f)
        val ot = ((top - t.padY) * invH).coerceIn(0f, 1f)
        val or = ((right - t.padX) * invW).coerceIn(0f, 1f)
        val ob = ((bottom - t.padY) * invH).coerceIn(0f, 1f)
        return NormBox(minOf(ol, or), minOf(ot, ob), maxOf(ol, or), maxOf(ot, ob))
    }

    /**
     * Forward map: a normalized-original box → model pixel coords as
     * `[left, top, right, bottom]`. (For tests.)
     */
    fun originalNormToModelPx(
        box: NormBox, t: Transform
    ): FloatArray = floatArrayOf(
        box.left * t.scaledW + t.padX,
        box.top * t.scaledH + t.padY,
        box.right * t.scaledW + t.padX,
        box.bottom * t.scaledH + t.padY
    )
}
