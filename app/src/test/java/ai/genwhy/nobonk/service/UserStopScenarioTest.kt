package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.service.ServiceLifecycle.StopReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end state scenarios for the "resume in stopped state" memory, using the real
 * ServiceLifecycle plus a minimal model of the two activity-side rules:
 *  - the service marks the flag only when a *started* session was user-stopped;
 *  - an explicit Start in the foreground clears the flag; onResume consumes it.
 */
class UserStopScenarioTest {
    private class App {
        var stoppedByUserFlag = false
        var scanning = true
        fun serviceShutdown(l: ServiceLifecycle) { if (l.stoppedByUser) stoppedByUserFlag = true }
        fun explicitStart() { stoppedByUserFlag = false; scanning = true }
        fun inAppStop() { scanning = false; serviceShutdown(ServiceLifecycle().also { it.stop(StopReason.USER) }) } // transient instance
        fun onResume() { if (stoppedByUserFlag) { stoppedByUserFlag = false; scanning = false } }
    }
    private fun startedService(): ServiceLifecycle = ServiceLifecycle().apply { onStartRequested(); onModelLoaded(); onCameraBound() }

    @Test fun inAppStopWithoutServiceThenStartThenBackgroundThenOpenNoBonkKeepsScanning() {
        val app = App()
        app.inAppStop()                                   // Stop in foreground, no service running
        assertFalse(app.stoppedByUserFlag)                // transient stop-only instance leaves no memory
        app.explicitStart()
        val svc = startedService()                        // Run in background
        svc.stop(StopReason.HANDOFF); app.serviceShutdown(svc)   // Open NoBonk → activity takes the camera back
        app.onResume()
        assertTrue(app.scanning)                          // foreground continues scanning
    }
    @Test fun backgroundUserStopThenReopenStaysStopped() {
        val app = App(); val svc = startedService()
        svc.stop(StopReason.USER); app.serviceShutdown(svc)      // notification Stop
        app.onResume()
        assertFalse(app.scanning); assertFalse(app.stoppedByUserFlag)   // consumed once
        app.onResume(); assertFalse(app.scanning)                // later resumes do not silently restart
    }
    @Test fun rapidStopDuringStartupIsAUserStop() {
        val app = App(); val svc = ServiceLifecycle(); svc.onStartRequested()   // START delivered, model loading
        svc.stop(StopReason.USER); app.serviceShutdown(svc)
        assertTrue(app.stoppedByUserFlag)
        app.onResume(); assertFalse(app.scanning)
    }
    @Test fun explicitStartClearsObsoleteStopMemory() {
        val app = App(); val svc = startedService(); svc.stop(StopReason.USER); app.serviceShutdown(svc)
        app.explicitStart()                               // user deliberately started again before returning
        val again = startedService(); again.stop(StopReason.HANDOFF); app.serviceShutdown(again)
        app.onResume(); assertTrue(app.scanning)
    }
}
