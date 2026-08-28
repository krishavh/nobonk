package ai.genwhy.nobonk.ml

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
 *
 * All functions are pure and side-effect free so they can be unit-tested without
 * any Android or ML machinery.
 */
object LowLight {

    /**
     * Decides whether the camera lens is physically blocked (pocket, hand, case).
     *
     * A blocked lens is both dark AND flat: the mean luma is below
     * [brightnessThreshold] and the spatial variance across the sampled grid is
     * below [varianceThreshold]. A dim street scene fails the variance test and
     * keeps detection running.
     *
     * Non-finite inputs ([Float.NaN], ±[Float.Infinity]) yield `false`: with IEEE-754
     * comparison semantics NaN already fails both comparisons, and an infinite
     * reading is treated as a sensor glitch rather than a confident "blocked" —
     * the caller should surface a separate quality indicator instead.
     *
     * @param meanBrightness      average luma over the sampled grid, 0‥255
     * @param variance            variance of luma across the sampled grid (spread of
     *                            the grid-cell brightnesses); low = flat/featureless
     * @param brightnessThreshold mean luma below which the scene counts as "dark"
     * @param varianceThreshold   luma variance below which the scene counts as "flat"
     * @return `true` only when the frame is both dark and flat, i.e. plausibly covered
     */
    fun isBlocked(
        meanBrightness: Float,
        variance: Float,
        brightnessThreshold: Float = 35f,
        varianceThreshold: Float = 40f
    ): Boolean =
        meanBrightness.isFinite() &&
            variance.isFinite() &&
            meanBrightness < brightnessThreshold &&
            variance < varianceThreshold

    /**
     * Dim-but-not-blocked scene → detection still runs but recall/accuracy degrade
     * (Round-2 reliability indicators). Drives the "low light — reduced reliability"
     * banner so the app is honest in exactly the dusk/night cases a distracted walker
     * is at most risk. Not "blocked" (that would disable detection entirely, ML-06).
     *
     * Non-finite [meanBrightness] yields `false`: a glitchy reading should not
     * trigger a user-facing "low light" banner.
     *
     * @param meanBrightness     average luma over the sampled grid, 0‥255
     * @param blocked            the [isBlocked] verdict for this frame (never both at once)
     * @param lowLightThreshold  mean luma below which the scene counts as "dim"
     * @return `true` when the frame is dim enough to warn about but not blocked
     */
    fun isLowLight(
        meanBrightness: Float,
        blocked: Boolean,
        lowLightThreshold: Float = 60f
    ): Boolean =
        meanBrightness.isFinite() &&
            !blocked &&
            meanBrightness < lowLightThreshold
}
