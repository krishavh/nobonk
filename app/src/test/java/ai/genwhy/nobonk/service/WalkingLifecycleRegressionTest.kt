package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.service.ServiceLifecycle.*
import org.junit.Assert.*
import org.junit.Test

class WalkingLifecycleRegressionTest {
    private fun armed() = ServiceLifecycle().apply { assertTrue(onStartRequested(true)) }
    @Test fun walkingAuthorizesExactlyOneReminderNeverCameraOrModel() {
        val l = armed(); val old = l.scanGeneration()
        assertTrue(l.onWalkingConfirmed())
        assertEquals(Phase.WALKING_PROMPTED, l.phase)
        repeat(20) { assertFalse(l.onWalkingConfirmed()) }
        assertFalse(l.onModelLoaded(old)); assertFalse(l.onModelLoaded())
        assertFalse(l.mayBindCamera()); l.onCameraBound()
        assertFalse(l.mayProcessFrames()); assertFalse(l.mayPostAlerts())
    }
    @Test fun stopBeforeWalkingNeverPrompts() {
        val l = armed(); l.stop(StopReason.USER)
        assertFalse(l.onWalkingConfirmed()); assertFalse(l.onStartRequested(true))
    }
    @Test fun promptThenStopCannotRearmOrLoad() {
        val l = armed(); l.onWalkingConfirmed(); l.stop(StopReason.USER)
        assertFalse(l.onWalkingConfirmed()); assertFalse(l.onModelLoaded())
        assertFalse(l.onStartRequested()); assertTrue(l.stoppedByUser)
    }
    @Test fun explicitManualStartUsesFreshInstanceAndStopInvalidatesFrames() {
        val reminder = armed(); reminder.onWalkingConfirmed(); reminder.stop(StopReason.USER)
        val scan = ServiceLifecycle(); assertTrue(scan.onStartRequested())
        val token = scan.scanGeneration(); assertTrue(scan.onModelLoaded(token)); scan.onCameraBound(token)
        assertTrue(scan.mayPostAlerts(token)); assertFalse(scan.onWalkingConfirmed())
        scan.stop(StopReason.USER)
        assertFalse(scan.mayProcessFrames(token)); assertFalse(scan.mayPostAlerts(token))
    }
}
