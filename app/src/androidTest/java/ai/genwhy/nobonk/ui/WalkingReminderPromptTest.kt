package ai.genwhy.nobonk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.MainActivity
import ai.genwhy.nobonk.requireIsolatedEmulator
import ai.genwhy.nobonk.safety.SafetyNotice
import ai.genwhy.nobonk.safety.SessionState
import ai.genwhy.nobonk.service.DetectionService
import ai.genwhy.nobonk.viewmodel.DetectionViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Opening a reminder, safety acknowledgement and recreation must never authorize the camera. */
class WalkingReminderPromptTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun prepare() {
        requireIsolatedEmulator()
        i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, Manifest.permission.CAMERA)
        i.targetContext.getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE).edit()
            .putInt(SafetyNotice.PREF_ACK_VERSION, SafetyNotice.VERSION).putBoolean("cue_choice_done", true).commit()
        i.runOnMainSync { SessionState.gate.onAcknowledged() }
    }
    private fun assertStopped(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            val getter = MainActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }
            assertFalse((getter.invoke(it) as DetectionViewModel).scanningEnabled)
            assertFalse(SessionState.gate.serviceActive)
        }
    }
    @Test fun reminderColdOpenRotationAndNotNowKeepCameraOff() {
        prepare()
        val intent = Intent(i.targetContext, MainActivity::class.java)
            .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithText("Turn on NoBonk?").assertIsDisplayed()
            assertStopped(scenario)
            scenario.recreate()
            compose.onNodeWithText("Turn on NoBonk?").assertIsDisplayed()
            assertStopped(scenario)
            compose.onNodeWithText("Not now").performClick()
            compose.onNodeWithText("Start scanning").assertIsDisplayed()
            assertStopped(scenario)
            scenario.recreate()
            compose.onNodeWithText("Turn on NoBonk?").assertDoesNotExist()
            assertStopped(scenario)
        }
    }
    @Test fun reminderWarmIntentLeavesScanningOffUntilExplicitStart() {
        prepare()
        val initial = Intent(i.targetContext, MainActivity::class.java)
            .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true)
        ActivityScenario.launch<MainActivity>(initial).use { scenario ->
            compose.onNodeWithText("Not now").performClick()
            scenario.onActivity {
                it.startActivity(Intent(it, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true))
            }
            compose.onNodeWithText("Turn on NoBonk?").assertIsDisplayed()
            assertStopped(scenario)
        }
    }
}
