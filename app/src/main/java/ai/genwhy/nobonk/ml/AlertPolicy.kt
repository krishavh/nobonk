package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.NormBox
import java.util.Locale

/**
 * Pure alarm-ladder policy — the fix for the audit's Critical findings ML-01/ML-02.
 *
 * ## Why not distance?
 * The old ladder compared a monocular distance estimate against the user threshold.
 * That estimate saturates: once a person's bounding box fills the frame height, the
 * pinhole model floors at ~1.48 m, so HIGH (`< threshold × 0.65`) was unreachable at
 * the 0.5/1/2 m presets, and non-person objects (which only alerted `< 0.8 m`) never
 * fired at all. A person walking straight at you — the whole point of the app — never
 * triggered the loudest alarm.
 *
 * ## What we do instead
 * Alert level is driven by **fill fraction**: how much of the frame the object occupies.
 * This is monotonic in proximity right up to contact (a box that fills the frame IS
 * about to hit you), so HIGH is always reachable. Every object class gets a real
 * LOW → MEDIUM → HIGH ladder. The user's distance preset becomes a *sensitivity* knob:
 * a larger "alert at N m" means "warn me earlier", i.e. trigger at a smaller fill.
 *
 * `isApproaching` (from [ApproachTracker]) escalates one level, so a fast closer fires
 * an escalated warning before it fully fills the frame.
 *
 * All functions are pure and total: NaN/infinite inputs are normalized rather than
 * propagated, so no caller path can produce a NaN threshold or an unhandled level.
 */
object AlertPolicy {

    /** Preset at which the base ladder fractions below apply unscaled. */
    private const val REFERENCE_THRESHOLD_M = 2.0f

    /** Widest user preset accepted by [sensitivity]; anything beyond is clamped here. */
    private const val MIN_PRESET_M = 0.25f
    private const val MAX_PRESET_M = 10f

    /** Bounds of the derived sensitivity multiplier (see [sensitivity]). */
    private const val MIN_SENSITIVITY = 0.55f
    private const val MAX_SENSITIVITY = 1.7f

    /** Floor for the derived HIGH threshold in [levelFor]; keeps HIGH reachable early. */
    private const val MIN_HIGH_THRESHOLD = 0.20f

    /** Ceiling for the derived HIGH threshold; a frame-filling object always clears it. */
    private const val MAX_HIGH_THRESHOLD = 0.85f

    /** Minimum gap between adjacent ladder thresholds, keeping the ladder strictly ordered. */
    private const val THRESHOLD_GAP = 0.02f

    /** Fixed lower bounds for the derived MEDIUM / LOW thresholds (see [levelFor]). */
    private const val MIN_MEDIUM_THRESHOLD = 0.12f
    private const val MIN_LOW_THRESHOLD = 0.06f

    /**
     * Fill fractions (0‥1 of frame) for HIGH / MEDIUM / LOW at the reference preset.
     *
     * @property high   fill at or above which the alarm is HIGH
     * @property medium fill at or above which the alarm is MEDIUM
     * @property low    fill at or above which the alarm is LOW
     *
     * Callers should satisfy high > medium > low; [levelFor] re-clamps derived
     * thresholds so even a degenerate ladder still yields strictly ordered cut-offs.
     */
    data class Ladder(val high: Float, val medium: Float, val low: Float)

    /**
     * Base fill-fraction ladder for an object class. Unknown (including blank or
     * misspelled) class names fall back to the person ladder — the most conservative
     * default for a safety app: never silently under-alert on an unrecognized class.
     * Class matching is case-insensitive and ignores surrounding whitespace.
     */
    fun ladderFor(className: String): Ladder = when (className.normalizedClass()) {
        // Round-2 calibration: fill-only HIGH backstop lowered 0.70 → 0.60 so a head-on
        // person clears HIGH ~0.15 s earlier (fill-only fired at ~0.75 s to collision —
        // too late after pipeline latency). The 0.85 cap in levelFor keeps HIGH reachable.
        "person"                       -> Ladder(high = 0.60f, medium = 0.42f, low = 0.28f)
        // Vehicles are large and fast — warn earlier (a lower fill already means danger).
        "car", "truck", "bus"          -> Ladder(high = 0.55f, medium = 0.36f, low = 0.22f)
        "motorcycle", "bicycle"        -> Ladder(high = 0.52f, medium = 0.34f, low = 0.20f)
        "dog", "cat", "horse"          -> Ladder(high = 0.55f, medium = 0.38f, low = 0.24f)
        // Fallback == person ladder: conservative default for unrecognized classes.
        else                           -> Ladder(high = 0.60f, medium = 0.42f, low = 0.28f)
    }

    /**
     * Fraction of the frame the object fills, clamped to 0‥1.
     *
     * For an upright person the box HEIGHT is the reliable proximity cue; wide objects
     * (cars, bikes seen side-on) fill the frame horizontally as they close, so we take
     * the max of height and width. The clamp guarantees a finite result in 0‥1 even if
     * the detector emits out-of-range or NaN box components (NaN fails both comparison
     * branches of [Float.coerceIn], so it is normalized to 0 first).
     */
    fun fillFraction(box: NormBox, className: String): Float {
        val h = box.height.saneFraction()
        val w = box.width.saneFraction()
        return if (className.normalizedClass() == "person") h else maxOf(h, w)
    }

    /**
     * Sensitivity multiplier from the user's distance preset. A larger threshold means
     * "warn me from further away" → need LESS fill to trigger → scale thresholds DOWN.
     * Clamped so every preset stays sane and HIGH stays reachable.
     *
     * @param thresholdMeters user preset in meters; values outside 0.25‥10 are clamped
     *        before use, so non-positive or absurd inputs cannot produce a zero or
     *        negative multiplier. NaN input is treated as the reference preset
     *        (multiplier 1); ±Infinity collapses to ±0 after division, which clamps to
     *        [MIN_PRESET_M] (0.25) and thus yields the maximum sensitivity
     *        ([MAX_SENSITIVITY], 1.7) — the "warn me as early as possible" extreme.
     */
    fun sensitivity(thresholdMeters: Float): Float =
        (REFERENCE_THRESHOLD_M / thresholdMeters.saneMeters().coerceIn(MIN_PRESET_M, MAX_PRESET_M))
            .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)

    /**
     * Compute the alert level for one detection.
     *
     * @param box             normalized bounding box
     * @param className       object class
     * @param thresholdMeters user's "alert at N m" preset (sensitivity knob)
     * @param isApproaching   tracker verdict — escalates one level when true
     * @param isImminent      tracker time-to-contact verdict (TTC ≤ 1.5 s, on-bearing).
     *                        When true the alarm is FORCED to HIGH regardless of fill —
     *                        this is what finally wires the previously-dead collision
     *                        signal and gives head-on lead time (and is the ONLY way
     *                        fast vehicles/bikes ever reach HIGH, since their fill never
     *                        grows early enough at 5–10 m/s).
     */
    fun levelFor(
        box: NormBox,
        className: String,
        thresholdMeters: Float,
        isApproaching: Boolean,
        isImminent: Boolean = false
    ): AlertLevel {
        if (isImminent) return AlertLevel.HIGH
        val base = baseLevel(box, className, thresholdMeters)
        return if (isApproaching) escalate(base) else base
    }

    /**
     * Fill-only ladder lookup: derives strictly ordered HIGH/MEDIUM/LOW thresholds from
     * the class ladder scaled by the sensitivity multiplier, then compares the object's
     * fill fraction against them.
     */
    private fun baseLevel(
        box: NormBox,
        className: String,
        thresholdMeters: Float
    ): AlertLevel {
        val fill = fillFraction(box, className)
        val s = sensitivity(thresholdMeters)
        val ladder = ladderFor(className)

        // Cap HIGH at 0.85 so a frame-filling object (fill up to 1.0) ALWAYS reaches it,
        // at every preset — this is what makes the alarm physically reachable.
        val high = (ladder.high * s).coerceIn(MIN_HIGH_THRESHOLD, MAX_HIGH_THRESHOLD)
        // Each lower threshold is clamped [THRESHOLD_GAP] below the one above, keeping the
        // ladder strictly ordered even at extreme sensitivity settings. The fixed lower
        // bounds are always below the derived upper bounds because `high ≥ 0.20` implies
        // `high - 0.02 ≥ 0.18 > 0.12`, and `med ≥ 0.12` implies `med - 0.02 ≥ 0.10 > 0.06`
        // — so [Float.coerceIn] can never see min > max here.
        val med = (ladder.medium * s).coerceIn(MIN_MEDIUM_THRESHOLD, high - THRESHOLD_GAP)
        val low = (ladder.low * s).coerceIn(MIN_LOW_THRESHOLD, med - THRESHOLD_GAP)

        return when {
            fill >= high -> AlertLevel.HIGH
            fill >= med  -> AlertLevel.MEDIUM
            fill >= low  -> AlertLevel.LOW
            else         -> AlertLevel.NONE
        }
    }

    /**
     * Bump one level. A closing object is more dangerous than a static one at the
     * same size; HIGH is idempotent (there is nothing louder).
     */
    fun escalate(level: AlertLevel): AlertLevel = when (level) {
        AlertLevel.NONE   -> AlertLevel.LOW
        AlertLevel.LOW    -> AlertLevel.MEDIUM
        AlertLevel.MEDIUM -> AlertLevel.HIGH
        AlertLevel.HIGH   -> AlertLevel.HIGH
    }

    /**
     * Canonical form of a detector class label: trimmed and lower-cased (locale-
     * independent via [Locale.ROOT], so a device set to e.g. Turkish cannot break
     * ASCII matching), so "Person" / " PERSON " match the ladders above. Unknown
     * labels fall back to the person ladder in [ladderFor], the most conservative
     * default.
     */
    private fun String.normalizedClass(): String = trim().lowercase(Locale.ROOT)

    /**
     * Normalize a raw box dimension to a finite fraction in 0‥1: NaN → 0, then clamp.
     * Needed because [Float.coerceIn] passes NaN through unchanged (both comparisons fail).
     */
    private fun Float.saneFraction(): Float =
        if (isNaN()) 0f else coerceIn(0f, 1f)

    /**
     * Normalize a raw distance preset to a finite, positive meter value: NaN → the
     * reference preset; ±Infinity is left as-is and clamped by the caller's bounds.
     */
    private fun Float.saneMeters(): Float =
        if (isNaN()) REFERENCE_THRESHOLD_M else this
}
