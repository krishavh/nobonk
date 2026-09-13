package ai.genwhy.nobonk.motion

/**
 * One explicit walking-mode session, fed only by TYPE_STEP_DETECTOR events.
 * Both timestamps use the elapsedRealtimeNanos time base; wall-clock time is unsuitable.
 *
 * Defaults require at least 16 plausible steps spanning 20 seconds, without a gap above
 * 3.5 seconds. This allows slow walking while rejecting brief movement. A 250 ms refractory
 * period prevents duplicate events from inflating the count. This is a conservative trigger,
 * not a guarantee that the user is walking or that camera scanning is currently permitted.
 *
 * Delayed batches cannot supply historical walking: events older than two seconds break
 * the streak. Missing events never trigger anything. A trigger or cancel is terminal; create
 * a new instance only after another explicit arm action. The caller owns sensor cleanup and
 * must check its session authorization again before starting asynchronous camera work.
 */
class SustainedWalkingPolicy(
    val sustainedNanos: Long = 20_000_000_000L,
    val minimumSteps: Int = 16,
    val maximumGapNanos: Long = 3_500_000_000L,
    val maximumEventAgeNanos: Long = 2_000_000_000L,
    val minimumStepIntervalNanos: Long = 250_000_000L
) {
    init {
        require(sustainedNanos > 0)
        require(minimumSteps >= 2)
        require(minimumStepIntervalNanos > 0)
        require(maximumGapNanos >= minimumStepIntervalNanos)
        require(maximumEventAgeNanos >= 0)
    }

    private var terminal = false
    private var firstStepNanos: Long? = null
    private var lastStepNanos: Long? = null
    private var newestEventNanos: Long? = null
    private var newestNowNanos: Long? = null
    private var stepCount = 0

    /** Returns true exactly once, on a fresh qualifying step, and never after [cancel]. */
    @Synchronized fun onStep(eventNanos: Long, nowNanos: Long): Boolean {
        if (terminal) return false
        if (nowNanos < 0 || (newestNowNanos?.let { nowNanos < it } == true)) {
            resetStreak()
            return false
        }
        newestNowNanos = nowNanos
        // Check ordering before subtraction to avoid overflow on malformed timestamps.
        if (eventNanos < 0 || eventNanos > nowNanos || nowNanos - eventNanos > maximumEventAgeNanos) {
            resetStreak()
            return false
        }
        if (newestEventNanos?.let { eventNanos <= it } == true) return false
        newestEventNanos = eventNanos

        val previous = lastStepNanos
        if (previous != null && eventNanos - previous < minimumStepIntervalNanos) return false
        if (previous == null || eventNanos - previous > maximumGapNanos) {
            firstStepNanos = eventNanos
            stepCount = 1
        } else if (stepCount < minimumSteps) {
            stepCount++
        }
        lastStepNanos = eventNanos
        val first = firstStepNanos ?: return false
        if (stepCount >= minimumSteps && eventNanos - first >= sustainedNanos) {
            terminal = true
            return true
        }
        return false
    }

    /** Stop wins over every subsequent event, including already queued sensor callbacks. */
    @Synchronized fun cancel() {
        terminal = true
        resetStreak()
    }

    private fun resetStreak() {
        firstStepNanos = null
        lastStepNanos = null
        stepCount = 0
    }
}
