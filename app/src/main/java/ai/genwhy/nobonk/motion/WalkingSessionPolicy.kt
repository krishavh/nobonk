package ai.genwhy.nobonk.motion

/** Repeated walk/pause cycles within one explicitly armed session. No location is inferred. */
class WalkingSessionPolicy(val inactivityNanos: Long = 60_000_000_000L) {
    enum class Transition { NONE, START, PAUSE }
    init { require(inactivityNanos > 0) }
    private var qualification = SustainedWalkingPolicy()
    private var cancelled = false
    private var walking = false
    private var lastStep: Long? = null
    private var lastNow: Long? = null

    @Synchronized fun onStep(eventNanos: Long, nowNanos: Long): Transition {
        val elapsed = onTime(nowNanos)
        if (cancelled || nowNanos < 0 || lastNow != nowNanos) return Transition.NONE
        // Old/batched/duplicate/future events must not keep the camera alive.
        if (eventNanos < 0 || eventNanos > nowNanos || nowNanos - eventNanos > 2_000_000_000L ||
            lastStep?.let { eventNanos <= it || eventNanos - it < 250_000_000L } == true) {
            if (!walking) qualification.onStep(eventNanos, nowNanos)
            return elapsed
        }
        lastStep = eventNanos
        if (!walking && qualification.onStep(eventNanos, nowNanos)) {
            walking = true
            return Transition.START
        }
        return elapsed
    }

    /** A timer is necessary: absence of steps does not emit a sensor event. */
    @Synchronized fun onTime(nowNanos: Long): Transition {
        if (cancelled || nowNanos < 0 || lastNow?.let { nowNanos < it } == true) return Transition.NONE
        lastNow = nowNanos
        if (walking && lastStep?.let { nowNanos - it >= inactivityNanos } == true) {
            walking = false
            qualification = SustainedWalkingPolicy()
            return Transition.PAUSE
        }
        return Transition.NONE
    }

    @Synchronized fun cancel() { cancelled = true; walking = false; qualification.cancel() }
}
