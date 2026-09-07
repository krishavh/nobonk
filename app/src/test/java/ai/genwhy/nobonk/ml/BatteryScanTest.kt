package ai.genwhy.nobonk.ml

import org.junit.Assert.*
import org.junit.Test

class BatteryScanTest {
    @Test fun lowBatteryInvalidatesInflightFrameAndRecoveryAcceptsOnlyNewFrames() {
        val session = ScanSession()
        val beforePause = session.current()
        session.setPowerAvailable(false)
        assertFalse(session.isCurrent(beforePause))
        assertFalse(session.mayScan)
        val whilePaused = session.current()
        session.setPowerAvailable(true)
        assertFalse(session.isCurrent(beforePause))
        assertFalse(session.isCurrent(whilePaused))
        assertTrue(session.isCurrent(session.current()))
    }

    @Test fun chargingAfterUserStopNeverRestartsScanning() {
        val session = ScanSession()
        session.setPowerAvailable(false)
        session.stop()
        session.setPowerAvailable(true)
        assertFalse(session.active)
        assertFalse(session.mayScan)
        assertFalse(session.isCurrent(session.current()))
        session.start()
        assertTrue(session.mayScan)
    }

    @Test fun startWhileBatteryLowWaitsForRecoveryAndModelCompletionRespectsPause() {
        val session = ScanSession()
        session.stop() // loading a model
        session.setPowerAvailable(false)
        session.start() // model finished / explicit Start
        assertFalse(session.mayScan)
        session.setPowerAvailable(true)
        assertTrue(session.mayScan)
    }

    @Test fun unchangedBatteryAvailabilityDoesNotInvalidateLiveFrame() {
        val session = ScanSession()
        val frame = session.current()
        session.setPowerAvailable(true)
        assertTrue(session.isCurrent(frame))
    }

    @Test fun backgroundHandoffDropsForegroundFrameAndRecoveryDoesNotActivateHiddenOwner() {
        val session = ScanSession()
        val foregroundFrame = session.current()
        session.setOwnerAvailable(false)
        assertFalse(session.isCurrent(foregroundFrame))
        session.setPowerAvailable(false)
        session.setPowerAvailable(true)
        assertFalse(session.mayScan)
        session.setOwnerAvailable(true)
        assertFalse(session.isCurrent(foregroundFrame))
        assertTrue(session.isCurrent(session.current()))
        session.stop()
        session.setOwnerAvailable(false)
        session.setOwnerAvailable(true)
        assertFalse(session.mayScan)
    }

    @Test fun invalidBatteryBroadcastDoesNotReleasePauseAndPercentageHandlesScale() {
        assertEquals(9, BatteryLevel.percent(-1, 100, 9))
        assertEquals(9, BatteryLevel.percent(50, 0, 9))
        assertEquals(10, BatteryLevel.percent(20, 200, 9))
        assertEquals(100, BatteryLevel.percent(Int.MAX_VALUE, 100, 9))
        assertEquals(0, BatteryLevel.percent(0, 100, 90))
    }
}
