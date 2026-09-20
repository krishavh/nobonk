package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.service.ServiceLifecycle.*
import org.junit.Assert.*
import org.junit.Test

class WalkingLifecycleRegressionTest {
    private fun armed() = ServiceLifecycle().apply { assertTrue(onStartRequested(true)) }
    private fun run(l: ServiceLifecycle): Long {
        assertTrue(l.onWalkingConfirmed())
        val scan = l.scanGeneration()
        assertTrue(l.onModelLoaded(scan)); l.onCameraBound(scan)
        assertTrue(l.mayProcessFrames(scan))
        return scan
    }
    @Test fun pauseInvalidatesOldFramesEvenAfterNewScanIsRunning() {
        val l = armed(); val old = run(l)
        assertTrue(l.onWalkingPaused())
        assertFalse(l.mayPostAlerts(old)); assertFalse(l.mayProcessFrames(old))
        val fresh = run(l)
        assertFalse(l.mayPostAlerts(old)); assertTrue(l.mayPostAlerts(fresh))
        assertFalse(l.onModelLoaded(old)); l.onCameraBound(old)
        assertEquals(Phase.RUNNING, l.phase)
    }
    @Test fun oldModelCompletionCannotBeAdoptedIntoNextLoadingCycle() {
        val l = armed(); l.onWalkingConfirmed(); val old = l.scanGeneration()
        assertTrue(l.onWalkingPaused()); l.onWalkingConfirmed()
        assertFalse(l.onModelLoaded(old)); assertEquals(Phase.LOADING_MODEL, l.phase)
        assertTrue(l.onModelLoaded(l.scanGeneration()))
    }
    @Test fun oldCameraCallbackCannotBindIntoNextBindingCycle() {
        val l = armed(); l.onWalkingConfirmed(); val old = l.scanGeneration(); l.onModelLoaded(old)
        assertTrue(l.onWalkingPaused()); l.onWalkingConfirmed(); val fresh = l.scanGeneration(); l.onModelLoaded(fresh)
        assertFalse(l.mayBindCamera(old)); l.onCameraBound(old)
        assertEquals(Phase.BINDING_CAMERA, l.phase)
        l.onCameraBound(fresh); assertEquals(Phase.RUNNING, l.phase)
    }
    @Test fun manualStopWinsFromEveryWalkingPhaseAndNeverRearms() {
        for (stage in 0..4) {
            val l = armed()
            if (stage >= 1) l.onWalkingConfirmed()
            if (stage >= 2) l.onModelLoaded()
            if (stage >= 3) l.onCameraBound()
            if (stage == 4) l.onWalkingPaused()
            val token = l.scanGeneration(); l.stop(StopReason.USER)
            assertFalse(l.onWalkingConfirmed()); assertFalse(l.onWalkingPaused())
            assertFalse(l.onStartRequested(true)); assertFalse(l.isCurrent(token)); assertTrue(l.stoppedByUser)
        }
    }
    @Test fun stationaryDoesNotPauseManualBackgroundScanning() {
        val l = ServiceLifecycle(); l.onStartRequested(); l.onModelLoaded(); l.onCameraBound()
        assertFalse(l.onWalkingPaused()); assertTrue(l.mayPostAlerts())
    }
}
