package ai.genwhy.nobonk.analytics

import ai.genwhy.nobonk.data.DetectionEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Pure, stateless analytics engine.
 *
 * All functions take a list of [DetectionEvent] and return insight structures.
 * No Android context is required, which makes the engine trivially unit-testable.
 * Every function tolerates empty input lists without throwing.
 */
object AnalyticsEngine {

    // ── Hourly activity ───────────────────────────────────────────────────────

    /**
     * Returns a 24-element array where index = hour of day (0–23, local time)
     * and value = number of events in that hour.
     *
     * Events with non-positive timestamps are ignored, since they carry no
     * meaningful wall-clock time.
     */
    fun warningsByHour(events: List<DetectionEvent>): IntArray {
        val counts = IntArray(24)
        if (events.isEmpty()) return counts
        val cal = Calendar.getInstance()
        for (ev in events) {
            if (ev.timestamp <= 0L) continue
            cal.timeInMillis = ev.timestamp
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            // Defensive bounds check; HOUR_OF_DAY is always 0–23 in practice.
            if (hour in counts.indices) counts[hour]++
        }
        return counts
    }

    /**
     * Returns the hour of day (0–23) with the highest event count.
     * Ties resolve to the earliest hour. Returns -1 if there are no events
     * (or all events were filtered out as timestamp-less, leaving every hour
     * at zero).
     */
    fun peakDangerHour(events: List<DetectionEvent>): Int {
        val counts = warningsByHour(events)
        // If every hour is zero, there were no events with usable timestamps —
        // no peak exists.
        if (counts.all { it == 0 }) return -1
        // maxByOrNull keeps the first maximum encountered, i.e. the earliest hour on ties.
        return counts.withIndex().maxByOrNull { it.value }?.index ?: -1
    }

    /**
     * Formats an hour of day (0–23) as a human-readable 12-hour string,
     * e.g. 0 → "12 AM", 13 → "1 PM".
     *
     * Returns "N/A" for negative sentinels (such as [peakDangerHour]'s no-data
     * result). Hours above 23 wrap into the valid range via modulo rather than
     * producing nonsense output.
     */
    fun formatHour(hour: Int): String {
        if (hour < 0) return "N/A"
        val h24 = hour % 24
        val suffix = if (h24 < 12) "AM" else "PM"
        // 0 renders as "12 AM"; 12 stays as "12 PM"; 13–23 map to 1–11 PM.
        val h = when (h24) {
            0 -> 12
            in 1..12 -> h24
            else -> h24 - 12
        }
        return "$h $suffix"
    }

    // ── Threat breakdown ──────────────────────────────────────────────────────

    /**
     * Returns detected class name → event count, sorted descending by count.
     * Ties keep a stable order (the order in which class names were first seen).
     * Empty input yields an empty list.
     */
    fun threatBreakdown(events: List<DetectionEvent>): List<Pair<String, Int>> =
        events.groupingBy { it.className }
            .eachCount()
            .entries
            // sortedByDescending is stable, preserving first-seen order on ties.
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    // ── Alert severity ────────────────────────────────────────────────────────

    /**
     * Aggregate counts by alert level.
     *
     * @property total Total number of events, including those with unknown levels.
     * @property closeCalls HIGH alerts where the object was approaching the user.
     */
    data class AlertStats(
        val total: Int,
        val low: Int,
        val medium: Int,
        val high: Int,
        val closeCalls: Int
    )

    /**
     * Counts events per alert level. Unknown alert-level strings are counted in
     * [AlertStats.total] only.
     */
    fun alertStats(events: List<DetectionEvent>): AlertStats {
        var low = 0
        var medium = 0
        var high = 0
        var closeCalls = 0
        for (ev in events) {
            when (ev.alertLevel) {
                "LOW"    -> low++
                "MEDIUM" -> medium++
                "HIGH"   -> {
                    high++
                    if (ev.isApproaching) closeCalls++
                }
                // Unknown levels contribute to total only.
                else     -> Unit
            }
        }
        return AlertStats(events.size, low, medium, high, closeCalls)
    }

    // ── Session stats ─────────────────────────────────────────────────────────

    /** Cross-session summary derived from a flat event list. */
    data class OverallStats(
        val totalSessions: Int,
        val totalEvents: Int,
        val closeCalls: Int,
        val peakHour: Int
    )

    /**
     * Summarises the given events: distinct session count, total event count,
     * close-call count, and the peak danger hour (-1 when there are no events).
     */
    fun overallStats(events: List<DetectionEvent>): OverallStats = OverallStats(
        totalSessions = events.asSequence().map { it.sessionId }.distinct().count(),
        totalEvents = events.size,
        closeCalls = events.count { it.alertLevel == "HIGH" && it.isApproaching },
        peakHour = peakDangerHour(events)
    )

    // ── GPS hotspots ──────────────────────────────────────────────────────────

    /** A geographic cluster of events: centroid coordinates plus event count. */
    data class Hotspot(val latitude: Double, val longitude: Double, val count: Int)

    /**
     * Greedily clusters events by GPS position and returns hotspots sorted by
     * event count descending, up to [maxClusters].
     *
     * Events without a GPS fix (null latitude/longitude) are excluded, as are
     * non-finite coordinates. A non-positive [maxClusters] yields an empty list.
     *
     * Clustering uses a Manhattan-style degree distance; ~0.0005° ≈ 55 m at the
     * equator, so points within that box of a cluster's running centroid join it.
     */
    fun hotspots(events: List<DetectionEvent>, maxClusters: Int = 20): List<Hotspot> {
        if (maxClusters <= 0 || events.isEmpty()) return emptyList()

        data class GpsEvent(val lat: Double, val lng: Double)

        val validPoints: List<GpsEvent> = events.mapNotNull { ev ->
            val lat = ev.latitude ?: return@mapNotNull null
            val lng = ev.longitude ?: return@mapNotNull null
            // Reject non-finite coordinates (NaN/±Inf) that would corrupt centroid math.
            if (!lat.isFinite() || !lng.isFinite()) return@mapNotNull null
            GpsEvent(lat, lng)
        }
        if (validPoints.isEmpty()) return emptyList()

        // Mutable running-centroid cluster; count is always ≥ 1, so the
        // centroid update below never divides by zero.
        class Cluster(var lat: Double, var lng: Double, var count: Int)

        val clusters = mutableListOf<Cluster>()

        for (ev in validPoints) {
            // Manhattan distance in degrees is a cheap proxy for metres at city
            // scale. minByOrNull keeps the first minimum on ties, matching
            // insertion order.
            val nearest = clusters.minByOrNull { c -> abs(c.lat - ev.lat) + abs(c.lng - ev.lng) }
            if (nearest != null) {
                val dLat = abs(nearest.lat - ev.lat)
                val dLng = abs(nearest.lng - ev.lng)
                if (dLat < CLUSTER_RADIUS_DEGREES && dLng < CLUSTER_RADIUS_DEGREES) {
                    // Running-average centroid update; count is always ≥ 1 here,
                    // so newCount is ≥ 2 and the division is safe.
                    val newCount = nearest.count + 1
                    nearest.lat = (nearest.lat * nearest.count + ev.lat) / newCount
                    nearest.lng = (nearest.lng * nearest.count + ev.lng) / newCount
                    nearest.count = newCount
                    continue
                }
            }
            clusters.add(Cluster(ev.lat, ev.lng, 1))
        }

        return clusters
            // sortedByDescending is stable, so equal counts keep insertion order.
            .sortedByDescending { it.count }
            .take(maxClusters)
            .map { Hotspot(it.lat, it.lng, it.count) }
    }

    // ── Distance insights ─────────────────────────────────────────────────────

    /**
     * Returns the smallest recorded distance in metres across all events
     * (the "closest call"), or null if there are no events.
     *
     * Non-finite distances (NaN/±Inf) are ignored, since they indicate a bad
     * sensor reading rather than a real measurement.
     */
    fun closestCallDistance(events: List<DetectionEvent>): Float? =
        events.asSequence()
            .map { it.distance }
            .filter { it.isFinite() }
            .minOfOrNull { it }

    // ── Time formatting helpers ───────────────────────────────────────────────

    private const val DEFAULT_TIMESTAMP_PATTERN = "MMM d, h:mm a"

    /** Cluster join threshold in degrees of latitude/longitude (~55 m at the equator). */
    private const val CLUSTER_RADIUS_DEGREES = 0.0005

    /**
     * Formats a Unix epoch millisecond timestamp using [pattern] and the
     * device's default locale. Invalid (non-positive) timestamps yield "N/A".
     *
     * A new [SimpleDateFormat] is created per call, so this is safe to use
     * from any thread. A malformed pattern also yields "N/A" rather than
     * propagating an exception.
     */
    fun formatTimestamp(timestamp: Long, pattern: String = DEFAULT_TIMESTAMP_PATTERN): String {
        if (timestamp <= 0L) return "N/A"
        // Fall back to the default pattern if the caller passes a blank one,
        // which would otherwise produce empty output.
        val effectivePattern = pattern.ifBlank { DEFAULT_TIMESTAMP_PATTERN }
        return try {
            SimpleDateFormat(effectivePattern, Locale.getDefault()).format(timestamp)
        } catch (_: IllegalArgumentException) {
            // Malformed pattern (e.g. unbalanced quotes) — degrade gracefully.
            "N/A"
        } catch (_: NullPointerException) {
            // Defensive: some JDK builds throw NPE for null-adjacent pattern content.
            "N/A"
        }
    }

    /**
     * Formats a duration in minutes, e.g. 0 → "< 1 min", 1 → "1 min",
     * 59 → "59 mins", 125 → "2h 5m". Negative inputs are treated as zero.
     */
    fun formatDuration(minutes: Long): String {
        val m = minutes.coerceAtLeast(0L)
        return when {
            m < 1L  -> "< 1 min"
            m == 1L -> "1 min"
            m < 60L -> "$m mins"
            else    -> "${m / 60}h ${m % 60}m"
        }
    }
}
