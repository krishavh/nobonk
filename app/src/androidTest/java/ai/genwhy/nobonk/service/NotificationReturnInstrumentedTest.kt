package ai.genwhy.nobonk.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ai.genwhy.nobonk.MainActivity
import ai.genwhy.nobonk.requireIsolatedEmulator
import ai.genwhy.nobonk.safety.SafetyNotice
import ai.genwhy.nobonk.safety.SessionState
import ai.genwhy.nobonk.testing.WalkingServiceHarness
import ai.genwhy.nobonk.viewmodel.DetectionViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real posted notification PendingIntent, including collision with walking extras. */
class NotificationReturnInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            i.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $label")
    }

    private fun viewModel(activity: MainActivity): DetectionViewModel =
        MainActivity::class.java.getDeclaredMethod("getViewModel").run {
            isAccessible = true; invoke(activity) as DetectionViewModel
        }

    @Test fun postedReturnReusesActivityAndNeverReplaysWalkingPromptEvenAfterStop() {
        requireIsolatedEmulator()
        val context = i.targetContext
        val permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.ACTIVITY_RECOGNITION) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.forEach { i.uiAutomation.grantRuntimePermission(context.packageName, it) }
        context.getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE).edit()
            .putInt(SafetyNotice.PREF_ACK_VERSION, SafetyNotice.VERSION)
            .putBoolean("cue_choice_done", true).commit()
        i.runOnMainSync { SessionState.gate.onAcknowledged() }
        val initial = Intent(context, MainActivity::class.java)
            .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true)
        val scenario = ActivityScenario.launch<MainActivity>(initial)
        val manager = context.getSystemService(NotificationManager::class.java)
        var walkingIdentity: PendingIntent? = null
        try {
            compose.onNodeWithText("Not now").performClick()
            lateinit var original: MainActivity
            lateinit var originalModel: DetectionViewModel
            scenario.onActivity {
                original = it; originalModel = viewModel(it)
                assertFalse(originalModel.scanningEnabled)
                SessionState.gate.onAcknowledged()
                // Create the exact other Activity PendingIntent identity used by walking reminders.
                walkingIdentity = PendingIntent.getActivity(it, 2,
                    Intent(it, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(DetectionService.EXTRA_WALKING_REMINDER, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                it.startService(Intent(it, WalkingServiceHarness::class.java).apply {
                    action = DetectionService.ACTION_START
                    putExtra(DetectionService.EXTRA_WAIT_FOR_WALKING, true)
                })
            }
            await("real waiting service notification") {
                WalkingServiceHarness.current?.phase == ServiceLifecycle.Phase.WAITING_FOR_WALKING &&
                    manager.activeNotifications.any { it.id == 1 }
            }
            val posted = manager.activeNotifications.single { it.id == 1 }.notification
            val returnIntent = posted.contentIntent
            assertNotEquals("Return must not alias the walking prompt", walkingIdentity, returnIntent)
            fun assertSameScreen() {
                await("original Activity resumed") {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull() === original
                }
                scenario.onActivity {
                    assertSame(original, it)
                    assertSame(originalModel, viewModel(it))
                    assertFalse(viewModel(it).scanningEnabled)
                    assertFalse(it.intent.getBooleanExtra(DetectionService.EXTRA_WALKING_REMINDER, false))
                }
                compose.onNodeWithText("Turn on NoBonk?").assertDoesNotExist()
            }
            // Invoke the content intent Android actually received, not an independently rebuilt Intent.
            scenario.onActivity { returnIntent.send() }
            i.waitForIdleSync()
            assertSameScreen()
            posted.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
            await("Stop cleanup") { WalkingServiceHarness.current == null }
            scenario.onActivity { returnIntent.send() }
            i.waitForIdleSync()
            assertSameScreen()
        } finally {
            context.stopService(Intent(context, WalkingServiceHarness::class.java))
            await("fixture service cleanup") { WalkingServiceHarness.current == null }
            walkingIdentity?.cancel()
            manager.cancel(DetectionService.WALKING_NOTIFICATION_ID)
            scenario.close()
        }
    }
}
