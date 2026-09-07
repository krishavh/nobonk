package ai.genwhy.nobonk.safety

import ai.genwhy.nobonk.safety.SafetyNotice.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNoticeTest {
    private val V = SafetyNotice.VERSION

    @Test fun freshInstallShowsFullNoticeAndBlocksCamera() {
        assertEquals(Screen.FULL_NOTICE, SafetyNotice.screenFor(0, acknowledgedThisProcess = false, serviceRunning = false))
        assertFalse(SafetyNotice.cameraAllowed(0))
    }
    @Test fun migrationFromFirstRunDoneStillShowsFullNoticeOnce() {
        // vc5 and earlier only stored first_run_done=true; the ack version is absent (0) → full notice.
        assertEquals(Screen.FULL_NOTICE, SafetyNotice.screenFor(0, false, false))
        // after acceptance the persisted value is the current version and the camera is allowed
        assertEquals(V, SafetyNotice.acknowledgedVersion()); assertTrue(SafetyNotice.cameraAllowed(V))
        assertEquals(Screen.NONE, SafetyNotice.screenFor(V, acknowledgedThisProcess = true, serviceRunning = false))
    }
    @Test fun olderAcknowledgedVersionRequiresReacknowledgment() {
        assertEquals(Screen.FULL_NOTICE, SafetyNotice.screenFor(V - 1, true, false))
        assertFalse(SafetyNotice.cameraAllowed(V - 1))
    }
    @Test fun uncheckedBoxCannotContinueAndDeclineLeavesNothingAcknowledged() {
        assertFalse(SafetyNotice.canContinue(false)); assertTrue(SafetyNotice.canContinue(true))
        // "Not now" persists nothing → next launch is still the full notice, camera still blocked
        assertEquals(Screen.FULL_NOTICE, SafetyNotice.screenFor(0, false, false)); assertFalse(SafetyNotice.cameraAllowed(0))
    }
    @Test fun coldLaunchAfterAcknowledgmentShowsReminderOnly() {
        assertEquals(Screen.REMINDER, SafetyNotice.screenFor(V, acknowledgedThisProcess = false, serviceRunning = false))
        assertTrue(SafetyNotice.cameraAllowed(V))
    }
    @Test fun rotationOrScreenRoundTripDoesNotReprompt() {
        // same process: the in-memory flag stays true
        assertEquals(Screen.NONE, SafetyNotice.screenFor(V, acknowledgedThisProcess = true, serviceRunning = false))
    }
    @Test fun returnFromRunningBackgroundSessionDoesNotInterrupt() {
        // process restarted by the sticky service (flag false) but the session is live → no prompt
        assertEquals(Screen.NONE, SafetyNotice.screenFor(V, acknowledgedThisProcess = false, serviceRunning = true))
    }
    @Test fun backgroundSessionNeverBypassesAnUnacknowledgedNotice() {
        assertEquals(Screen.FULL_NOTICE, SafetyNotice.screenFor(0, acknowledgedThisProcess = false, serviceRunning = true))
        assertFalse(SafetyNotice.cameraAllowed(0))
    }
    @Test fun reminderIsMandatoryEveryColdLaunchWithExplicitOk() {
        assertEquals("OK — continue", SafetyNotice.REMINDER_OK_LABEL)
        // a second cold launch (new process → flag false) shows it again; only the OK press (flag true) clears it
        assertEquals(Screen.REMINDER, SafetyNotice.screenFor(V, false, false)); assertEquals(Screen.NONE, SafetyNotice.screenFor(V, true, false))
    }
    @Test fun noticeTextMakesNoGuarantees() {
        val t = SafetyNotice.MAIN_TEXT.lowercase()
        assertTrue(t.contains("can miss")); assertTrue(t.contains("no alert does not mean the path is clear"))
        assertTrue(t.contains("not a certified safety device")); assertFalse(t.contains("waive")); assertFalse(t.contains("liab"))
    }
}
