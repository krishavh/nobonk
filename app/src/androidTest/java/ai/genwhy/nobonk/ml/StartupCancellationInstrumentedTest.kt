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

    private fun awaitCameraReady(model: DetectionViewModel) {
        val end = SystemClock.elapsedRealtime() + 10_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            instrumentation.runOnMainSync {
                assertNull(model.cameraError)
                ready = model.alertsReady
                if (!ready) model.processFrame(grayFrame().first)
            }
            // Leave Main free for native-result publication and lifecycle callbacks.
            if (!ready) SystemClock.sleep(150)
        }
        instrumentation.runOnMainSync {
            assertTrue("Real camera frames must reach readiness before replacement", model.alertsReady)
            assertTrue("The ready scan must have fresh results", model.hasFreshResults(SystemClock.elapsedRealtime()))
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
            instrumentation.runOnMainSync {
                assertFalse("Model warmup without camera frames is not live", model.alertsReady)
                model.setDetectEverything(true)
                model.setDetectEverything(false)
                assertFalse(model.isObjectDetectionEnabled)
                assertTrue(model.scanningEnabled)
                assertTrue(model.detections.isEmpty()); assertNull(model.lookUpLabel)
                assertFalse(model.isWallDetected); assertFalse(model.isGroundHazardDetected)
                model.stopScanning(); model.setDetectEverything(true); model.setDetectEverything(false)
                assertFalse("Changing scope cannot restart a stopped scan", model.scanningEnabled)
                model.startScanning()
            }


            val prefs = instrumentation.targetContext.getSharedPreferences("nobonk_execution", 0)
            val completedCache = prefs.all.toMap()
            assertTrue("A completed native selection was cached", completedCache.isNotEmpty())
            instrumentation.runOnMainSync {
                val readyStatus = model.initializationStatus
                model.stopScanning(); model.startScanning()
                assertFalse("An already ready model should be reused", model.isInitializing)
                assertEquals(readyStatus, model.initializationStatus)
                assertEquals(completedCache, prefs.all)
            }
            awaitCameraReady(model)
            instrumentation.runOnMainSync {
                assertTrue("Positive control: the old model has live camera results", model.alertsReady)
                replacement = if (model.accuracyMode == AccuracyMode.Y26N) AccuracyMode.Y26S else AccuracyMode.Y26N
                model.setAccuracyMode(replacement, instrumentation.targetContext)
                assertTrue(model.isInitializing)
                assertFalse("Model replacement must immediately clear old readiness", model.alertsReady)
                assertTrue("Model replacement must immediately clear old detections", model.detections.isEmpty())
                assertNull("Model replacement must immediately clear old labels", model.lookUpLabel)
                assertFalse("Model replacement must invalidate old camera results", model.hasFreshResults(SystemClock.elapsedRealtime()))
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
