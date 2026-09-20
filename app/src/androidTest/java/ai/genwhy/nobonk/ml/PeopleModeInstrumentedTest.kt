package ai.genwhy.nobonk.ml

import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.model.AlertLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import ai.genwhy.nobonk.requireIsolatedEmulator
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Shared RGBA camera fixture; only the native frame path consumes it. */
internal fun grayFrame(level: Int = 127): Pair<ImageProxy, () -> Boolean> {
    val size = 128
    val bytes = ByteBuffer.allocateDirect(size * size * 4)
    repeat(size * size) { bytes.put(level.toByte()); bytes.put(level.toByte()); bytes.put(level.toByte()); bytes.put(255.toByte()) }
    bytes.rewind()
    var closed = false
    val plane = Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader,
        arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ -> when (method.name) {
            "getBuffer" -> bytes; "getRowStride" -> size * 4; "getPixelStride" -> 4
            else -> error(method.name)
        } } as ImageProxy.PlaneProxy
    val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader,
        arrayOf(ImageInfo::class.java)) { _, method, _ -> when (method.name) {
            "getRotationDegrees" -> 0; "getTimestamp" -> 0L
            "getSensorToBufferTransformMatrix" -> Matrix(); "getTagBundle" -> TagBundle.emptyBundle()
            else -> error(method.name)
        } } as ImageInfo
    val frame = Proxy.newProxyInstance(ImageProxy::class.java.classLoader,
        arrayOf(ImageProxy::class.java)) { _, method, _ -> when (method.name) {
            "getWidth", "getHeight" -> size; "getCropRect" -> Rect(0, 0, size, size)
            "getPlanes" -> arrayOf(plane); "getImageInfo" -> info; "getFormat" -> 1
            "close" -> { check(!closed); closed = true; null }
            else -> error(method.name)
        } } as ImageProxy
    return frame to { closed }
}

/** Real RGBA ingestion, shipped ONNX model, environment heuristic, session reset and result wiring. */
class PeopleModeInstrumentedTest {
    @Test fun peopleCannotLeakRealWallHeuristicAndRestartMustPrepareAgain() = runBlocking {
        requireIsolatedEmulator()
        val engine = DetectionEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        fun config(everything: Boolean, token: Int = 1) = DetectionEngine.Config(
            1f, everything, soundEnabled = false, hapticsEnabled = false, voiceEnabled = false, sessionToken = token)
        suspend fun frame(cfg: DetectionEngine.Config, gray: Int = 127): DetectionEngine.Result {
            val (image, closed) = grayFrame(gray)
            return engine.process(image, cfg).also { assertTrue("ImageProxy must close", closed()) }
        }
        try {
            engine.loadModel("yolo26n_416.onnx", 416, false)
            engine.warmUp()
            var result = frame(config(true))
            assertFalse(result.alertsReady)
            assertFalse(result.wallDetected)
            repeat(3) { delay(350); result = frame(config(true)) }
            assertTrue(result.alertsReady)
            assertTrue("Positive control: real uniform-wall heuristic must activate", result.wallDetected)
            result = frame(config(false)) // scope alone must invalidate persisted wall state
            assertFalse(result.alertsReady)
            repeat(3) { delay(350); result = frame(config(false)) }
            assertTrue(result.alertsReady)
            assertFalse(result.wallDetected); assertFalse(result.groundHazard)
            assertTrue(result.detections.all { it.classId == 0 && it.className == "person" })
            assertFalse(result.hudMessage.orEmpty().contains("OBSTACLE"))
            assertFalse(result.hudMessage.orEmpty().contains("STEP"))
            result = frame(config(false, 2))
            assertFalse(result.alertsReady); assertEquals(AlertLevel.NONE, result.highestAlert)
            assertNull(result.lookUpLabel)
            result = frame(config(false, 2), 0)
            assertTrue(result.cameraBlocked); assertFalse(result.alertsReady)
        } finally { engine.close() }
    }
}
