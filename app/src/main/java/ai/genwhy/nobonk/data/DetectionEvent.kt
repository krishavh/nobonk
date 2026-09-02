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
 * @param distance      Estimated distance in metres; must be non-negative and finite
 * @param alertLevel    Severity: "LOW", "MEDIUM", or "HIGH"; see [Companion.VALID_ALERT_LEVELS]
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
    val alertLevel: String,
    val isApproaching: Boolean
) {
    /**
     * Serialises this event to a [JSONObject].
     *
     * GPS coordinates are stored as JSON null when no fix was available, so that
     * "no GPS fix" is distinguishable from a genuine coordinate near 0.0/0.0.
     * Legacy records written with the 0.0/0.0 sentinel are normalised back to
     * `null` coordinates in [Companion.fromJson], so [toJson] / [Companion.fromJson]
     * round-trip cleanly.
     *
     * @return a fresh [JSONObject] with one key per [DetectionEvent] property.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("timestamp", timestamp)
        put("latitude", latitude ?: JSONObject.NULL)
        put("longitude", longitude ?: JSONObject.NULL)
        put("className", className)
        // JSONObject has no float overload, so widen to double. Every finite Float
        // value is exactly representable as a Double, and getDouble() -> toFloat()
        // narrows back to the identical bits, so the round-trip is lossless.
        // (NaN/Infinite distances cannot occur for validated events; see fromJson.)
        put("distance", distance.toDouble())
        put("alertLevel", alertLevel)
        put("isApproaching", isApproaching)
    }

    companion object {
        /**
         * Valid values for [DetectionEvent.alertLevel].
         *
         * Enforced in [fromJson] so a corrupt or tampered file cannot inject
         * arbitrary strings into app state.
         */
        private val VALID_ALERT_LEVELS = setOf("LOW", "MEDIUM", "HIGH")

        /**
         * Known COCO classes emitted by ObjectDetector.classNameFor (keep in sync).
         * "object" is the catch-all bucket for detections outside the curated classes.
         */
        private val VALID_CLASS_NAMES = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "dog", "cat", "object"
        )

        /**
         * Legacy "no GPS fix" sentinel written by older app versions: an exact
         * 0.0/0.0 coordinate pair (a point in the Gulf of Guinea no user will visit).
         */
        private const val LEGACY_SENTINEL_COORD = 0.0

        /**
         * Reads an optional double-valued coordinate from this [JSONObject].
         *
         * Note: [JSONObject.isNull] returns `true` both for an explicit JSON null and
         * for an absent key, so both cases collapse to `null` here.
         *
         * @return the stored value, or `null` when the key is absent or JSON null.
         * @throws org.json.JSONException if the key is present but not a number.
         */
        private fun JSONObject.optCoordinate(name: String): Double? =
            if (isNull(name)) null else getDouble(name)

        /**
         * True when both coordinates are exactly the legacy 0.0/0.0 "no fix" sentinel.
         *
         * The sentinel is only cleared when BOTH coordinates are exactly 0.0, so a
         * genuine fix at (0.0, non-zero) or (non-zero, 0.0) is preserved. The equality
         * checks are null-safe (`null == 0.0` is false, as is `NaN == 0.0`), so missing
         * or NaN coordinates never trigger the sentinel path.
         */
        private fun isLegacySentinel(lat: Double?, lng: Double?): Boolean =
            lat == LEGACY_SENTINEL_COORD && lng == LEGACY_SENTINEL_COORD

        /**
         * Deserialises a [DetectionEvent] previously written by [DetectionEvent.toJson].
         *
         * Unrecognised `alertLevel`/`className` values and non-finite or negative distances
         * are rejected so a corrupt or tampered JSON file cannot inject arbitrary strings
         * or nonsense metrics into app state. A legacy 0.0/0.0 coordinate pair is normalised
         * to `null` (no GPS fix). The `id` and `sessionId` strings are passed through as-is
         * (they are opaque identifiers generated by this app, not user-facing values).
         *
         * @throws IllegalArgumentException if `alertLevel` or `className` is not a recognised
         *   value, or if `distance` is negative, NaN, or infinite.
         * @throws org.json.JSONException if any required key is missing or has the wrong type.
         */
        fun fromJson(json: JSONObject): DetectionEvent {
            // Validate the enum-like strings before touching anything numeric, so the
            // cheapest and most descriptive failure happens first on corrupt input.
            val rawAlert = json.getString("alertLevel")
            val rawClass = json.getString("className")

            require(rawAlert in VALID_ALERT_LEVELS) {
                "Invalid alertLevel '$rawAlert' — expected one of $VALID_ALERT_LEVELS"
            }
            require(rawClass in VALID_CLASS_NAMES) {
                "Invalid className '$rawClass' — expected one of $VALID_CLASS_NAMES"
            }

            val dist = json.getDouble("distance").toFloat()
            // Rejects negatives, NaN (NaN >= 0f is false), and +/-Infinity.
            require(dist.isFinite() && dist >= 0f) {
                "distance must be finite and non-negative, got $dist"
            }

            // Treat JSON null OR the legacy 0.0/0.0 sentinel as "no GPS fix".
            val rawLat = json.optCoordinate("latitude")
            val rawLng = json.optCoordinate("longitude")
            val noFix = isLegacySentinel(rawLat, rawLng)

            return DetectionEvent(
                id = json.getString("id"),
                sessionId = json.getString("sessionId"),
                timestamp = json.getLong("timestamp"),
                latitude = if (noFix) null else rawLat,
                longitude = if (noFix) null else rawLng,
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
 * Instances are derived from already-validated [DetectionEvent]s; no I/O or
 * parsing happens here, so all fields are trusted as supplied by the caller.
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
     * Session length in whole minutes, truncated toward zero.
     *
     * A negative [endTimestamp] − [startTimestamp] difference (e.g. from a clock
     * adjustment mid-session) is clamped to zero so the UI never shows a negative
     * duration. Integer division by [MILLIS_PER_MINUTE] truncates any sub-minute remainder.
     *
     * Note: the subtraction could in principle overflow for pathological inputs
     * (near [Long.MIN_VALUE]/[Long.MAX_VALUE] timestamps), but real wall-clock
     * millisecond values are many orders of magnitude away from that range.
     */
    val durationMinutes: Long
        get() = (endTimestamp - startTimestamp).coerceAtLeast(0L) / MILLIS_PER_MINUTE

    companion object {
        /** Milliseconds per minute; a non-zero compile-time constant, so no division-by-zero risk. */
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
