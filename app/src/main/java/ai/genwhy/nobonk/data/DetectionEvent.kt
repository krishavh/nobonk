package ai.genwhy.nobonk.data

import org.json.JSONObject
import java.util.UUID

/**
 * A single recorded warning event captured during an active detection session.
 *
 * @param id            Unique identifier for deduplication
 * @param sessionId     Groups events that happened in the same app session
 * @param timestamp     Unix milliseconds when the event was recorded
 * @param latitude      GPS latitude; null if no fix was available at the time of detection
 * @param longitude     GPS longitude; null if no fix was available at the time of detection
 * @param className     Detected object class ("person", "car", etc.)
 * @param distance      Estimated distance in metres
 * @param alertLevel    Severity: "LOW", "MEDIUM", or "HIGH"
 * @param isApproaching Whether the object was closing distance
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
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("timestamp", timestamp)
        // Store null as JSON null (JSONObject.NULL) so fromJson can distinguish
        // "no GPS fix" from a genuine coordinate near 0.0/0.0.
        // Legacy records written as 0.0/0.0 are handled in fromJson.
        put("latitude",  if (latitude  != null) latitude  else JSONObject.NULL)
        put("longitude", if (longitude != null) longitude else JSONObject.NULL)
        put("className", className)
        put("distance", distance.toDouble())
        put("alertLevel", alertLevel)
        put("isApproaching", isApproaching)
    }

    companion object {
        /** Valid values for alertLevel — rejects any arbitrary string injected via a corrupt file. */
        private val VALID_ALERT_LEVELS = setOf("LOW", "MEDIUM", "HIGH")
        /** Known COCO classes emitted by ObjectDetector.classNameFor (keep in sync). */
        private val VALID_CLASS_NAMES  = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "dog", "cat", "object"
        )

        fun fromJson(json: JSONObject): DetectionEvent {
            val rawAlert = json.getString("alertLevel")
            val rawClass = json.getString("className")

            // Reject unrecognised values so a corrupt/tampered JSON file cannot
            // inject arbitrary strings into app state or analytics.
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
            val rawLat = if (json.isNull("latitude"))  null else json.getDouble("latitude")
            val rawLng = if (json.isNull("longitude")) null else json.getDouble("longitude")
            val lat = if (rawLat == 0.0 && rawLng == 0.0) null else rawLat
            val lng = if (rawLat == 0.0 && rawLng == 0.0) null else rawLng

            return DetectionEvent(
                id          = json.getString("id"),
                sessionId   = json.getString("sessionId"),
                timestamp   = json.getLong("timestamp"),
                latitude    = lat,
                longitude   = lng,
                className   = rawClass,
                distance    = dist,
                alertLevel  = rawAlert,
                isApproaching = json.getBoolean("isApproaching")
            )
        }
    }
}

/** Lightweight summary computed per session for the session list UI. */
data class SessionSummary(
    val sessionId: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val totalEvents: Int,
    val highAlerts: Int,
    val topThreat: String,              // most common className
    val startLatitude: Double?,         // null when no GPS fix at session start
    val startLongitude: Double?         // null when no GPS fix at session start
) {
    val durationMinutes: Long get() = (endTimestamp - startTimestamp) / 60_000
}
