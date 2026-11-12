package ai.genwhy.nobonk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ResultReceiver
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
import java.lang.ref.WeakReference

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

    @Test fun recreationPreservesNavigationAndWalkingExplanationWithoutRestartingCamera() {
        prepare()
        val intent = Intent(i.targetContext, MainActivity::class.java)
            .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithText("Not now").performClick()
            scenario.onActivity {
                setActivityState(it, "setShowHistory", Boolean::class.javaPrimitiveType!!, true)
                setActivityState(it, "setShowLicenses", Boolean::class.javaPrimitiveType!!, true)
                setActivityState(it, "setWalkingStatus", String::class.java, "Motion permission was not granted.")
            }
            compose.onNodeWithText("About NoBonk").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("About NoBonk").assertIsDisplayed()
            scenario.onActivity {
                assertEquals(true, activityState(it, "getShowHistory"))
                assertEquals(true, activityState(it, "getShowLicenses"))
                assertEquals("Motion permission was not granted.", activityState(it, "getWalkingStatus"))
            }
            assertStopped(scenario)
        }
    }

    @Test fun recreationDetachesServiceAcknowledgementFromOldActivity() {
        prepare()
        val intent = Intent(i.targetContext, MainActivity::class.java)
            .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var receiver: ResultReceiver
            lateinit var owner: WeakReference<*>
            scenario.onActivity { activity ->
                val receiverClass = MainActivity::class.java.declaredClasses.single { it.simpleName == "SessionStartReceiver" }
                receiver = receiverClass.getDeclaredConstructor(MainActivity::class.java, Long::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }.newInstance(activity, 41L) as ResultReceiver
                owner = receiverClass.getDeclaredField("owner").apply { isAccessible = true }.get(receiver) as WeakReference<*>
                MainActivity::class.java.getDeclaredField("sessionStartReceiver").apply { isAccessible = true }.set(activity, receiver)
                MainActivity::class.java.getDeclaredField("sessionStartGeneration").apply { isAccessible = true }.setLong(activity, 41L)
                MainActivity::class.java.getDeclaredField("sessionStartPending").apply { isAccessible = true }.setBoolean(activity, true)
                assertSame(activity, owner.get())
            }
            scenario.recreate()
            assertNull(owner.get())
            receiver.send(1, android.os.Bundle.EMPTY)
            i.waitForIdleSync()
            compose.onNodeWithText("Turn on NoBonk?").assertIsDisplayed()
            scenario.onActivity {
                assertEquals("Background setup was interrupted. Start scanning or arm another reminder when ready.", activityState(it, "getWalkingStatus"))
                assertFalse(MainActivity::class.java.getDeclaredField("sessionStartPending").apply { isAccessible = true }.getBoolean(it))
            }
            assertStopped(scenario)
        }
    }

    @Test fun rejectedManualStartShowsServiceFailureAndStaleResultCannotCancelLaterStart() {
        prepare()
        val intent = Intent(i.targetContext, MainActivity::class.java)
            .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val generation = MainActivity::class.java.getDeclaredField("sessionStartGeneration").apply { isAccessible = true }
                val pending = MainActivity::class.java.getDeclaredField("sessionStartPending").apply { isAccessible = true }
                val result = MainActivity::class.java.getDeclaredMethod("onSessionStartResult", Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
                generation.setLong(activity, 41L)
                pending.setBoolean(activity, true)
                SessionState.backgroundFailure = "Camera access changed during setup."
                result.invoke(activity, 41L, 0)
                val getter = MainActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }
                assertEquals("Camera access changed during setup.", (getter.invoke(activity) as DetectionViewModel).cameraError)
                assertFalse(pending.getBoolean(activity))
                generation.setLong(activity, 43L)
                pending.setBoolean(activity, true)
                result.invoke(activity, 41L, 1)
                assertTrue(pending.getBoolean(activity))
                assertEquals(43L, generation.getLong(activity))
                pending.setBoolean(activity, false)
            }
            assertStopped(scenario)
        }
    }

    private fun setActivityState(activity: MainActivity, setter: String, type: Class<*>, value: Any) {
        MainActivity::class.java.getDeclaredMethod(setter, type).apply { isAccessible = true }.invoke(activity, value)
    }

    private fun activityState(activity: MainActivity, getter: String): Any? =
        MainActivity::class.java.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
}
