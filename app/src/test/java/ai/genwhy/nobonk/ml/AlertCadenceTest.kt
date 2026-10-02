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
        assertFalse(gate.shouldEmit(NONE, null, 3000, true))
        assertTrue(gate.shouldEmit(LOW, "a", 3100, true))
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

}
