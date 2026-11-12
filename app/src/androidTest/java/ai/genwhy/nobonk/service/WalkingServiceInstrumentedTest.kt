package ai.genwhy.nobonk.service

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.motion.WalkingSessionPolicy.Transition
import ai.genwhy.nobonk.testing.WalkingHarnessActivity
import ai.genwhy.nobonk.testing.WalkingServiceHarness
import org.junit.Assert.*
import ai.genwhy.nobonk.requireIsolatedEmulator
import org.junit.Test

/** Synthetic motion only. Real LifecycleService, CameraX, ONNX, notification Stop, and camera teardown. */
class WalkingServiceInstrumentedTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, timeout: Long = 60_000, onMain: Boolean = true, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            if (onMain) i.runOnMainSync { ready = condition() } else ready = condition()
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $label")
    }
    private fun grant() {
        requireIsolatedEmulator()
        i.uiAutomation.executeShellCommand("appops set ${i.targetContext.packageName} SYSTEM_ALERT_WINDOW allow").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        (listOf(Manifest.permission.CAMERA, Manifest.permission.ACTIVITY_RECOGNITION) +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).forEach {
            i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, it)
        }
    }
    private class CameraProbe(manager: CameraManager) : AutoCloseable {
        private val cameraManager = manager
        private val rear = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        @Volatile var available: Boolean? = null
        private val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) { if (id == rear) available = true }
            override fun onCameraUnavailable(id: String) { if (id == rear) available = false }
        }
        init { manager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper())) }
        override fun close() { cameraManager.unregisterAvailabilityCallback(callback) }
    }
    private fun stopNotification() {
        val manager = i.targetContext.getSystemService(NotificationManager::class.java)
        await("service Stop notification posted") {
            manager.activeNotifications.any { it.id == 1 && it.notification.actions?.any { action -> action.title.toString() == "Stop" } == true }
        }
        val notification = manager.activeNotifications.single { it.id == 1 }.notification
        notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
    }
    @Test fun walkingAsksOnceAndNeverOpensCameraEvenAfterMoreMotion() {
        grant()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        val camera = CameraProbe(i.targetContext.getSystemService(CameraManager::class.java))
        val nm = i.targetContext.getSystemService(NotificationManager::class.java)
        try {
            scenario.onActivity { it.startWalking() }
            await("armed") { WalkingServiceHarness.current?.phase == ServiceLifecycle.Phase.WAITING_FOR_WALKING }
            val service = WalkingServiceHarness.current!!
            await("camera available while waiting") { camera.available == true }
            i.runOnMainSync { service.emit(Transition.START) }
            await("reminder service ends") { WalkingServiceHarness.current == null }
            await("walking reminder posted") { nm.activeNotifications.any { it.id == DetectionService.WALKING_NOTIFICATION_ID } }
            val reminder = nm.activeNotifications.single { it.id == DetectionService.WALKING_NOTIFICATION_ID }
            val posted = reminder.postTime
            i.runOnMainSync {
                assertNull(service.field("engine")); assertNull(service.field("analysis"))
                assertFalse(service.monitorActive)
                service.emit(Transition.PAUSE); repeat(10) { service.emit(Transition.START) }
            }
            assertEquals(posted, nm.activeNotifications.single { it.id == DetectionService.WALKING_NOTIFICATION_ID }.postTime)
            assertEquals(true, camera.available)
            await("waiting notification removed") { nm.activeNotifications.none { it.id == 1 } }
            reminder.notification.actions.single { it.title.toString() == "Not now" }.actionIntent.send()
            await("dismiss removes reminder") { nm.activeNotifications.none { it.id == DetectionService.WALKING_NOTIFICATION_ID } }
        } finally {
            nm.cancel(DetectionService.WALKING_NOTIFICATION_ID)
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("fixture service cleanup") { WalkingServiceHarness.current == null }
            camera.close(); scenario.close()
        }
    }

    @Test fun stopWhileWaitingSuppressesReminderAndCamera() {
        grant()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        try {
            scenario.onActivity { it.startWalking() }
            await("armed") { WalkingServiceHarness.current?.phase == ServiceLifecycle.Phase.WAITING_FOR_WALKING }
            val service = WalkingServiceHarness.current!!
            stopNotification()
            await("stopped") { WalkingServiceHarness.current == null }
            i.runOnMainSync { service.emit(Transition.START); assertNull(service.field("engine")); assertNull(service.field("analysis")) }
            await("notifications removed") { i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id in 1..2 } }
        } finally { scenario.close() }
    }

    @Test fun stopDuringWarmupCannotRecreateEngineCameraOrNotification() {
        grant()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        try {
            scenario.onActivity { it.startWalking(waitForWalking = false) }
            await("manual service started") { WalkingServiceHarness.current != null }
            val service = WalkingServiceHarness.current!!
            stopNotification()
            await("warmup stopped") { WalkingServiceHarness.current == null }
            // Let an already-running native load unwind, then check for resurrection.
            SystemClock.sleep(3_000)
            i.runOnMainSync {
                assertEquals(ServiceLifecycle.Phase.STOPPED, service.phase)
                assertNull(service.field("engine")); assertNull(service.field("analysis")); assertFalse(service.monitorActive)
            }
            await("service notification removed") { i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 1 } }
        } finally {
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("fixture service cleanup") { WalkingServiceHarness.current == null }
            scenario.close()
        }
    }
    @Test fun notificationStopDuringActiveCameraReleasesHardwareAndSilencesEngine() {
        grant()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        val camera = CameraProbe(i.targetContext.getSystemService(CameraManager::class.java))
        try {
            scenario.onActivity { it.startWalking(waitForWalking = false) }
            await("manual service started") { WalkingServiceHarness.current != null }
            val service = WalkingServiceHarness.current!!
            await("active native camera") { service.phase == ServiceLifecycle.Phase.RUNNING && service.field("latestResult") != null }
            await("camera in use") { camera.available == false }
            scenario.onActivity { it.moveTaskToBack(true) }
            await("harness backgrounded", onMain = false) { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
            assertEquals(ServiceLifecycle.Phase.RUNNING, service.phase)
            lateinit var engine: ai.genwhy.nobonk.ml.DetectionEngine
            i.runOnMainSync { engine = service.field("engine") as ai.genwhy.nobonk.ml.DetectionEngine }
            stopNotification()
            await("stopped service") { WalkingServiceHarness.current == null }
            await("camera released after Stop") { camera.available == true }
            assertTrue(engine.halted)
            assertFalse(service.monitorActive)
            await("service notification removed") { i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 1 } }
        } finally {
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("fixture service cleanup") { WalkingServiceHarness.current == null }
            camera.close(); scenario.close()
        }
    }

    @Test fun expiredWaitRejectsWalkingAndDestroysSessionWithoutCamera() {
        grant()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        try {
            scenario.onActivity { it.startWalking() }
            await("armed") { WalkingServiceHarness.current?.phase == ServiceLifecycle.Phase.WAITING_FOR_WALKING }
            val service = WalkingServiceHarness.current!!
            i.runOnMainSync {
                DetectionService::class.java.getDeclaredField("walkingDeadlineMs").apply { isAccessible = true }
                    .setLong(service, SystemClock.elapsedRealtime() - 1)
                service.emit(Transition.START)
                assertNull(service.field("analysis")); assertNull(service.field("engine"))
            }
            await("expiry destroys session") { WalkingServiceHarness.current == null }
            assertFalse(service.monitorActive)
            await("notifications removed") { i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id in 1..2 } }
        } finally {
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("fixture service cleanup") { WalkingServiceHarness.current == null }
            scenario.close()
        }
    }

    @Test fun returnControlCanMoveAndStopRemovesIt() {
        grant()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        try {
            scenario.onActivity { it.startWalking(waitForWalking = false) }
            await("return control visible") { WalkingServiceHarness.current?.field("returnView") != null }
            val service = WalkingServiceHarness.current!!
            i.runOnMainSync {
                val view = service.field("returnView") as android.view.View
                val original = view.layoutParams as android.view.WindowManager.LayoutParams
                val beforeX = original.x; val beforeY = original.y
                val t = SystemClock.uptimeMillis()
                fun touch(action: Int, x: Float, y: Float, offset: Long) {
                    val event = android.view.MotionEvent.obtain(t, t + offset, action, x, y, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN, 20f, 20f, 0)
                touch(android.view.MotionEvent.ACTION_MOVE, -200f, 240f, 100)
                touch(android.view.MotionEvent.ACTION_UP, -200f, 240f, 200)
                val moved = view.layoutParams as android.view.WindowManager.LayoutParams
                assertTrue("Drag should reposition the window", moved.x != beforeX || moved.y != beforeY)
                assertTrue(moved.x >= 0 && moved.y >= 0)
                assertTrue(service.getSharedPreferences("nobonk_prefs", 0).contains("return_control_x"))
            }
            stopNotification()
            await("stopped") { WalkingServiceHarness.current == null }
            assertNull(service.field("returnView"))
        } finally {
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("cleanup") { WalkingServiceHarness.current == null }
            scenario.close()
        }
    }

    @Test fun edgeTrailCanBeDisabledWithoutRemovingReturnControlOrScan() {
        grant()
        val prefs = i.targetContext.getSharedPreferences("nobonk_prefs", 0)
        prefs.edit().putBoolean("edge_trail_enabled", false).commit()
        val scenario = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        try {
            scenario.onActivity { it.startWalking(waitForWalking = false) }
            await("background scan") { WalkingServiceHarness.current?.phase == ServiceLifecycle.Phase.RUNNING }
            val service = WalkingServiceHarness.current!!
            i.runOnMainSync { assertNull(service.field("edge")); assertNotNull(service.field("returnView")); assertNotNull(service.field("analysis")) }
            stopNotification()
            await("stopped") { WalkingServiceHarness.current == null }
        } finally {
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("cleanup") { WalkingServiceHarness.current == null }
            prefs.edit().remove("edge_trail_enabled").remove("return_control_x").remove("return_control_y").commit()
            scenario.close()
        }
    }

    @Test fun lowerReturnControlStaysBehindKeyboardWithoutTakingTypingFocus() {
        grant()
        val prefs = i.targetContext.getSharedPreferences("nobonk_prefs", 0)
        prefs.edit().putFloat("return_control_x", 0.5f).putFloat("return_control_y", 1f).commit()
        val automation = i.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8).trim()
        }
        fun returnWindow(w: android.view.accessibility.AccessibilityWindowInfo): Boolean {
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): Boolean {
                if (node == null || depth > 5) return false
                if (node.text?.toString() == "Open NoBonk" || node.contentDescription?.toString()?.startsWith("Open NoBonk controls") == true) return true
                return (0 until node.childCount).any { contains(node.getChild(it), depth + 1) }
            }
            return w.title?.toString() == "NoBonk return control" || contains(w.root)
        }
        val oldImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        val scanner = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        var typing: ActivityScenario<ai.genwhy.nobonk.testing.TypingHarnessActivity>? = null
        try {
            scanner.onActivity { it.startWalking(waitForWalking = false) }
            await("return control") { WalkingServiceHarness.current?.field("returnView") != null }
            await("return accessibility window", timeout = 10_000, onMain = false) {
                automation.windows.any { returnWindow(it) }
            }
            typing = ActivityScenario.launch(ai.genwhy.nobonk.testing.TypingHarnessActivity::class.java)
            typing.onActivity { it.showKeyboard() }
            await("editor keyboard", onMain = false) {
                automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            val windows = automation.windows
            val ime = windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val control = windows.find { returnWindow(it) }
            assertTrue("Return control must be below or fully hidden by the keyboard", control == null || control.layer < ime.layer)
            typing.onActivity { assertTrue("Editor retains focus", it.editor.hasFocus()); assertTrue(it.editor.hasWindowFocus()) }
            i.runOnMainSync {
                val view = WalkingServiceHarness.current!!.field("returnView") as android.view.View
                assertTrue("Overlay remains attached while keyboard covers it", view.isAttachedToWindow)
            }
            shell("screencap -p /data/local/tmp/nobonk-keyboard-overlay.png")
            typing.onActivity { it.hideKeyboard() }
            await("keyboard dismissed", onMain = false) {
                automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            await("return control restored", onMain = false) {
                automation.windows.any { returnWindow(it) }
            }
            stopNotification()
            await("stopped") { WalkingServiceHarness.current == null }
        } finally {
            i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
            await("cleanup") { WalkingServiceHarness.current == null }
            typing?.close(); scanner.close()
            prefs.edit().remove("return_control_x").remove("return_control_y").commit()
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            if (oldImeSetting == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $oldImeSetting")
        }
    }

}
