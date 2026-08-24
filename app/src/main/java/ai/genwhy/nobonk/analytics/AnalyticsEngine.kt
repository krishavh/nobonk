package ai.genwhy.nobonk.analytics

import ai.genwhy.nobonk.data.DetectionEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Pure, stateless analytics engine.
 * All functions take a list of [DetectionEvent] and return insight structures.
 * No Android context required — easy to unit test.
 */
object AnalyticsEngine {

    // ── Hourly activity ───────────────────────────────────────────────────────

    /**
     * Returns a 24-element array where index = hour of day (0–23)
     * and value = number of warning events in that hour.
     */
    fun warningsByHour(events: List<DetectionEvent>): IntArray {
        val counts = IntArray(24)
        val cal = Calendar.getInstance()
        for (ev in events) {
            cal.timeInMillis = ev.timestamp
            counts[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        return counts
    }

    /**
     * Returns the hour of day (0–23) with the highest event count,
     * or -1 if there are no events.
     */
    fun peakDangerHour(events: List<DetectionEvent>): Int {
        if (events.isEmpty()) return -1
        val counts = warningsByHour(events)
        return counts.indices.maxByOrNull { counts[it] } ?: -1
    }

    fun formatHour(hour: Int): String {
        if (hour < 0) return "N/A"
        val suffix = if (hour < 12) "AM" else "PM"
        val h = when {
            hour == 0   -> 12
            hour <= 12  -> hour
            else        -> hour - 12
        }
        return "$h $suffix"
    }

    // ── Threat breakdown ──────────────────────────────────────────────────────

    /** Returns detected class → count, sorted descending by count. */
    fun threatBreakdown(events: List<DetectionEvent>): List<Pair<String, Int>> =
        events.groupingBy { it.className }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    // ── Alert severity ────────────────────────────────────────────────────────

    data class AlertStats(
        val total: Int,
        val low: Int,
        val medium: Int,
        val high: Int,
        val closeCalls: Int       // HIGH alerts where object was approaching
    )

    fun alertStats(events: List<DetectionEvent>): AlertStats {
        val low    = events.count { it.alertLevel == "LOW" }
        val medium = events.count { it.alertLevel == "MEDIUM" }
        val high   = events.count { it.alertLevel == "HIGH" }
        val closeCalls = events.count { it.alertLevel == "HIGH" && it.isApproaching }
        return AlertStats(events.size, low, medium, high, closeCalls)
    }

    // ── Session stats ─────────────────────────────────────────────────────────

    data class OverallStats(
        val totalSessions: Int,
        val totalEvents: Int,
        val closeCalls: Int,
        val peakHour: Int
    )

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

    /**
     * Clusters GPS points and returns hotspots as (lat, lng, count) triples,
     * sorted by count descending. Clusters events within ~50 m of each other.
     *
     * Returns up to [maxClusters] hotspots. Points with 0,0 coordinates
     * (no GPS fix) are excluded.
     */
    data class Hotspot(val latitude: Double, val longitude: Double, val count: Int)

    fun hotspots(events: List<DetectionEvent>, maxClusters: Int = 20): List<Hotspot> {
        // Only include events where a real GPS fix was available.
        // latitude/longitude are null when no fix was obtained — do not substitute 0.0/0.0,
        // which is a valid coordinate in the Gulf of Guinea and would pollute the map.
        data class GpsEvent(val lat: Double, val lng: Double)
        val validPoints: List<GpsEvent> = events.mapNotNull { ev ->
            val lat = ev.latitude ?: return@mapNotNull null
            val lng = ev.longitude ?: return@mapNotNull null
            GpsEvent(lat, lng)
        }
        if (validPoints.isEmpty()) return emptyList()

        // Simple greedy clustering: ~0.0005° ≈ 55 m
        val clusterRadius = 0.0005
        val clusters = mutableListOf<Triple<Double, Double, Int>>() // lat, lng, count

        for (ev in validPoints) {
            val nearest = clusters.minByOrNull { (lat, lng, _) ->
                val dLat = abs(lat - ev.lat)
                val dLng = abs(lng - ev.lng)
                dLat + dLng
            }
            if (nearest != null) {
                val (lat, lng, cnt) = nearest
                if (abs(lat - ev.lat) < clusterRadius && abs(lng - ev.lng) < clusterRadius) {
                    val idx = clusters.indexOf(nearest)
                    // Update centroid with running average
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

    /** Returns a rounded "closest call" distance in metres, or null if no events. */
    fun closestCallDistance(events: List<DetectionEvent>): Float? =
        events.minOfOrNull { it.distance }

    // ── Time formatting helpers ───────────────────────────────────────────────

    fun formatTimestamp(timestamp: Long, pattern: String = "MMM d, h:mm a"): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(timestamp)

    fun formatDuration(minutes: Long): String = when {
        minutes < 1    -> "< 1 min"
        minutes == 1L  -> "1 min"
        minutes < 60   -> "$minutes mins"
        else           -> "${minutes / 60}h ${minutes % 60}m"
    }
}
