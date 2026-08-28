package ai.genwhy.nobonk.data

import org.json.JSONObject
import java.util.UUID

/**
 * A single recorded warning event captured during an active detection session.
 *
 * Instances are produced on-device by the detection pipeline and persisted locally
 * via [toJson]; nothing is ever transmitted off the device.
 *
 * @param id            Unique identifier (UUIDv4 by default) used for deduplication
 * @param sessionId     Groups events that happened in the same app session
 * @param timestamp     Unix milliseconds when the event was recorded
 * @param latitude      GPS latitude; `null` if no fix was available at the time of detection
 * @param longitude     GPS longitude; `null` if no fix was available at the time of detection
 * @param className     Detected object class ("person", "car", etc.); see [Companion.VALID_CLASS_NAMES]
 * @param distance      Estimated distance in metres; must be non-negative
 * @param alertLevel    Severity: "LOW", "MEDIUM", or "HIGH"
 * @param isApproaching Whether the object was closing distance at the time of the event
 */
data class DetectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val className: String,
    val distance: Float,
    val alertLevel: String,      // "LOW" | "MEDIUM" | "HIGH"
    val isApproaching: Boolean
) {
    /**
     * Serialises this event to a [JSONObject].
     *
     * GPS coordinates are stored as JSON null when no fix was available, so that
     * "no GPS fix" is distinguishable from a genuine coordinate near 0.0/0.0.
     * Legacy records written with the 0.0/0.0 sentinel are normalised back to
     * `null` coordinates in [Companion.fromJson].
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("timestamp", timestamp)
        put("latitude", latitude ?: JSONObject.NULL)
        put("longitude", longitude ?: JSONObject.NULL)
        put("className", className)
        put("distance", distance.toDouble())
        put("alertLevel", alertLevel)
        put("isApproaching", isApproaching)
    }

    companion object {
        /** Valid values for [DetectionEvent.alertLevel] — rejects arbitrary strings injected via a corrupt file. */
        private val VALID_ALERT_LEVELS = setOf("LOW", "MEDIUM", "HIGH")

        /** Known COCO classes emitted by ObjectDetector.classNameFor (keep in sync). */
        private val VALID_CLASS_NAMES = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "dog", "cat", "object"
        )

        /**
         * Deserialises a [DetectionEvent] previously written by [toJson].
         *
         * Unrecognised `alertLevel`/`className` values and negative distances are rejected
         * so a corrupt or tampered JSON file cannot inject arbitrary strings or nonsense
         * metrics into app state.
         *
         * @throws IllegalArgumentException if `alertLevel` or `className` is not a recognised
         *   value, or if `distance` is negative.
         * @throws org.json.JSONException if any required key is missing or has the wrong type.
         */
        fun fromJson(json: JSONObject): DetectionEvent {
            val rawAlert = json.getString("alertLevel")
            val rawClass = json.getString("className")

            require(rawAlert in VALID_ALERT_LEVELS) {
                "Invalid alertLevel '$rawAlert' — expected one of $VALID_ALERT_LEVELS"
            }
            require(rawClass in VALID_CLASS_NAMES) {
                "Invalid className '$rawClass' — expected one of $VALID_CLASS_NAMES"
            }

            val dist = json.getDouble("distance").toFloat()
            require(dist >= 0f) { "distance must be non-negative, got $dist" }

            // Read GPS: treat JSON null OR the legacy 0.0/0.0 sentinel as "no fix".
            // The 0.0/0.0 sentinel was used in records written before this migration;
            // it maps to a point in the Gulf of Guinea which no user will ever visit.
            val rawLat = if (json.isNull("latitude")) null else json.getDouble("latitude")
            val rawLng = if (json.isNull("longitude")) null else json.getDouble("longitude")
            val hasLegacySentinel = rawLat == 0.0 && rawLng == 0.0
            val lat = if (hasLegacySentinel) null else rawLat
            val lng = if (hasLegacySentinel) null else rawLng

            return DetectionEvent(
                id = json.getString("id"),
                sessionId = json.getString("sessionId"),
                timestamp = json.getLong("timestamp"),
                latitude = lat,
                longitude = lng,
                className = rawClass,
                distance = dist,
                alertLevel = rawAlert,
                isApproaching = json.getBoolean("isApproaching")
            )
        }
    }
}

/**
 * Lightweight summary computed per session for the session list UI.
 *
 * @property sessionId       Identifier of the summarised session
 * @property startTimestamp  Unix milliseconds when the session started
 * @property endTimestamp    Unix milliseconds when the session ended
 * @property totalEvents     Total number of [DetectionEvent]s recorded in the session
 * @property highAlerts      Number of events whose alertLevel was "HIGH"
 * @property topThreat       Most frequently detected className in the session
 * @property startLatitude   GPS latitude at session start; `null` when no fix was available
 * @property startLongitude  GPS longitude at session start; `null` when no fix was available
 */
data class SessionSummary(
    val sessionId: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val totalEvents: Int,
    val highAlerts: Int,
    val topThreat: String,
    val startLatitude: Double?,
    val startLongitude: Double?
) {
    /**
     * Session length in whole minutes.
     *
     * A negative [SessionSummary.endTimestamp] minus [SessionSummary.startTimestamp]
     * (e.g. from a clock adjustment mid-session) is clamped to zero so the UI never
     * shows a negative duration.
     */
    val durationMinutes: Long
        get() = (endTimestamp - startTimestamp).coerceAtLeast(0L) / 60_000L
}
