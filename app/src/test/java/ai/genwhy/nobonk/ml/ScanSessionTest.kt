package ai.genwhy.nobonk.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {
    @Test fun resultFromFrameDispatchedBeforeStopIsDropped() {
        val s = ScanSession(); val gen = s.current()          // frame dispatched
        s.stop()                                              // user Stop while inference runs
        assertFalse(s.isCurrent(gen)); assertFalse(s.active)
    }
    @Test fun restartAcceptsOnlyFramesFromTheNewSession() {
        val s = ScanSession(); val old = s.current(); s.stop(); s.start()
        assertFalse(s.isCurrent(old))                          // stale frame from before Stop
        val fresh = s.current(); assertTrue(s.isCurrent(fresh)); assertTrue(s.active)
    }
    @Test fun stopDuringDelayedInferenceThenRestartThenStopAgain() {
        val s = ScanSession(); val a = s.current(); s.stop(); s.start(); val b = s.current(); s.stop()
        assertFalse(s.isCurrent(a)); assertFalse(s.isCurrent(b))
    }
    @Test fun currentFrameAcceptedWhileActive() { val s = ScanSession(); assertTrue(s.isCurrent(s.current())) }
    @Test fun modeChangeInvalidatesOldFrameButPreservesScanningIntent() {
        val s = ScanSession(); val old = s.current(); s.invalidateResults()
        assertFalse(s.isCurrent(old)); assertTrue(s.mayScan); assertTrue(s.isCurrent(s.current()))
        s.stop(); s.invalidateResults(); assertFalse(s.active); assertFalse(s.mayScan)
        s.start(); s.setPowerAvailable(false); s.invalidateResults(); assertFalse(s.mayScan)
        s.setPowerAvailable(true); s.setOwnerAvailable(false); s.invalidateResults(); assertFalse(s.mayScan)
    }
}
