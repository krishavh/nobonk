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

    /** Default mean-luma threshold below which a frame counts as "dark". */
    private const val DEFAULT_BRIGHTNESS_THRESHOLD = 35f

    /** Default luma-variance threshold below which a frame counts as "flat". */
    private const val DEFAULT_VARIANCE_THRESHOLD = 40f

    /** Default mean-luma threshold below which a frame counts as "dim" (banner). */
    private const val DEFAULT_LOW_LIGHT_THRESHOLD = 60f

    /**
     * Decides whether the camera lens is physically blocked (pocket, hand, case).
     *
     * A blocked lens is both dark AND flat: the mean luma is below
     * [brightnessThreshold] and the spatial variance across the sampled grid is
     * below [varianceThreshold]. A dim street scene fails the variance test and
     * keeps detection running.
     *
     * Non-finite inputs ([Float.NaN], [Float.POSITIVE_INFINITY],
     * [Float.NEGATIVE_INFINITY]) yield `false`. With IEEE-754 comparison semantics
     * NaN already fails both `<` comparisons, but [Float.isFinite] makes that
     * guarantee explicit and also rejects infinite readings, which are treated as a
     * sensor glitch rather than a confident "blocked" — the caller should surface a
     * separate quality indicator instead.
     *
     * Callers are expected to pass thresholds with [varianceThreshold] > 0 and
     * [brightnessThreshold] within the 0‥255 luma range; degenerate thresholds
     * (e.g. negative) simply make the corresponding test unsatisfiable, which is
     * the safe outcome (never claims "blocked").
     *
     * @param meanBrightness      average luma over the sampled grid, 0‥255
     * @param variance            variance of luma across the sampled grid (spread of
     *                            the grid-cell brightnesses); low = flat/featureless
     * @param brightnessThreshold mean luma below which the scene counts as "dark";
     *                            default 35f
     * @param varianceThreshold   luma variance below which the scene counts as "flat";
     *                            default 40f
     * @return `true` only when the frame is both dark and flat, i.e. plausibly covered
     */
    fun isBlocked(
        meanBrightness: Float,
        variance: Float,
        brightnessThreshold: Float = DEFAULT_BRIGHTNESS_THRESHOLD,
        varianceThreshold: Float = DEFAULT_VARIANCE_THRESHOLD
    ): Boolean {
        // Reject sensor glitches outright; NaN would already fail the `<` checks
        // under IEEE-754, but infinities would not, so the finite check is load-bearing.
        if (!meanBrightness.isFinite() || !variance.isFinite()) return false
        return meanBrightness < brightnessThreshold && variance < varianceThreshold
    }

    /**
     * Dim-but-not-blocked scene → detection still runs but recall/accuracy degrade
     * (Round-2 reliability indicators). Drives the "low light — reduced reliability"
     * banner so the app is honest in exactly the dusk/night cases a distracted walker
     * is at most risk. Not "blocked" (that would disable detection entirely, ML-06).
     *
     * Non-finite [meanBrightness] yields `false`: a glitchy reading should not
     * trigger a user-facing "low light" banner. Passing `blocked = true` also yields
     * `false`, so the two verdicts are mutually exclusive by construction.
     *
     * Callers should keep [lowLightThreshold] above [isBlocked]'s
     * `brightnessThreshold` so "blocked" frames are a strict subset of the dark
     * range; a lower value would only narrow the banner, never widen the blocked
     * verdict, so misconfiguration degrades gracefully.
     *
     * @param meanBrightness     average luma over the sampled grid, 0‥255
     * @param blocked            the [isBlocked] verdict for this frame (never both at once)
     * @param lowLightThreshold  mean luma below which the scene counts as "dim";
     *                           default 60f
     * @return `true` when the frame is dim enough to warn about but not blocked
     */
    fun isLowLight(
        meanBrightness: Float,
        blocked: Boolean,
        lowLightThreshold: Float = DEFAULT_LOW_LIGHT_THRESHOLD
    ): Boolean {
        // A glitchy reading must not raise a user-facing banner; NaN already fails
        // the `<` check, but the explicit finite test also rejects infinities.
        if (!meanBrightness.isFinite()) return false
        return !blocked && meanBrightness < lowLightThreshold
    }
}
