package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel

/**
 * Adaptive analysis cadence: how long to wait between analysed frames.
 *
 * The camera pipeline delivers ~30 fps; running the detector on every frame the
 * device can keep up with is the single biggest battery cost in NoBonk. When nothing
 * has been in the frame for a while we can safely back off, because the first frame
 * that *does* contain something instantly snaps the cadence back to full rate (the
 * decision is made per frame from the previous result, so the worst case is one slow
 * interval of latency on the first sighting).
 *
 * Pure function so the policy is unit-tested; the engine/service just call [intervalMs].
 */
object FrameCadence {
    /** Full-rate interval when something is being tracked (≈10 fps ceiling). */
    const val BASE_MS = 100L
    /** After this long with an empty frame, drop to half rate. */
    const val IDLE_HALF_AFTER_MS = 3_000L
    /** After this long with an empty frame, drop to a third of full rate (~3 fps). */
    const val IDLE_THIRD_AFTER_MS = 12_000L
    /** Battery threshold below which every interval is stretched by [LOW_BATTERY_FACTOR]. */
    const val LOW_BATTERY_PCT = 20
    const val LOW_BATTERY_FACTOR = 1.5f
    /** Interval while the lens is covered (pocket, hand, face-down): just watch for light to return. */
    const val BLOCKED_MS = 500L

    /**
     * @param lastAlert         alert level of the previous processed frame
     * @param lastHadDetections whether the previous processed frame contained any object
     * @param idleMs            time since the last frame that contained an object
     * @param batteryPct        current battery percentage (0..100)
     * @param cameraBlocked     previous frame was judged blocked (dark AND flat)
     */
    fun intervalMs(
        lastAlert: AlertLevel,
        lastHadDetections: Boolean,
        idleMs: Long,
        batteryPct: Int,
        cameraBlocked: Boolean = false
    ): Long {
        if (cameraBlocked) return BLOCKED_MS
        var ms = when {
            lastAlert == AlertLevel.HIGH || lastAlert == AlertLevel.MEDIUM -> BASE_MS
            lastHadDetections -> BASE_MS
            idleMs >= IDLE_THIRD_AFTER_MS -> BASE_MS * 3
            idleMs >= IDLE_HALF_AFTER_MS -> BASE_MS * 2
            else -> BASE_MS
        }
        // Never slow down an active alert to save power — safety first — but stretch the
        // idle cadences on a low battery.
        if (batteryPct < LOW_BATTERY_PCT && lastAlert == AlertLevel.NONE) {
            ms = (ms * LOW_BATTERY_FACTOR).toLong()
        }
        return ms
    }
}
