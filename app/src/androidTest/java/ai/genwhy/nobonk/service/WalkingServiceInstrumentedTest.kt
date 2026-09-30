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
        listOf(Manifest.permission.CAMERA, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS).forEach {
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

}
