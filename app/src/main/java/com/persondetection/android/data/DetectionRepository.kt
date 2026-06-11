package com.persondetection.android.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe, file-backed repository for [DetectionEvent] records.
 *
 * Storage: a single JSON array written to `<filesDir>/detection_events.json`.
 * The lock ensures safe concurrent reads from the analytics engine while the
 * frame-processing coroutine appends new events.
 *
 * All public methods are safe to call from any thread.
 */
class DetectionRepository(context: Context) {

    private val file: File = File(context.filesDir, "detection_events.json")
    private val lock = ReentrantReadWriteLock()

    companion object {
        private const val TAG = "DetectionRepository"
        // Keep at most this many events to prevent unbounded growth (~30 days of use)
        private const val MAX_EVENTS = 5_000
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /** Appends a single [DetectionEvent] to the persistent store. */
    fun addEvent(event: DetectionEvent) {
        lock.write {
            try {
                val array = readArray()
                array.put(event.toJson())
                // Trim oldest events when the store exceeds the limit
                val trimmed = if (array.length() > MAX_EVENTS) trimArray(array, MAX_EVENTS) else array
                file.writeText(trimmed.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist event: ${e.message}")
            }
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Returns ALL stored events in chronological order (oldest first). */
    fun getAllEvents(): List<DetectionEvent> = lock.read {
        try {
            parseArray(readArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load events: ${e.message}")
            emptyList()
        }
    }

    /** Returns events for the given [sessionId]. */
    fun getSessionEvents(sessionId: String): List<DetectionEvent> =
        getAllEvents().filter { it.sessionId == sessionId }

    /**
     * Returns the [n] most recent [SessionSummary] objects.
     * A session boundary is determined by the sessionId field.
     */
    fun getRecentSessions(n: Int = 10): List<SessionSummary> {
        val events = getAllEvents()
        if (events.isEmpty()) return emptyList()

        // Group by sessionId preserving insertion order
        val grouped = LinkedHashMap<String, MutableList<DetectionEvent>>()
        for (ev in events) grouped.getOrPut(ev.sessionId) { mutableListOf() }.add(ev)

        return grouped.values
            .map { sessionEvents -> buildSummary(sessionEvents) }
            .sortedByDescending { it.startTimestamp }
            .take(n)
    }

    /** Clears all stored history. */
    fun clearAll() = lock.write {
        file.writeText("[]")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readArray(): JSONArray {
        if (!file.exists()) return JSONArray()
        val text = file.readText().trim()
        return if (text.isEmpty()) JSONArray() else JSONArray(text)
    }

    private fun parseArray(array: JSONArray): List<DetectionEvent> {
        val result = mutableListOf<DetectionEvent>()
        for (i in 0 until array.length()) {
            try {
                result.add(DetectionEvent.fromJson(array.getJSONObject(i)))
            } catch (e: Exception) {
                // Skip malformed entries silently
            }
        }
        return result
    }

    /** Keeps the last [maxSize] items from [array]. */
    private fun trimArray(array: JSONArray, maxSize: Int): JSONArray {
        val start = array.length() - maxSize
        val trimmed = JSONArray()
        for (i in start until array.length()) trimmed.put(array.getJSONObject(i))
        return trimmed
    }

    private fun buildSummary(events: List<DetectionEvent>): SessionSummary {
        val sorted = events.sortedBy { it.timestamp }
        val topThreat = events.groupingBy { it.className }.eachCount()
            .maxByOrNull { it.value }?.key ?: "person"
        val first = sorted.first()
        return SessionSummary(
            sessionId = first.sessionId,
            startTimestamp = first.timestamp,
            endTimestamp = sorted.last().timestamp,
            totalEvents = events.size,
            highAlerts = events.count { it.alertLevel == "HIGH" },
            topThreat = topThreat,
            startLatitude = first.latitude,
            startLongitude = first.longitude
        )
    }
}
