package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel

/**
 * One shared gate for sound, vibration and voice. Visual detections are never muted.
 * Stable hazards produce spaced reminders, not one vibration per camera frame.
 * Escalation remains prompt; short detector dropouts and track-ID churn cannot rearm
 * a burst. Times use the monotonic camera-processing clock.
 */
class AlertCadence {
    private var lastEmission: Long? = null
    private var lastLevel = AlertLevel.NONE
    private var lastCall: Long? = null
    private var lastObservation: Long? = null
    private var clearSince: Long? = null
    private var identityContinuous = true
    private var lastTrack: String? = null
    private val recentHighTracks = linkedMapOf<String, Long>()

    fun shouldEmit(level: AlertLevel, track: String?, nowMs: Long, canEmit: Boolean): Boolean {
        if (lastCall?.let { nowMs < it } == true) reset()
        lastCall = nowMs
        if (!canEmit) {
            // Suppression is not evidence of a clear scene and consumes no emission budget.
            clearSince = null
            return false
        }
        if (lastObservation?.let { nowMs - it >= CLEAR_MS } == true) {
            clearSince = null
            // A tracker can assign a new ID after missing frames. Until another cue is
            // emitted, that alone cannot claim the faster new-person repeat interval.
            identityContinuous = false
        }
        lastObservation = nowMs
        if (level == AlertLevel.NONE) {
            val start = clearSince ?: nowMs.also { clearSince = it }
            if (nowMs - start >= CLEAR_MS) reset()
            return false
        }
        clearSince = null
        val emitted = lastEmission
        val elapsed = if (emitted == null) Long.MAX_VALUE else (nowMs - emitted).coerceAtLeast(0L)
        val escalation = level.ordinal > lastLevel.ordinal
        // Switching the top box between people already in view is not a new hazard.
        recentHighTracks.entries.removeAll { nowMs - it.value >= HIGH_REPEAT_MS }
        val newTrack = identityContinuous && track != null && lastTrack != null && track != lastTrack && track !in recentHighTracks
        val interval = when {
            escalation -> ESCALATION_MS
            newTrack && level == AlertLevel.HIGH -> NEW_HIGH_MS
            level == AlertLevel.HIGH -> HIGH_REPEAT_MS
            level == AlertLevel.MEDIUM -> 10_000L
            else -> 15_000L
        }
        if (emitted != null && elapsed < interval) return false
        lastEmission = nowMs
        lastLevel = level
        lastTrack = track
        identityContinuous = true
        if (level == AlertLevel.HIGH && track != null) {
            recentHighTracks.remove(track)
            recentHighTracks[track] = nowMs
            if (recentHighTracks.size > 64) recentHighTracks.remove(recentHighTracks.keys.first())
        }
        return true
    }

    fun reset() {
        lastEmission = null; lastCall = null; lastObservation = null; clearSince = null
        lastLevel = AlertLevel.NONE; lastTrack = null; identityContinuous = true
        recentHighTracks.clear()
    }

    companion object {
        const val HIGH_REPEAT_MS = 6_000L
        const val CLEAR_MS = 3_000L
        const val ESCALATION_MS = 400L
        const val NEW_HIGH_MS = 1_500L
    }
}
