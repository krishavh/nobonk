package ai.genwhy.nobonk.model

/**
 * A single object detection in normalized frame coordinates.
 *
 * Instances are immutable value objects produced by the on-device detector and
 * consumed by the tracker, alert policy, and UI layers. Because they flow across
 * layers, they deliberately depend only on pure model types ([NormBox], [AlertLevel])
 * — no Compose or Android framework types.
 *
 * @param id            Stable tracker-assigned identity for this object across
 *                      frames (not the raw class name; distinct objects of the
 *                      same class get distinct ids).
 * @param boundingBox   Normalized box (0‥1). Pure [NormBox] — no Compose dependency.
 * @param confidence    Model confidence in the range 0‥1; values outside that range
 *                      are treated as-is by consumers, so clamp at the detector
 *                      boundary if the model can emit unnormalized scores.
 * @param distance      Rough monocular distance estimate in metres. **Informational
 *                      only** (shown in the label / logged). The alarm ladder is NOT
 *                      driven by this value — see [ai.genwhy.nobonk.ml.AlertPolicy]
 *                      — because monocular distance saturates once a person fills the
 *                      frame, which historically made HIGH alerts mathematically
 *                      unreachable. May be [Float.NaN] when no estimate is available;
 *                      consumers should prefer [hasDistanceEstimate] over raw
 *                      [Float.isNaN] checks.
 * @param className     Human-readable class ("person", "car", …).
 * @param classId       Raw COCO class id (used for per-class NMS grouping).
 *                      Defaults to -1 when the raw id is unknown or irrelevant.
 * @param isApproaching True when the tracker sees this object closing distance.
 * @param alertLevel    Alert level computed by the engine for the current frame.
 */
data class Detection(
    val id: String,
    val boundingBox: NormBox,
    val confidence: Float,
    val distance: Float,
    val className: String,
    val classId: Int = -1,
    val isApproaching: Boolean = false,
    val alertLevel: AlertLevel = AlertLevel.NONE
) {
    /**
     * True when no monocular distance estimate is available for this detection.
     * Preferred over raw [Float.isNaN] checks at call sites so the "missing"
     * sentinel stays an implementation detail of this model type.
     */
    val hasDistanceEstimate: Boolean
        get() = !distance.isNaN()
}

/**
 * Alert escalation ladder used by the engine for the current frame.
 *
 * Driven by how much of the frame the object fills (a proxy for proximity that,
 * unlike the saturating monocular-distance estimate, is actually reachable) plus
 * approach escalation. See [ai.genwhy.nobonk.ml.AlertPolicy].
 *
 * Ordered by severity, so consumers can compare levels directly
 * (e.g. `level >= AlertLevel.MEDIUM`).
 */
enum class AlertLevel {
    /** Nothing close; object is far away or absent. */
    NONE,

    /** Object present, worth a glance. */
    LOW,

    /** Object getting close. */
    MEDIUM,

    /** Imminent — full-screen LOOK UP + sound. */
    HIGH;

    /**
     * True when this level warrants user-visible interruption (sound / full-screen).
     *
     * Implemented as an ordinal comparison so that inserting a future severity
     * step between MEDIUM and HIGH keeps the "at least MEDIUM" semantics intact.
     */
    val requiresInterruption: Boolean
        get() = ordinal >= MEDIUM.ordinal
}
