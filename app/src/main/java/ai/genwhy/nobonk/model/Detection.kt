package ai.genwhy.nobonk.model

/**
 * A single object detection in normalized frame coordinates.
 *
 * Instances are immutable value objects produced by the on-device detector and
 * consumed by the tracker, alert policy, and UI layers.
 *
 * @param id            Stable tracker-assigned identity for this object across
 *                      frames (not the raw class name; distinct objects of the
 *                      same class get distinct ids).
 * @param boundingBox   Normalized box (0‥1). Pure [NormBox] — no Compose dependency.
 * @param confidence    Model confidence 0‥1.
 * @param distance      Rough monocular distance estimate in metres. **Informational
 *                      only** (shown in the label / logged). The alarm ladder is NOT
 *                      driven by this value — see [ai.genwhy.nobonk.ml.AlertPolicy]
 *                      — because monocular distance saturates once a person fills the
 *                      frame, which historically made HIGH alerts mathematically
 *                      unreachable.
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
)

/**
 * Alert escalation ladder used by the engine for the current frame.
 *
 * Driven by how much of the frame the object fills (a proxy for proximity that,
 * unlike the saturating monocular-distance estimate, is actually reachable) plus
 * approach escalation. See [ai.genwhy.nobonk.ml.AlertPolicy].
 */
enum class AlertLevel {
    /** Nothing close; object is far away or absent. */
    NONE,

    /** Object present, worth a glance. */
    LOW,

    /** Object getting close. */
    MEDIUM,

    /** Imminent — full-screen LOOK UP + sound. */
    HIGH
}
