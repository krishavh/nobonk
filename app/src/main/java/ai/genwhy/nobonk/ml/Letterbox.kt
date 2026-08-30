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
 *
 * All functions are pure (no allocation beyond the returned values) and safe to call
 * from any thread.
 */
object Letterbox {
    /**
     * Records the letterbox transform for one frame.
     *
     * @property srcW source frame width in pixels (must be > 0 for a usable transform)
     * @property srcH source frame height in pixels (must be > 0 for a usable transform)
     * @property size model input edge length in pixels (must be > 0 for a usable transform)
     * @property scale factor applied to the source to fit inside [size]×[size];
     *   0 for degenerate (non-positive) inputs
     * @property padX left/right padding in model pixels (half the leftover width each side)
     * @property padY top/bottom padding in model pixels (half the leftover height each side)
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

        /**
         * True when this transform can map coordinates in both directions, i.e. the
         * scaled source has positive extent on both axes (guards division by zero in
         * the inverse map). Non-finite extents also count as unusable, since any
         * comparison against NaN fails and would silently poison the inverse map.
         */
        val isUsable: Boolean
            get() = scaledW > 0f && scaledW.isFinite() &&
                scaledH > 0f && scaledH.isFinite()
    }

    /**
     * Compute the letterbox transform that fits a [srcW]×[srcH] frame inside a
     * [size]×[size] model input, preserving aspect ratio and centering the content.
     *
     * Degenerate inputs (non-positive dimensions) fall back to a no-op transform
     * (scale 0, zero padding) rather than throwing or producing NaN; such transforms
     * report [Transform.isUsable] == false and map boxes to all zeros.
     */
    fun compute(srcW: Int, srcH: Int, size: Int): Transform {
        if (srcW <= 0 || srcH <= 0 || size <= 0) {
            return Transform(srcW, srcH, size, scale = 0f, padX = 0f, padY = 0f)
        }
        val sizeF = size.toFloat()
        // Scaling by size / max(srcW, srcH) is the min of the two per-axis factors:
        // it keeps the whole frame inside the square input while preserving aspect
        // ratio; the other axis is then padded.
        val scale = sizeF / maxOf(srcW, srcH).toFloat()
        // Center the scaled content: leftover space on each axis is split evenly.
        // scale <= 1 guarantees the leftover (and thus padding) is non-negative;
        // coerceAtLeast(0f) is a cheap belt-and-braces against float rounding.
        val padX = ((sizeF - srcW * scale) / 2f).coerceAtLeast(0f)
        val padY = ((sizeF - srcH * scale) / 2f).coerceAtLeast(0f)
        return Transform(srcW, srcH, size, scale, padX, padY)
    }

    /**
     * Map a box given in MODEL pixel coordinates (0‥size, as YOLO emits) back to the
     * original frame's normalized coordinates (0‥1), removing pad + scale.
     *
     * Coordinates are clamped to 0‥1 (NaN inputs are treated as 0) and re-ordered so
     * left ≤ right, top ≤ bottom. If the transform is degenerate (zero scaled extent),
     * the result is all zeros.
     */
    fun boxToOriginalNorm(
        left: Float, top: Float, right: Float, bottom: Float, t: Transform
    ): NormBox {
        if (!t.isUsable) return NormBox(0f, 0f, 0f, 0f)
        // Precompute reciprocals once: dividing by the scaled extent undoes the
        // forward scale, and subtracting the pad removes the centering offset.
        val invW = 1f / t.scaledW
        val invH = 1f / t.scaledH
        val ol = clamp01((left - t.padX) * invW)
        val ot = clamp01((top - t.padY) * invH)
        val or = clamp01((right - t.padX) * invW)
        val ob = clamp01((bottom - t.padY) * invH)
        // Re-order in case the detector emitted corners in an unexpected order.
        return NormBox(minOf(ol, or), minOf(ot, ob), maxOf(ol, or), maxOf(ot, ob))
    }

    /**
     * Forward map: a normalized-original box → model pixel coords as
     * `[left, top, right, bottom]`. (For tests.)
     *
     * For a degenerate transform every coordinate collapses to the padding value
     * (0 for the fallback transform produced by [compute]). NaN inputs propagate
     * as NaN — callers feeding detector output should prefer [boxToOriginalNorm],
     * which sanitizes.
     */
    fun originalNormToModelPx(
        box: NormBox, t: Transform
    ): FloatArray = floatArrayOf(
        box.left * t.scaledW + t.padX,
        box.top * t.scaledH + t.padY,
        box.right * t.scaledW + t.padX,
        box.bottom * t.scaledH + t.padY
    )

    /**
     * Clamp to 0‥1, mapping NaN to 0. Plain [Float.coerceIn] would propagate NaN
     * (its comparisons both fail), which would poison downstream box math.
     * Infinities clamp to the nearest bound, which is the desired saturation.
     */
    private fun clamp01(v: Float): Float =
        if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
}
