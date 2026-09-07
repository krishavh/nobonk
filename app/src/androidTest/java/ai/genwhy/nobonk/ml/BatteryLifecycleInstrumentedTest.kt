package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.viewmodel.DetectionViewModel
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** System battery broadcasts, real ViewModel and model load, isolated to an emulator. */
class BatteryLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    @Test fun batteryChangesPauseRecoverAndRespectStopAcrossHiddenOwner() {
        assumeTrue("This test only overrides battery state on an emulator", Build.MODEL.contains("sdk_gphone"))
        val store = ViewModelStore()
        lateinit var model: DetectionViewModel
        lateinit var monitor: BatteryMonitor
        var cleanupMonitor: BatteryMonitor? = null
        try {
            shell("cmd battery set level 50")
            instrumentation.runOnMainSync {
                monitor = BatteryMonitor(instrumentation.targetContext).also { it.start(); it.start() }
                cleanupMonitor = monitor
                model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[DetectionViewModel::class.java]
                model.initialize(instrumentation.targetContext)
                model.setForegroundActive(true)
            }
            await("Initial sticky battery is consumed") { model.batteryLevel == 50 && monitor.level == 50 }
            await("Actual model finished loading") { !model.isInitializing }
            assertNull(model.cameraError)
            shell("cmd battery set level 9")
            await("Foreground and background readers receive low battery") { model.batteryLevel == 9 && monitor.level == 9 }
            assertTrue(model.scanningEnabled) // paused by power, user intent is retained
            assertTrue(model.detections.isEmpty())
            assertEquals(0f, model.fps)
            instrumentation.runOnMainSync { model.setForegroundActive(false) }
            shell("cmd battery set level 50")
            await("Battery recovery while hidden is observed") { model.batteryLevel == 50 }
            instrumentation.runOnMainSync { model.setForegroundActive(true); model.stopScanning() }
            shell("cmd battery set level 8")
            await("Second low-battery event arrives") { model.batteryLevel == 8 }
            shell("cmd battery set level 60")
            await("Charging event arrives after Stop") { model.batteryLevel == 60 }
            assertFalse("Charging must never undo Stop", model.scanningEnabled)
            instrumentation.runOnMainSync {
                model.setForegroundActive(false); model.setForegroundActive(true)
                assertFalse(model.scanningEnabled)
                monitor.close(); monitor.close()
                store.clear()
            }
            shell("cmd battery set level 70")
            SystemClock.sleep(250)
            assertEquals("Closed monitor receives no more broadcasts", 60, monitor.level)
            assertEquals("Cleared ViewModel receives no more broadcasts", 60, model.batteryLevel)
        } finally {
            instrumentation.runOnMainSync { cleanupMonitor?.close(); store.clear() }
            shell("cmd battery reset")
        }
    }
}
