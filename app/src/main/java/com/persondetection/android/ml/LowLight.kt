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

    /**
     * Dim-but-not-blocked scene → detection still runs but recall/accuracy degrade
     * (Round-2 reliability indicators). Drives the "low light — reduced reliability"
     * banner so the app is honest in exactly the dusk/night cases a distracted walker
     * is at most risk. Not "blocked" (that would disable detection entirely, ML-06).
     *
     * @param meanBrightness average luma over the sampled grid, 0‥255
     * @param blocked        the [isBlocked] verdict for this frame (never both at once)
     */
    fun isLowLight(
        meanBrightness: Float,
        blocked: Boolean,
        lowLightThreshold: Float = 60f
    ): Boolean = !blocked && meanBrightness < lowLightThreshold
}
