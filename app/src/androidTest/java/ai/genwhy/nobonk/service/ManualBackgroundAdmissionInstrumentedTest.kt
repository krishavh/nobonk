package ai.genwhy.nobonk.service

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ai.genwhy.nobonk.MainActivity
import ai.genwhy.nobonk.requireIsolatedEmulator
import ai.genwhy.nobonk.safety.SafetyNotice
import ai.genwhy.nobonk.safety.SessionState
import ai.genwhy.nobonk.viewmodel.DetectionViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real UI admission, production service and native model/camera; no harness or injected ack. */
class ManualBackgroundAdmissionInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun await(label: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            // State changes from UI actions and native callbacks need a Compose frame;
            // polling only the Android looper leaves the test clock's UI frozen.
            compose.mainClock.advanceTimeByFrame()
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $label; service failure=${SessionState.backgroundFailure}")
    }

    private fun viewModel(activity: MainActivity): DetectionViewModel =
        MainActivity::class.java.getDeclaredMethod("getViewModel").run {
            isAccessible = true
            invoke(activity) as DetectionViewModel
        }

    private class RearCameraProbe(private val manager: CameraManager) : AutoCloseable {
        private val rearId = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        @Volatile var available: Boolean? = null
        private val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) { if (cameraId == rearId) available = true }
            override fun onCameraUnavailable(cameraId: String) { if (cameraId == rearId) available = false }
        }
        init { manager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper())) }
        override fun close() { manager.unregisterAvailabilityCallback(callback) }
    }

    @Test fun explicitBackgroundAdmissionReturnsToSameActivityAndStopKeepsCameraOff() {
        requireIsolatedEmulator()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        context.stopService(Intent(context, DetectionService::class.java))
        await("previous service stopped") { !SessionState.gate.serviceActive }
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        val permissions = listOf(Manifest.permission.CAMERA) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        context.getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE).edit()
            .putInt(SafetyNotice.PREF_ACK_VERSION, SafetyNotice.VERSION)
            .putBoolean("cue_choice_done", true)
            .putString("accuracy_mode", "Y26N").commit()
        instrumentation.runOnMainSync {
            SessionState.backgroundStoppedByUser = false
            SessionState.backgroundFailure = null
            SessionState.gate.onLeftApp()
        }
        val camera = RearCameraProbe(context.getSystemService(CameraManager::class.java))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.onNodeWithText(SafetyNotice.REMINDER_OK_LABEL).performClick()
            lateinit var original: MainActivity
            lateinit var model: DetectionViewModel
            scenario.onActivity { original = it; model = viewModel(it) }
            await("foreground model and camera ready") { !model.isInitializing && camera.available == false }
            compose.onNodeWithText("Run in background").assertIsDisplayed().performClick()

            // Only the production ResultReceiver acknowledgement may background this task.
            await("accepted camera service and backgrounded Activity") {
                SessionState.gate.serviceActive && !SessionState.walkingSession &&
                    original.lifecycle.currentState == Lifecycle.State.CREATED &&
                    manager.activeNotifications.any { it.id == 1 }
            }
            assertTrue("Manual handoff preserves the user's running scan session", model.scanningEnabled)
            // Wait for a post-warmup service notification as well as actual hardware use, so
            // leftover foreground-camera teardown cannot masquerade as successful admission.
            await("production service has progressed past camera preparation") {
                camera.available == false && manager.activeNotifications.any {
                    it.id == 1 && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                        ?.let { text -> !text.startsWith("Preparing camera") } == true
                }
            }
            val posted = manager.activeNotifications.single { it.id == 1 }.notification
            // Send the exact posted content PendingIntent with SystemUI-equivalent launch
            // privilege. This test-only privilege is not part of production app admission.
            fun openPostedNotification(notification: android.app.Notification) {
                instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
                try {
                    val options = ActivityOptions.makeBasic().apply {
                        if (Build.VERSION.SDK_INT >= 34) {
                            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        }
                    }
                    notification.contentIntent.send(context, 0, null, null, null, null, options.toBundle())
                    await("notification returns to the same Activity") {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().singleOrNull() === original
                    }
                } finally {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                }
            }
            openPostedNotification(posted)
            scenario.onActivity { assertSame(original, it); assertSame(model, viewModel(it)) }
            await("return hands live camera back to the preview") {
                !SessionState.gate.serviceActive && camera.available == false && model.scanningEnabled &&
                    manager.activeNotifications.none { it.id == 1 }
            }
            compose.onNodeWithText("Run in background").assertIsDisplayed().performClick()
            await("second explicit background admission") {
                SessionState.gate.serviceActive && !SessionState.walkingSession &&
                    original.lifecycle.currentState == Lifecycle.State.CREATED &&
                    camera.available == false && manager.activeNotifications.any { it.id == 1 }
            }
            val secondPosted = manager.activeNotifications.single { it.id == 1 }.notification

            // Actual notification Stop terminates this session. Opening its old content
            // PendingIntent must now return to a stopped preview, unlike the live handoff.
            secondPosted.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
            await("notification Stop releases hardware") {
                !SessionState.gate.serviceActive && camera.available == true &&
                    manager.activeNotifications.none { it.id == 1 }
            }
            openPostedNotification(secondPosted)
            assertFalse(model.scanningEnabled)
            assertEquals(true, camera.available)
            compose.onNodeWithText(SafetyNotice.REMINDER_OK_LABEL).performClick()
            compose.onNodeWithText("Start scanning").assertIsDisplayed()
            assertFalse(model.scanningEnabled)
            // ActivityScenario loses its internal stage after the real task is moved back
            // and returned with REORDER_TO_FRONT. Exercise Android recreation directly.
            instrumentation.runOnMainSync { original.recreate() }
            lateinit var recreated: MainActivity
            await("recreated Activity resumed") {
                val current = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                if (current != null && current !== original) { recreated = current; true } else false
            }
            compose.onNodeWithText("Start scanning").assertIsDisplayed()
            instrumentation.runOnMainSync { assertFalse(viewModel(recreated).scanningEnabled) }
            await("Stop plus recreation keeps hardware and service off") {
                camera.available == true && !SessionState.gate.serviceActive &&
                    manager.activeNotifications.none { it.id == 1 }
            }
        } finally {
            context.stopService(Intent(context, DetectionService::class.java))
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().toList().forEach { it.finish() }
            }
            // A failed background launch can leave ActivityScenario between stages. Do not
            // hide the original failure behind its cleanup-state exception.
            runCatching { scenario.close() }
            await("production service and camera cleanup") { !SessionState.gate.serviceActive && camera.available == true }
            camera.close()
        }
    }
}
