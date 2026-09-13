package ai.genwhy.nobonk.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SustainedWalkingPolicyTest {
    private fun ms(value: Long) = value * 1_000_000L
    private fun step(policy: SustainedWalkingPolicy, atMs: Long, deliveredMs: Long = atMs) =
        policy.onStep(ms(atMs), ms(deliveredMs))

    @Test fun normalWalkingNeedsFullTwentySecondsAndTriggersOnlyOnce() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L until 20_000L step 500L) assertFalse(step(policy, at))
        assertTrue(step(policy, 20_000))
        for (at in 20_500L..50_000L step 500L) assertFalse(step(policy, at))
    }

    @Test fun slowWalkingMustMeetBothDurationAndStepCount() {
        val policy = SustainedWalkingPolicy()
        // One step every two seconds: 20 seconds alone gives only 11 steps.
        for (at in 0L until 30_000L step 2_000L) assertFalse(step(policy, at))
        assertTrue(step(policy, 30_000))
    }

    @Test fun pauseBreaksWalkingAndRequiresNewFullInterval() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L..19_000L step 1_000L) assertFalse(step(policy, at))
        for (at in 23_000L until 43_000L step 1_000L) assertFalse(step(policy, at))
        assertTrue(step(policy, 43_000))
    }

    @Test fun repeatedShortBurstsNeverAccumulateAcrossPauses() {
        val policy = SustainedWalkingPolicy()
        for (burst in 0L..10L) {
            for (offset in 0L..10_000L step 500L) assertFalse(step(policy, burst * 15_000L + offset))
        }
    }

    @Test fun staleBatchCannotInstantlySupplyTwentySecondsOfWalking() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L..20_000L step 500L) assertFalse(step(policy, at, 20_000))
        // Only the fresh tail starting at 18 seconds counted.
        for (at in 20_500L until 38_000L step 500L) assertFalse(step(policy, at))
        assertTrue(step(policy, 38_000))
    }

    @Test fun staleEventBreaksPreviouslyAccumulatedWalking() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L..19_000L step 1_000L) assertFalse(step(policy, at))
        assertFalse(step(policy, 19_500, 22_000))
        for (at in 22_000L until 42_000L step 1_000L) assertFalse(step(policy, at))
        assertTrue(step(policy, 42_000))
    }

    @Test fun duplicatesReorderingAndFastEchoesDoNotInflateStepCount() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L until 30_000L step 2_000L) {
            assertFalse(step(policy, at))
            assertFalse(step(policy, at, at + 10))
            if (at > 0) assertFalse(step(policy, at - 100, at + 20))
            assertFalse(step(policy, at + 100))
        }
        assertTrue(step(policy, 30_000))
    }

    @Test fun futureEventDoesNotPoisonNextValidStreak() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L..19_000L step 1_000L) assertFalse(step(policy, at))
        assertFalse(policy.onStep(Long.MAX_VALUE, ms(20_000)))
        for (at in 20_000L until 40_000L step 1_000L) assertFalse(step(policy, at))
        assertTrue(step(policy, 40_000))
    }

    @Test fun backwardsCallbackClockCannotCompleteAStreak() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L..19_000L step 1_000L) assertFalse(step(policy, at))
        assertFalse(step(policy, 18_500, 18_500))
        for (at in 20_000L until 40_000L step 1_000L) assertFalse(step(policy, at))
        assertTrue(step(policy, 40_000))
    }

    @Test fun cancelledSessionRejectsQueuedAndFutureSteps() {
        val policy = SustainedWalkingPolicy()
        for (at in 0L until 20_000L step 500L) assertFalse(step(policy, at))
        policy.cancel()
        policy.cancel()
        for (at in 20_000L..90_000L step 500L) assertFalse(step(policy, at))
    }

    @Test fun exactGapAndFreshnessLimitsAreAccepted() {
        val policy = SustainedWalkingPolicy(sustainedNanos = ms(7_000), minimumSteps = 3)
        assertFalse(step(policy, 0, 2_000))
        assertFalse(step(policy, 3_500, 5_500))
        assertTrue(step(policy, 7_000, 9_000))
    }

    @Test fun negativeTimestampsAreRejectedWithoutOverflow() {
        val policy = SustainedWalkingPolicy()
        assertFalse(policy.onStep(Long.MIN_VALUE, Long.MAX_VALUE))
        assertFalse(policy.onStep(0, -1))
    }
}
