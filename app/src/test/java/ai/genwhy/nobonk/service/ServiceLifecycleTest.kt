package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.service.ServiceLifecycle.Phase
import ai.genwhy.nobonk.service.ServiceLifecycle.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleTest {
    private fun running(): ServiceLifecycle = ServiceLifecycle().apply {
        assertTrue(onStartRequested()); assertTrue(onModelLoaded()); assertTrue(mayBindCamera()); onCameraBound()
    }

    @Test fun normalStartReachesRunningAndAllowsWork() {
        val l = running()
        assertEquals(Phase.RUNNING, l.phase); assertTrue(l.mayProcessFrames()); assertTrue(l.mayPostAlerts()); assertTrue(l.sticky())
    }
    @Test fun rapidStopDuringModelLoadPreventsCameraBindAndAlerts() {
        val l = ServiceLifecycle(); assertTrue(l.onStartRequested())
        l.stop(StopReason.USER)                       // Stop while loadModel() is still running
        assertFalse(l.onModelLoaded())                // → release the just-loaded engine, no camera
        assertFalse(l.mayBindCamera()); assertFalse(l.mayProcessFrames()); assertFalse(l.mayPostAlerts())
        assertFalse(l.sticky()); assertTrue(l.stoppedByUser)
    }
    @Test fun stopDuringCameraBindingPreventsBindAndLaterCallbacks() {
        val l = ServiceLifecycle(); l.onStartRequested(); l.onModelLoaded()
        l.stop(StopReason.USER)
        assertFalse(l.mayBindCamera())
        l.onCameraBound()                             // late CameraX callback must not revive RUNNING
        assertEquals(Phase.STOPPED, l.phase); assertFalse(l.mayPostAlerts())
    }
    @Test fun inFlightFrameAfterStopCannotPostNotificationOrHud() {
        val l = running(); l.stop(StopReason.USER)
        assertFalse(l.mayPostAlerts()); assertFalse(l.mayProcessFrames())
    }
    @Test fun startAfterStopOnSameInstanceIsRefused() {
        val l = running(); l.stop(StopReason.USER)
        assertFalse(l.onStartRequested()); assertFalse(l.sticky())
    }
    @Test fun repeatedStartStopCyclesAreIndependentInstances() {
        repeat(5) { val l = running(); l.stop(if (it % 2 == 0) StopReason.USER else StopReason.HANDOFF); assertTrue(l.isStopped) }
        val fresh = ServiceLifecycle(); assertTrue(fresh.onStartRequested())   // a new service instance starts clean
    }
    @Test fun handoffStopIsNotAUserStop() {
        val l = running(); l.stop(StopReason.HANDOFF)
        assertFalse(l.stoppedByUser); assertTrue(l.isStopped)
    }
    @Test fun concurrentStopAndModelLoadedNeverLeavesTheServiceRunning() {
        // Real threads: main-side stop() racing the worker-side onModelLoaded(). Whatever the
        // interleaving, the end state is STOPPED and a true onModelLoaded implies the engine will
        // be adopted by a shutdown that runs after it (never a lost Stop).
        repeat(300) {
            val l = ServiceLifecycle(); l.onStartRequested()
            val loaded = java.util.concurrent.atomic.AtomicBoolean(false)
            val t1 = Thread { l.stop(StopReason.USER) }
            val t2 = Thread { loaded.set(l.onModelLoaded()) }
            t2.start(); t1.start(); t1.join(); t2.join()
            assertEquals(Phase.STOPPED, l.phase); assertFalse(l.mayBindCamera()); assertFalse(l.mayPostAlerts())
        }
    }
    @Test fun stopIsIdempotentAndFirstReasonWins() {
        val l = running(); l.stop(StopReason.USER); l.stop(StopReason.HANDOFF)
        assertEquals(StopReason.USER, l.stopReason)
    }
}
