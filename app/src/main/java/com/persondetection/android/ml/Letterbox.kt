package com.persondetection.android.ml

import com.persondetection.android.model.NormBox

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
     * @param scale factor applied to the source to fit inside [size]×[size]
     * @param padX  left/right padding in model pixels
     * @param padY  top/bottom padding in model pixels
     */
    data class Transform(
        val srcW: Int,
        val srcH: Int,
        val size: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float
    ) {
        val scaledW: Float get() = srcW * scale
        val scaledH: Float get() = srcH * scale
    }

    fun compute(srcW: Int, srcH: Int, size: Int): Transform {
        val scale = minOf(size.toFloat() / srcW, size.toFloat() / srcH)
        val padX = (size - srcW * scale) / 2f
        val padY = (size - srcH * scale) / 2f
        return Transform(srcW, srcH, size, scale, padX, padY)
    }

    /**
     * Map a box given in MODEL pixel coordinates (0‥size, as YOLO emits) back to the
     * original frame's normalized coordinates (0‥1), removing pad + scale.
     */
    fun boxToOriginalNorm(
        left: Float, top: Float, right: Float, bottom: Float, t: Transform
    ): NormBox {
        val ol = ((left   - t.padX) / t.scaledW).coerceIn(0f, 1f)
        val ot = ((top    - t.padY) / t.scaledH).coerceIn(0f, 1f)
        val or = ((right  - t.padX) / t.scaledW).coerceIn(0f, 1f)
        val ob = ((bottom - t.padY) / t.scaledH).coerceIn(0f, 1f)
        return NormBox(minOf(ol, or), minOf(ot, ob), maxOf(ol, or), maxOf(ot, ob))
    }

    /** Forward map: a normalized-original box → model pixel coords. (For tests.) */
    fun originalNormToModelPx(
        box: NormBox, t: Transform
    ): FloatArray = floatArrayOf(
        box.left   * t.scaledW + t.padX,
        box.top    * t.scaledH + t.padY,
        box.right  * t.scaledW + t.padX,
        box.bottom * t.scaledH + t.padY
    )
}
