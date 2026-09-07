package ai.genwhy.nobonk.safety

import ai.genwhy.nobonk.safety.SafetyNotice.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AckGateTest {
    private val V = SafetyNotice.VERSION

    @Test fun readFullThenBackStillBlocksAndCameraStaysOff() {
        val g = AckGate()
        assertEquals(Screen.REMINDER, g.screenOnCreate(V, null))
        g.onReadFull()                       // opens About over the reminder
        assertFalse(g.cleared); assertFalse(g.cameraAllowed(V)); assertFalse(g.permissionRequestAllowed(V))
        // Back returns to the pending reminder: recomputing yields REMINDER again
        assertEquals(Screen.REMINDER, SafetyNotice.screenFor(V, g.cleared, g.serviceActive))
        g.onAcknowledged()                   // only OK clears it
        assertTrue(g.cameraAllowed(V))
    }
    @Test fun permissionNotRequestableBeforeOkEvenWhenVersionPersisted() {
        val g = AckGate(); g.screenOnCreate(V, null)
        assertFalse(g.permissionRequestAllowed(V))
        g.onAcknowledged(); assertTrue(g.permissionRequestAllowed(V))
    }
    @Test fun fullNoticeNotAcknowledgedBlocksEverythingRegardlessOfSession() {
        val g = AckGate(); g.onAcknowledged()   // even a stale "cleared" flag
        assertEquals(Screen.FULL_NOTICE, g.screenOnCreate(0, true))
        assertFalse(g.cameraAllowed(0)); assertFalse(g.serviceMayStart(0, true)); assertFalse(g.serviceMayStart(V - 1, true)); assertTrue(g.serviceMayStart(V, true))
    }
    @Test fun finishAndRelaunchInSameProcessRepromptsEveryLaunch() {
        val g = AckGate(); g.screenOnCreate(V, null); g.onAcknowledged(); g.activityResumed = true
        g.activityResumed = false; g.onActivityFinished()      // Back / Not now / task removed
        assertEquals(Screen.REMINDER, g.screenOnCreate(V, null))
        assertFalse(g.cameraAllowed(V))
    }
    @Test fun configurationRecreationPreservesClearedGate() {
        val g = AckGate(); g.screenOnCreate(V, null); g.onAcknowledged()
        // recreated in the SAME process with saved state (cleared=true, matching token) → no prompt
        assertEquals(Screen.NONE, g.screenOnCreate(V, restoredCleared = true, restoredToken = g.processToken)); assertTrue(g.cameraAllowed(V))
    }
    @Test fun processDeathRestoreRepromptsDespiteSavedClearedFlag() {
        val old = AckGate(); old.onAcknowledged()
        val fresh = AckGate()                                  // new process, new token
        assertEquals(Screen.REMINDER, fresh.screenOnCreate(V, restoredCleared = true, restoredToken = old.processToken))
        assertFalse(fresh.cameraAllowed(V))
        assertEquals(Screen.REMINDER, fresh.screenOnCreate(V, restoredCleared = true, restoredToken = null))
    }
    @Test fun explicitActionStartRefusedWhileGatePending() {
        val g = AckGate(); g.screenOnCreate(V, null)           // reminder pending, version current
        assertFalse(g.serviceMayStart(V, explicitStart = true))
        g.onAcknowledged(); assertTrue(g.serviceMayStart(V, explicitStart = true))
        assertFalse(g.serviceMayStart(V - 1, explicitStart = true)); assertFalse(g.serviceMayStart(0, explicitStart = true))
    }
    @Test fun stickyRestartAllowedOnlyWithCurrentPersistedVersion() {
        val g = AckGate()                                      // fresh process after a kill: no in-memory gate
        assertTrue(g.serviceMayStart(V, explicitStart = false))
        assertFalse(g.serviceMayStart(V - 1, explicitStart = false)); assertFalse(g.serviceMayStart(0, explicitStart = false))
    }
    @Test fun returnToLiveAuthorizedBackgroundSessionDoesNotReprompt() {
        val g = AckGate(); g.screenOnCreate(V, null); g.onAcknowledged(); g.activityResumed = true
        g.onServiceStarted(); g.activityResumed = false; g.onActivityFinished()   // app left while detection runs
        assertEquals(Screen.NONE, g.screenOnCreate(V, null)); assertTrue(g.cameraAllowed(V))
    }
    @Test fun stopOnlyServiceInstanceDoesNotBypassTheGate() {
        val g = AckGate(); g.screenOnCreate(V, null)
        // an ACTION_STOP-created transient service never reports onServiceStarted; its destroy is harmless
        g.onServiceStopped()
        assertFalse(g.serviceActive); assertEquals(Screen.REMINDER, g.screenOnCreate(V, null)); assertFalse(g.cameraAllowed(V))
    }
    @Test fun idleBackgroundSessionStoppedResetsForNextLaunch() {
        val g = AckGate(); g.screenOnCreate(V, null); g.onAcknowledged(); g.onServiceStarted()
        g.activityResumed = false; g.onActivityFinished()
        g.onServiceStopped()                                  // Stop from the notification, app not in front
        assertEquals(Screen.REMINDER, g.screenOnCreate(V, null))
    }
    @Test fun handoffStopWhileActivityResumedKeepsGate() {
        val g = AckGate(); g.screenOnCreate(V, null); g.onAcknowledged(); g.onServiceStarted()
        g.activityResumed = true; g.onServiceStopped()        // onResume stopped the service (camera hand-off)
        assertTrue(g.cleared); assertTrue(g.cameraAllowed(V))
    }
}
