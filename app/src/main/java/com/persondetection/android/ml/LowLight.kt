package com.persondetection.android.ml

/**
 * Pure "is the camera actually blocked?" decision (fixes audit ML-06).
 *
 * The old check declared the camera blocked whenever mean brightness fell below a
 * threshold — so a dim-but-structured scene at dusk or night (exactly when a
 * distracted walker is at MOST risk) disabled ALL detection and threw up a
 * "CAMERA COVERED" overlay.
 *
 * A genuinely covered lens (pocket, hand) is not just dark — it is **flat**: almost
 * no spatial variation. A dark street still has structure (edges, highlights,
 * silhouettes). So we require **low brightness AND low spatial variance** before
 * declaring the camera blocked.
 */
object LowLight {
    /**
     * @param meanBrightness average luma over the sampled grid, 0‥255
     * @param variance       variance of luma across the sampled grid (spread of the
     *                       grid-cell brightnesses); low = flat/featureless
     */
    fun isBlocked(
        meanBrightness: Float,
        variance: Float,
        brightnessThreshold: Float = 35f,
        varianceThreshold: Float = 40f
    ): Boolean = meanBrightness < brightnessThreshold && variance < varianceThreshold
}
