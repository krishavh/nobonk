package ai.genwhy.nobonk.motion

import ai.genwhy.nobonk.motion.WalkingSessionPolicy.Transition.*
import org.junit.Assert.*
import org.junit.Test

class WalkingSessionPolicyTest {
    private val second = 1_000_000_000L
    private fun qualify(p: WalkingSessionPolicy, start: Long = second): Long {
        for (s in 0L until 20L) assertEquals(NONE, p.onStep(start + s * second, start + s * second))
        val end = start + 20 * second
        assertEquals(START, p.onStep(end, end))
        return end
    }
    @Test fun startsOnlyAfterSustainedWalkingAndPausesAtSixtySecondsWithoutSteps() {
        val p = WalkingSessionPolicy(); val last = qualify(p)
        assertEquals(NONE, p.onTime(last + 60 * second - 1))
        assertEquals(PAUSE, p.onTime(last + 60 * second))
        assertEquals(NONE, p.onTime(last + 61 * second))
    }
    @Test fun briefStopDoesNotToggleCameraAndContinuingStepsExtendSession() {
        val p = WalkingSessionPolicy(); val last = qualify(p)
        assertEquals(NONE, p.onTime(last + 30 * second))
        assertEquals(NONE, p.onStep(last + 40 * second, last + 40 * second))
        assertEquals(NONE, p.onTime(last + 99 * second))
        assertEquals(PAUSE, p.onTime(last + 100 * second))
    }
    @Test fun repeatedWalkPauseCyclesRequireFullFreshQualification() {
        val p = WalkingSessionPolicy(); var start = second
        repeat(5) {
            val end = qualify(p, start)
            assertEquals(PAUSE, p.onTime(end + 60 * second))
            start = end + 61 * second
        }
    }
    @Test fun delayedTimerCannotBeHiddenByFirstStepAfterLongStop() {
        val p = WalkingSessionPolicy(); val end = qualify(p)
        val next = end + 120 * second
        assertEquals(PAUSE, p.onStep(next, next))
        for (s in 1L until 20L) assertEquals(NONE, p.onStep(next + s * second, next + s * second))
        assertEquals(START, p.onStep(next + 20 * second, next + 20 * second))
    }
    @Test fun staleDuplicateAndFutureEventsDoNotKeepScanningAlive() {
        val p = WalkingSessionPolicy(); val end = qualify(p)
        assertEquals(NONE, p.onStep(end, end + 30 * second))
        assertEquals(NONE, p.onStep(end + second, end + 40 * second))
        assertEquals(NONE, p.onStep(end + 99 * second, end + 50 * second))
        assertEquals(PAUSE, p.onTime(end + 60 * second))
    }
    @Test fun cancelWhileWaitingOrScanningIsTerminal() {
        listOf(false, true).forEach { scanning ->
            val p = WalkingSessionPolicy(); if (scanning) qualify(p)
            p.cancel()
            for (i in 100L..300L) {
                assertEquals(NONE, p.onStep(i * second, i * second))
                assertEquals(NONE, p.onTime(i * second))
            }
        }
    }
    @Test fun shortBurstsAndMalformedClockDoNotStartSession() {
        val p = WalkingSessionPolicy()
        for (i in 1L..10L) assertEquals(NONE, p.onStep(i * second, i * second))
        assertEquals(NONE, p.onStep(9 * second, 9 * second))
        assertEquals(NONE, p.onStep(-1, 11 * second))
        for (i in 20L..30L) assertEquals(NONE, p.onStep(i * second, i * second))
        assertEquals(NONE, p.onTime(500 * second))
    }
}
