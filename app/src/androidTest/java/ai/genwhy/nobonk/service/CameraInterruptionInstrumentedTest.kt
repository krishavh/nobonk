package ai.genwhy.nobonk.service

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.requireIsolatedEmulator
import ai.genwhy.nobonk.testing.CompetingCameraActivity
import ai.genwhy.nobonk.testing.WalkingHarnessActivity
import ai.genwhy.nobonk.testing.WalkingServiceHarness
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Camera2 ownership contention; skips explicitly if this device cannot reproduce preemption. */
class CameraInterruptionInstrumentedTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, timeout: Long = 60_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false
            i.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $label")
    }
    private fun grant() {
        requireIsolatedEmulator()
        i.uiAutomation.executeShellCommand("appops set ${i.targetContext.packageName} SYSTEM_ALERT_WINDOW allow").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        (listOf(Manifest.permission.CAMERA) +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).forEach {
            i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, it)
        }
    }
    private fun availability(service: WalkingServiceHarness) = service.field("cameraAvailability") as CameraAvailability
    private fun stopNotification() {
        val nm = i.targetContext.getSystemService(NotificationManager::class.java)
        val notice = nm.activeNotifications.single { it.id == 1 }.notification
        notice.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
    }
    private fun start(scenario: ActivityScenario<WalkingHarnessActivity>): WalkingServiceHarness {
        scenario.onActivity { it.startWalking(waitForWalking = false) }
        await("real camera frames received") {
            WalkingServiceHarness.current?.let { availability(it).state() == CameraAvailability.State.OPEN && it.field("latestResult") != null } == true
        }
        return WalkingServiceHarness.current!!
    }
    private fun compete(): ActivityScenario<CompetingCameraActivity> {
        val scenario = ActivityScenario.launch(CompetingCameraActivity::class.java)
        try {
            await("competitor opens or reports camera ownership refusal", 15_000) {
                CompetingCameraActivity.current?.let { it.opened || it.failure != null } == true
            }
        } catch (failure: Throwable) { scenario.close(); throw failure }
        return scenario
    }
    private fun requireHeldCompetition() {
        val competitor = CompetingCameraActivity.current
        assumeTrue("Real Camera2 preemption unavailable: ${competitor?.failure ?: "competitor did not hold camera"}", competitor?.opened == true)
    }
    private fun cleanup(service: WalkingServiceHarness?) {
        i.targetContext.stopService(Intent(i.targetContext, WalkingServiceHarness::class.java))
        await("service fixture cleanup") { WalkingServiceHarness.current == null }
        if (service != null) i.runOnMainSync {
            assertNull(service.field("engine")); assertNull(service.field("analysis"))
            assertNull(service.field("returnView")); assertNull(service.field("hudView"))
        }
    }

    @Test fun realCameraCompetitionClearsResultsAndStopCannotBeReversedByCameraReturn() {
        grant()
        val owner = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        var rival: ActivityScenario<CompetingCameraActivity>? = null
        var service: WalkingServiceHarness? = null
        try {
            val running = start(owner); service = running
            val oldToken = availability(running).admitFrame()!!
            rival = compete(); requireHeldCompetition()
            // Less than the five-second frame watchdog: this must be CameraX-state driven.
            await("immediate interruption state", 1_500) {
                availability(running).state() in setOf(CameraAvailability.State.INTERRUPTED, CameraAvailability.State.FAILED)
            }
            requireHeldCompetition()
            i.runOnMainSync {
                assertNull("Old detections cannot remain visible", running.field("latestResult"))
                assertFalse("The old frame cannot emit cues or publish", availability(running).mayPublish(oldToken))
                val message = running.field("hudMessage") as String?
                assertTrue(message?.contains("SCANNING PAUSED") == true || message?.contains("CAMERA UNAVAILABLE") == true)
                val edge = running.field("edge")
                if (edge != null) assertEquals(true, edge.javaClass.getDeclaredField("blocked").run { isAccessible = true; get(edge) })
            }
            stopNotification()
            await("Stop tears service down") { WalkingServiceHarness.current == null }
            rival.close(); rival = null
            i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals(CameraAvailability.State.STOPPED, availability(running).state())
                assertNull(running.field("latestResult")); assertNull(running.field("analysis"))
                assertNull(running.field("returnView")); assertNull(running.field("hudView"))
            }
            assertTrue(i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 1 })
        } finally { rival?.close(); cleanup(service); owner.close() }
    }

    @Test fun recoverableCompetitionRequiresFreshFramesBeforeScanningReturns() {
        grant()
        val owner = ActivityScenario.launch(WalkingHarnessActivity::class.java)
        var rival: ActivityScenario<CompetingCameraActivity>? = null
        var service: WalkingServiceHarness? = null
        try {
            val running = start(owner); service = running
            val oldToken = availability(running).admitFrame()!!
            rival = compete(); requireHeldCompetition()
            await("camera reports interruption", 1_500) { availability(running).state() != CameraAvailability.State.OPEN }
            assumeTrue("CameraX classified this device's interruption as critical; recovery is intentionally prohibited",
                availability(running).state() == CameraAvailability.State.INTERRUPTED)
            i.runOnMainSync { assertNull(running.field("latestResult")); assertFalse(availability(running).mayPublish(oldToken)) }
            val releasedAt = SystemClock.elapsedRealtime()
            rival.close(); rival = null
            await("CameraX reopens and receives fresh results") {
                // OPEN only means the device reopened; a slow first inference can still
                // publish an expired result. Require an actually fresh post-release frame.
                val status = running.field("scanStatus") as BackgroundScanStatus
                val capturedAt = status.javaClass.getDeclaredField("frameAt").run {
                    isAccessible = true; get(status) as Long?
                }
                val now = SystemClock.elapsedRealtime()
                val state = status.state(now)
                availability(running).state() == CameraAvailability.State.OPEN &&
                    running.field("latestResult") != null && capturedAt != null &&
                    capturedAt >= releasedAt && BackgroundScanStatus.isFresh(capturedAt, now) &&
                    (state == BackgroundScanStatus.State.SCANNING || state == BackgroundScanStatus.State.COVERED || state == BackgroundScanStatus.State.WAITING)
            }
            i.runOnMainSync {
                assertFalse("Old in-flight generation remains invalid after reopening", availability(running).mayPublish(oldToken))
                val status = running.field("scanStatus") as BackgroundScanStatus
                val capturedAt = status.javaClass.getDeclaredField("frameAt").run { isAccessible = true; get(status) as Long }
                assertTrue("Only a post-release frame may restore results", capturedAt >= releasedAt)
                assertTrue("Reopened camera must provide a fresh frame", BackgroundScanStatus.isFresh(capturedAt, SystemClock.elapsedRealtime()))
                val state = status.state(SystemClock.elapsedRealtime())
                assertTrue(state == BackgroundScanStatus.State.SCANNING || state == BackgroundScanStatus.State.COVERED || state == BackgroundScanStatus.State.WAITING)
            }
            stopNotification()
            await("Stop after recovery") { WalkingServiceHarness.current == null }
        } finally { rival?.close(); cleanup(service); owner.close() }
    }
}
