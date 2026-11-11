package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.requireIsolatedEmulator
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual bitmap ingestion and the night-boost path, not just the policy formula. */
class LowLightPipelineRegressionTest {
    @Test fun repeatedDarkFramesKeepTheSameVerdictWithoutBlockingMoreNightScenes() = runBlocking {
        requireIsolatedEmulator()
        val engine = DetectionEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        val config = DetectionEngine.Config(1f, false, false, false, false, sessionToken = 1)
        try {
            // The covered-camera decision precedes inference and must not need a loaded model.
            repeat(3) { index ->
                val (image, closed) = grayFrame(16, 24)
                val result = engine.process(image, config)
                assertTrue(closed())
                assertFalse("Keep the shipped steady-state behavior for dim scenes, frame $index", result.cameraBlocked)
            }
            repeat(3) { index ->
                val (image, closed) = grayFrame(8, 12)
                val result = engine.process(image, config)
                assertTrue(closed())
                assertTrue("A genuinely dark and flat scene stays covered, frame $index", result.cameraBlocked)
            }
            // Structured night scene must remain usable, even after a boosted frame.
            repeat(3) { index ->
                val (image, closed) = grayFrame(0, 50)
                val result = engine.process(image, config)
                assertTrue(closed())
                assertFalse("Texture must not be mistaken for a covered lens, frame $index", result.cameraBlocked)
            }
        } finally { engine.close() }
    }

    @Test fun structuredNightFramesStillReachTheModelWithBoostAndRecoverInBrightLight() = runBlocking {
        requireIsolatedEmulator()
        val engine = DetectionEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        val config = DetectionEngine.Config(1f, false, false, false, false, sessionToken = 1)
        suspend fun frame(a: Int, b: Int): DetectionEngine.Result {
            val (image, closed) = grayFrame(a, b)
            return engine.process(image, config).also { assertTrue(closed()); assertFalse(it.cameraBlocked) }
        }
        try {
            engine.loadModel("yolo26n_416.onnx", 416, false)
            engine.warmUp()
            frame(0, 50)
            assertTrue(frame(0, 50).nightBoost)
            frame(120, 140)
            assertFalse("Boost clears after a bright frame", frame(120, 140).nightBoost)
        } finally { engine.close() }
    }
}
