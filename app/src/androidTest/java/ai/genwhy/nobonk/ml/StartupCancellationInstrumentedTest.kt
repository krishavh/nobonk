package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.viewmodel.AccuracyMode
import ai.genwhy.nobonk.viewmodel.DetectionViewModel
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real ViewModel/jobs/native models; no camera or physical-device state changes. */
class StartupCancellationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun awaitReady(model: DetectionViewModel, label: String) {
        val end = SystemClock.elapsedRealtime() + 45_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            instrumentation.runOnMainSync { ready = !model.isInitializing }
            if (!ready) SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync {
            assertTrue("Replacement startup must finish", ready)
            assertNull(model.cameraError)
            assertEquals("Ready — $label", model.initializationStatus)
            assertTrue(model.scanningEnabled)
        }
    }

    @Test fun immediateStopStartAndModelReplacementRecoverWithoutStaleLoadingState() {
        assumeTrue("Use the isolated emulator only", Build.MODEL.contains("sdk_gphone"))
        val store = ViewModelStore()
        lateinit var model: DetectionViewModel
        lateinit var replacement: AccuracyMode
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[DetectionViewModel::class.java]
                model.initialize(instrumentation.targetContext)
                model.setForegroundActive(true)
                model.stopScanning()
                assertFalse(model.scanningEnabled)
                assertFalse("Stop must dismiss cancelled warmup immediately", model.isInitializing)
                model.startScanning()
                assertTrue("Start owns a new initialization", model.isInitializing)
            }
            awaitReady(model, model.accuracyMode.label)

            val prefs = instrumentation.targetContext.getSharedPreferences("nobonk_execution", 0)
            val completedCache = prefs.all.toMap()
            assertTrue("A completed native selection was cached", completedCache.isNotEmpty())
            instrumentation.runOnMainSync {
                val readyStatus = model.initializationStatus
                model.stopScanning(); model.startScanning()
                assertFalse("An already ready model should be reused", model.isInitializing)
                assertEquals(readyStatus, model.initializationStatus)
                assertEquals(completedCache, prefs.all)

                replacement = if (model.accuracyMode == AccuracyMode.Y26N) AccuracyMode.Y26S else AccuracyMode.Y26N
                model.setAccuracyMode(replacement, instrumentation.targetContext)
                assertTrue(model.isInitializing)
                model.stopScanning(); model.startScanning()
                assertTrue("Cancelled replacement must start a new owned job", model.isInitializing)
            }
            awaitReady(model, replacement.label)
            instrumentation.runOnMainSync {
                model.stopScanning()
                assertFalse(model.scanningEnabled)
                assertFalse(model.isInitializing)
                assertTrue(model.detections.isEmpty())
                model.setForegroundActive(false)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
