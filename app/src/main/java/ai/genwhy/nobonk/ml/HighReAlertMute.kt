package ai.genwhy.nobonk.ml

/**
 * Per-track mute for the loud HIGH cue: after a HIGH fires on a track, the same track
 * does not re-blast for [muteMs] (the red visual stays; only the sound is held).
 *
 * The mute window is consumed **only when a cue is actually emitted**. A HIGH observed
 * while the camera is off-angle (cue suppressed) must not start the window — otherwise
 * the user corrects the angle and the first audible alert is delayed by up to [muteMs].
 */
class HighReAlertMute(private val muteMs: Long = 2_000L, private val pruneAfterMs: Long = 10_000L) {
    private val lastFiredAt = HashMap<String, Long>()

    /**
     * @param trackId   the track behind this HIGH (null = untracked; always emits)
     * @param nowMs     current time
     * @param canEmit   false when the cue is being suppressed for another reason (bad angle)
     * @return true if the audible cue should fire now
     */
    fun shouldEmit(trackId: String?, nowMs: Long, canEmit: Boolean): Boolean {
        if (lastFiredAt.size > 32) lastFiredAt.entries.removeAll { nowMs - it.value > pruneAfterMs }
        if (!canEmit) return false
        if (trackId == null) return true
        val last = lastFiredAt[trackId]
        if (last != null && nowMs - last < muteMs) return false
        lastFiredAt[trackId] = nowMs
        return true
    }

    fun reset() = lastFiredAt.clear()
}
