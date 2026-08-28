package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.NormBox

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
 */
object AlertPolicy {

    /** Preset at which the base ladder fractions below apply unscaled. */
    private const val REFERENCE_THRESHOLD_M = 2.0f

    /**
     * Fill fractions (0‥1 of frame) for HIGH / MEDIUM / LOW at the reference preset.
     * Must satisfy high > medium > low; [levelFor] re-clamps derived thresholds so
     * a degenerate ladder still yields strictly ordered cut-offs.
     */
    data class Ladder(val high: Float, val medium: Float, val low: Float)

    /**
     * Base fill-fraction ladder for an object class. Unknown classes fall back to
     * the person ladder (the most conservative default for a safety app).
     */
    fun ladderFor(className: String): Ladder = when (className) {
        // Round-2 calibration: fill-only HIGH backstop lowered 0.70 → 0.60 so a head-on
        // person clears HIGH ~0.15 s earlier (fill-only fired at ~0.75 s to collision —
        // too late after pipeline latency). The 0.85 cap in levelFor keeps HIGH reachable.
        "person"                       -> Ladder(high = 0.60f, medium = 0.42f, low = 0.28f)
        // Vehicles are large and fast — warn earlier (a lower fill already means danger).
        "car", "truck", "bus"          -> Ladder(high = 0.55f, medium = 0.36f, low = 0.22f)
        "motorcycle", "bicycle"        -> Ladder(high = 0.52f, medium = 0.34f, low = 0.20f)
        "dog", "cat", "horse"          -> Ladder(high = 0.55f, medium = 0.38f, low = 0.24f)
        else                           -> Ladder(high = 0.60f, medium = 0.42f, low = 0.28f)
    }

    /**
     * Fraction of the frame the object fills, clamped to 0‥1. For an upright person
     * the box HEIGHT is the reliable proximity cue; wide objects (cars, bikes seen
     * side-on) fill the frame horizontally as they close, so we take the max of
     * height and width. A null/NaN-free result is guaranteed by the clamp.
     */
    fun fillFraction(box: NormBox, className: String): Float {
        val h = box.height.coerceIn(0f, 1f)
        val w = box.width.coerceIn(0f, 1f)
        return if (className == "person") h else maxOf(h, w)
    }

    /**
     * Sensitivity multiplier from the user's distance preset. A larger threshold means
     * "warn me from further away" → need LESS fill to trigger → scale thresholds DOWN.
     * Clamped so every preset stays sane and HIGH stays reachable.
     *
     * @param thresholdMeters user preset in meters; values outside 0.25‥10 are clamped
     *        before use, so non-positive or absurd inputs cannot produce a zero or
     *        negative multiplier.
     */
    fun sensitivity(thresholdMeters: Float): Float =
        (REFERENCE_THRESHOLD_M / thresholdMeters.coerceIn(0.25f, 10f)).coerceIn(0.55f, 1.7f)

    /**
     * Compute the alert level for one detection.
     *
     * @param box            normalized bounding box
     * @param className      object class
     * @param thresholdMeters user's "alert at N m" preset (sensitivity knob)
     * @param isApproaching  tracker verdict — escalates one level when true
     * @param isImminent     tracker time-to-contact verdict (TTC ≤ 1.5 s, on-bearing).
     *                       When true the alarm is FORCED to HIGH regardless of fill —
     *                       this is what finally wires the previously-dead collision
     *                       signal and gives head-on lead time (and is the ONLY way
     *                       fast vehicles/bikes ever reach HIGH, since their fill never
     *                       grows early enough at 5–10 m/s).
     */
    fun levelFor(
        box: NormBox,
        className: String,
        thresholdMeters: Float,
        isApproaching: Boolean,
        isImminent: Boolean = false
    ): AlertLevel {
        if (isImminent) return AlertLevel.HIGH
        val fill = fillFraction(box, className)
        val s = sensitivity(thresholdMeters)
        val ladder = ladderFor(className)

        // Cap HIGH at 0.85 so a frame-filling object (fill up to 1.0) ALWAYS reaches it,
        // at every preset — this is what makes the alarm physically reachable.
        val high = (ladder.high * s).coerceIn(0.20f, 0.85f)
        // Each lower threshold is clamped 0.02 below the one above, keeping the ladder
        // strictly ordered even at extreme sensitivity settings.
        val med  = (ladder.medium * s).coerceIn(0.12f, high - 0.02f)
        val low  = (ladder.low * s).coerceIn(0.06f, med - 0.02f)

        val base = when {
            fill >= high -> AlertLevel.HIGH
            fill >= med  -> AlertLevel.MEDIUM
            fill >= low  -> AlertLevel.LOW
            else         -> AlertLevel.NONE
        }
        return if (isApproaching) escalate(base) else base
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
}
