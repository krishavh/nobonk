package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel.*
import org.junit.Assert.*
import org.junit.Test

class AlertCadenceTest {
    @Test fun persistentHighEmitsFiveTimesInThirtySecondsInsteadOfEveryFrame() {
        val gate = AlertCadence()
        val emissions = (0L until 30_000L step 100L).count { gate.shouldEmit(HIGH, "person", it, true) }
        assertEquals(5, emissions)
    }
    @Test fun escalationDoesNotWaitForTheOrdinaryRepeatInterval() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(LOW, "a", 0, true))
        assertFalse(gate.shouldEmit(HIGH, "a", 100, true))
        assertTrue(gate.shouldEmit(HIGH, "a", 400, true))
    }
    @Test fun briefDropoutAndChangingTrackIdsCannotProduceABuzzEveryFrame() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        assertFalse(gate.shouldEmit(NONE, null, 100, true))
        assertFalse(gate.shouldEmit(HIGH, "b", 200, true))
        assertTrue(gate.shouldEmit(HIGH, "b", 1500, true))
    }
    @Test fun sustainedClearSceneRearmsFirstHazard() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        for (time in 100L..3100L step 500L) assertFalse(gate.shouldEmit(NONE, null, time, true))
        assertTrue(gate.shouldEmit(LOW, "a", 3200, true))
    }
    @Test fun suppressedCuesDoNotConsumeFirstAlert() {
        val gate = AlertCadence()
        assertFalse(gate.shouldEmit(HIGH, "a", 0, false))
        assertTrue(gate.shouldEmit(HIGH, "a", 1, true))
    }
    @Test fun repeatedMediumAndLowAreSpacedEvenWithoutATrackId() {
        for ((level, interval) in listOf(MEDIUM to 10_000L, LOW to 15_000L)) {
            val gate = AlertCadence()
            assertTrue(gate.shouldEmit(level, null, 0, true))
            for (t in 100L until interval step 100L) assertFalse(gate.shouldEmit(level, null, t, true))
            assertTrue(gate.shouldEmit(level, null, interval, true))
        }
    }
    @Test fun resetAndClockRollbackDoNotLeaveNewSessionsMuted() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 5000, true))
        gate.reset()
        assertTrue(gate.shouldEmit(HIGH, "a", 5001, true))
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
    }
    @Test fun switchingBetweenRecentlyAlertedPeopleDoesNotPretendTheyAreNew() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        assertTrue(gate.shouldEmit(HIGH, "b", 1500, true))
        assertFalse(gate.shouldEmit(HIGH, "a", 3000, true))
        assertFalse(gate.shouldEmit(HIGH, "b", 4500, true))
        assertTrue(gate.shouldEmit(HIGH, "c", 4600, true))
    }
    @Test fun briefBadAngleAndRecoveryDoNotRearmAnAlert() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        assertFalse(gate.shouldEmit(HIGH, "a", 100, false))
        assertFalse(gate.shouldEmit(NONE, null, 200, true))
        // Tracker may have assigned a new ID after the off-angle frame.
        assertFalse(gate.shouldEmit(HIGH, "new-a", 300, true))
        assertFalse(gate.shouldEmit(HIGH, "new-a", 1499, true))
        assertTrue(gate.shouldEmit(HIGH, "new-a", 1500, true))
    }
    @Test fun continuousTrackChurnStillHasAGlobalLimit() {
        val gate = AlertCadence()
        val emissions = (0L until 30_000L step 100L).count {
            gate.shouldEmit(HIGH, "track-$it", it, true)
        }
        assertEquals(20, emissions)
    }

    @Test fun absentFramesDoNotRearmSameOrReassignedPerson() {
        for (gap in listOf(3000L, 3100L)) for (identity in listOf("a", "reassigned-a")) {
            val gate = AlertCadence()
            assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
            assertFalse(gate.shouldEmit(HIGH, identity, gap, true))
            assertFalse(gate.shouldEmit(HIGH, "another-id", 3200, true))
            assertFalse(gate.shouldEmit(HIGH, identity, 5999, true))
            assertTrue(gate.shouldEmit(HIGH, identity, 6000, true))
            // Once a cue establishes fresh identity continuity, genuinely new people keep
            // the existing 1.5-second exception.
            assertTrue(gate.shouldEmit(HIGH, "new-person", 7500, true))
        }
    }

    @Test fun observedClearRearmsAtThreeSecondsNotOneMillisecondBefore() {
        for (duration in listOf(2999L, 3000L)) {
            val gate = AlertCadence()
            assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
            for (time in 100L..2600L step 500L) assertFalse(gate.shouldEmit(NONE, null, time, true))
            assertFalse(gate.shouldEmit(NONE, null, 100 + duration, true))
            assertEquals(duration == 3000L, gate.shouldEmit(LOW, "a", 101 + duration, true))
        }
    }

    @Test fun suppressionBreaksClearEvidenceButPreservesEmissionBudget() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        assertFalse(gate.shouldEmit(NONE, null, 100, true))
        assertFalse(gate.shouldEmit(NONE, null, 2000, false))
        assertFalse(gate.shouldEmit(NONE, null, 3100, true))
        assertFalse(gate.shouldEmit(HIGH, "reassigned-a", 3200, true))
        assertTrue(gate.shouldEmit(HIGH, "reassigned-a", 6000, true))
    }

    @Test fun twoClearSamplesSeparatedByMissingFramesAreNotAContinuousClearScene() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        assertFalse(gate.shouldEmit(NONE, null, 100, true))
        assertFalse(gate.shouldEmit(NONE, null, 3200, true))
        assertFalse(gate.shouldEmit(LOW, "a", 3300, true))
        // Start counting actual clear observations again, rather than time since the old hazard.
        for (time in 3400L..6400L step 500L) assertFalse(gate.shouldEmit(NONE, null, time, true))
        assertTrue(gate.shouldEmit(LOW, "a", 6500, true))
    }

    @Test fun gapDoesNotBlockSeverityEscalationAndSuppressedClockRollbackResets() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(LOW, "a", 0, true))
        assertTrue(gate.shouldEmit(HIGH, "reassigned-a", 3100, true))
        assertFalse(gate.shouldEmit(HIGH, "a", 5000, false))
        assertFalse(gate.shouldEmit(HIGH, "a", 100, false))
        assertTrue(gate.shouldEmit(HIGH, "a", 101, true))
    }

    @Test fun coveredCameraInterruptsClearEvidenceWithoutRearmingTheCue() {
        val gate = AlertCadence()
        assertTrue(gate.shouldEmit(HIGH, "a", 0, true))
        assertFalse(gate.shouldEmit(NONE, null, 100, true))
        assertFalse(gate.shouldEmit(NONE, null, 1500, true))
        gate.interruptObservation()
        assertFalse(gate.shouldEmit(NONE, null, 2000, true))
        assertFalse(gate.shouldEmit(NONE, null, 3100, true))
        assertFalse(gate.shouldEmit(HIGH, "a", 3200, true))
        for (time in 3300L..6300L step 500L) assertFalse(gate.shouldEmit(NONE, null, time, true))
        assertTrue(gate.shouldEmit(LOW, "a", 6400, true))
    }

}
