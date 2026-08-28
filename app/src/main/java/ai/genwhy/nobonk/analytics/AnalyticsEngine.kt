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
     * (or all events were filtered out as timestamp-less).
     */
    fun peakDangerHour(events: List<DetectionEvent>): Int {
        if (events.isEmpty()) return -1
        val counts = warningsByHour(events)
        // maxByOrNull returns the first maximum encountered, i.e. the earliest hour on ties.
        return counts.indices.maxByOrNull { counts[it] } ?: -1
    }

    /**
     * Formats an hour of day (0–23) as a human-readable 12-hour string,
     * e.g. 0 → "12 AM", 13 → "1 PM".
     * Returns "N/A" for negative sentinels (such as [peakDangerHour]'s no-data result).
     * Hours above 23 are clamped into the valid range rather than producing
     * nonsense output.
     */
    fun formatHour(hour: Int): String {
        if (hour < 0) return "N/A"
        val h24 = hour % 24
        val suffix = if (h24 < 12) "AM" else "PM"
        val h = when {
            h24 == 0  -> 12   // midnight renders as "12 AM"
            h24 <= 12 -> h24
            else      -> h24 - 12
        }
        return "$h $suffix"
    }

    // ── Threat breakdown ──────────────────────────────────────────────────────

    /**
     * Returns detected class name → event count, sorted descending by count.
     * Ties keep an arbitrary but stable order. Empty input yields an empty list.
     */
    fun threatBreakdown(events: List<DetectionEvent>): List<Pair<String, Int>> =
        events.groupingBy { it.className }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    // ── Alert severity ────────────────────────────────────────────────────────

    /**
     * Aggregate counts by alert level.
     *
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
    fun overallStats(events: List<DetectionEvent>): OverallStats {
        val totalSessions = events.map { it.sessionId }.distinct().size
        val closeCalls = events.count { it.alertLevel == "HIGH" && it.isApproaching }
        return OverallStats(
            totalSessions = totalSessions,
            totalEvents = events.size,
            closeCalls = closeCalls,
            peakHour = peakDangerHour(events)
        )
    }

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
        if (maxClusters <= 0) return emptyList()

        data class GpsEvent(val lat: Double, val lng: Double)

        val validPoints: List<GpsEvent> = events.mapNotNull { ev ->
            val lat = ev.latitude ?: return@mapNotNull null
            val lng = ev.longitude ?: return@mapNotNull null
            // Reject non-finite coordinates that would corrupt centroid math.
            if (!lat.isFinite() || !lng.isFinite()) return@mapNotNull null
            GpsEvent(lat, lng)
        }
        if (validPoints.isEmpty()) return emptyList()

        val clusterRadius = 0.0005
        val clusters = mutableListOf<Triple<Double, Double, Int>>() // (lat, lng, count)

        for (ev in validPoints) {
            val nearest = clusters.minByOrNull { (lat, lng, _) ->
                // Manhattan distance in degrees: cheap proxy for metres at city scale.
                abs(lat - ev.lat) + abs(lng - ev.lng)
            }
            if (nearest != null) {
                val (lat, lng, cnt) = nearest
                if (abs(lat - ev.lat) < clusterRadius && abs(lng - ev.lng) < clusterRadius) {
                    val idx = clusters.indexOf(nearest)
                    // Running-average centroid update; cnt ≥ 1 so no div-by-zero.
                    val newCnt = cnt + 1
                    clusters[idx] = Triple(
                        (lat * cnt + ev.lat) / newCnt,
                        (lng * cnt + ev.lng) / newCnt,
                        newCnt
                    )
                    continue
                }
            }
            clusters.add(Triple(ev.lat, ev.lng, 1))
        }

        return clusters
            .sortedByDescending { it.third }
            .take(maxClusters)
            .map { (lat, lng, cnt) -> Hotspot(lat, lng, cnt) }
    }

    // ── Distance insights ─────────────────────────────────────────────────────

    /**
     * Returns the smallest recorded distance in metres across all events
     * (the "closest call"), or null if there are no events.
     */
    fun closestCallDistance(events: List<DetectionEvent>): Float? =
        events.minOfOrNull { it.distance }

    // ── Time formatting helpers ───────────────────────────────────────────────

    /**
     * Formats a Unix epoch millisecond timestamp using [pattern] and the
     * device's default locale. Invalid (non-positive) timestamps yield "N/A".
     */
    fun formatTimestamp(timestamp: Long, pattern: String = "MMM d, h:mm a"): String {
        if (timestamp <= 0L) return "N/A"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(timestamp)
    }

    /**
     * Formats a duration in minutes, e.g. 0 → "< 1 min", 1 → "1 min",
     * 59 → "59 mins", 125 → "2h 5m". Negative inputs are treated as zero.
     */
    fun formatDuration(minutes: Long): String {
        val m = if (minutes < 0) 0L else minutes
        return when {
            m < 1   -> "< 1 min"
            m == 1L -> "1 min"
            m < 60  -> "$m mins"
            else    -> "${m / 60}h ${m % 60}m"
        }
    }
}
