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
    private var lastSeen: Long? = null
    private var lastTrack: String? = null
    private val recentHighTracks = linkedMapOf<String, Long>()

    fun shouldEmit(level: AlertLevel, track: String?, nowMs: Long, canEmit: Boolean): Boolean {
        if (!canEmit) return false // no rate-limit budget consumed while stopped/off-angle
        val seen = lastSeen
        if (seen != null && (nowMs < seen || nowMs - seen >= CLEAR_MS)) reset()
        if (level == AlertLevel.NONE) return false
        lastSeen = nowMs
        val emitted = lastEmission
        val elapsed = if (emitted == null) Long.MAX_VALUE else (nowMs - emitted).coerceAtLeast(0L)
        val escalation = level.ordinal > lastLevel.ordinal
        // Switching the top box between people already in view is not a new hazard.
        recentHighTracks.entries.removeAll { nowMs - it.value >= HIGH_REPEAT_MS }
        val newTrack = track != null && lastTrack != null && track != lastTrack && track !in recentHighTracks
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
        if (level == AlertLevel.HIGH && track != null) {
            recentHighTracks.remove(track)
            recentHighTracks[track] = nowMs
            if (recentHighTracks.size > 64) recentHighTracks.remove(recentHighTracks.keys.first())
        }
        return true
    }

    fun reset() {
        lastEmission = null; lastSeen = null; lastLevel = AlertLevel.NONE; lastTrack = null
        recentHighTracks.clear()
    }

    companion object {
        const val HIGH_REPEAT_MS = 6_000L
        const val CLEAR_MS = 3_000L
        const val ESCALATION_MS = 400L
        const val NEW_HIGH_MS = 1_500L
    }
}
