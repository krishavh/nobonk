package ai.genwhy.nobonk.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.requireIsolatedEmulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Opt-in private replay; no images are packaged or checked into source control.
 * Runner argument: privateReplay=true. Stage the five hash-verified fixtures in the target app's
 * files/private-replay directory (or external-files/private-replay on older test setups).
 * Results stay in target files/private-replay-results.json.
 *
 * Provenance: four retained NoBonk phone-recording pavement crops at 6/9/12/15 seconds, originally
 * prepared in release-handoff/validation/outdoor-goal-20260913/camera-regions. They exclude app UI
 * with crop (0,360,576,800). bus.jpg is the previously retained official Ultralytics person control,
 * not a user photograph. Compression and cropping change the original camera input/framing.
 *
 * Exercises the current Android RGBA conversion, shipped models, decoder, scope, readiness and
 * result path. Repeated still frames test temporal confirmation, not real movement. Sensors and
 * physical cues are intentionally inactive. This is not an outdoor accuracy, distance, camera
 * calibration, hardware throughput or background-service integration benchmark.
 */
class PrivateSceneReplayInstrumentedTest {
    private val fixtures = linkedMapOf(
        "frame-006.png" to "4a454e4c8457c62e42742e0e7db388148c1093b1c394840c1f0bc898dd76f3a1",
        "frame-009.png" to "3f02443b8d937d172d6dd21d1f2222324cae591a846878cb17490c9950a9a058",
        "frame-012.png" to "57b0a1140f530547c5a78cbe8a785df69ee13029293d69c25fc188edc87fd112",
        "frame-015.png" to "a495318ccaaaeccea634b42a77ac42706f8ea2464adb488aec7f7c302dbb06d8",
        "bus.jpg" to "c02019c4979c191eb739ddd944445ef408dad5679acab6fd520ef9d434bfbc63"
    )

    @Test fun retainedPavementStaysQuietForPeopleAndPersonControlStillDetects() = runBlocking<Unit> {
        assumeTrue("Private fixtures require explicit opt-in", InstrumentationRegistry.getArguments().getString("privateReplay") == "true")
        requireIsolatedEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = File(context.filesDir, "private-replay")
        val directory = if (internal.isDirectory) internal else File(requireNotNull(context.getExternalFilesDir(null)), "private-replay")
        val rows = JSONArray()
        val report = JSONObject().put("completed", false).put("cases", rows)
            .put("scope", "Private compressed still-scene replay; no outdoor accuracy or physical-device claim")
        var token = 100
        try {
            fixtures.forEach { (name, expected) ->
                val file = File(directory, name)
                assertTrue("Missing private fixture: $name", file.isFile)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                assertEquals("Unexpected fixture content: $name", expected, digest.digest().joinToString("") { "%02x".format(it) })
            }
            for (model in listOf("yolo26n_416.onnx", "yolo26s_416.onnx")) {
                val engine = DetectionEngine(context)
                try {
                    engine.loadModel(model, 416, false)
                    engine.warmUp()
                    for ((name, hash) in fixtures) {
                        val bitmap = requireNotNull(BitmapFactory.decodeFile(File(directory, name).absolutePath)) { "Cannot decode $name" }
                        try {
                            for (everything in listOf(true, false)) {
                                val cfg = DetectionEngine.Config(1f, everything, soundEnabled = false,
                                    hapticsEnabled = false, voiceEnabled = false, sessionToken = ++token)
                                val frames = JSONArray()
                                val row = JSONObject().put("model", model).put("provider", engine.executionProvider)
                                    .put("fixture", name).put("sha256", hash).put("everything", everything).put("frames", frames)
                                rows.put(row)
                                var result: DetectionEngine.Result? = null
                                repeat(4) { index ->
                                    if (index > 0) delay(350)
                                    val (image, wasClosed) = frame(bitmap)
                                    val started = SystemClock.elapsedRealtime()
                                    val current = try { engine.process(image, cfg) }
                                    finally { assertTrue("ImageProxy must close: $name", wasClosed()) }
                                    result = current
                                    val detections = JSONArray()
                                    current.detections.forEach { detection ->
                                        detections.put(JSONObject().put("label", detection.className).put("classId", detection.classId)
                                            .put("confidence", detection.confidence.toDouble()).put("alert", detection.alertLevel.name))
                                    }
                                    frames.put(JSONObject().put("index", index).put("elapsedRealtimeMs", started)
                                        .put("inferMs", current.inferMs).put("ready", current.alertsReady)
                                        .put("cameraBlocked", current.cameraBlocked).put("lowLight", current.lowLight)
                                        .put("angleQuality", current.angleQuality.name)
                                        .put("wall", current.wallDetected).put("ground", current.groundHazard)
                                        .put("alert", current.highestAlert.name).put("detections", detections)
                                        .put("hud", current.hudMessage ?: JSONObject.NULL))
                                    if (index == 0) assertFalse("New session must prepare: $name", current.alertsReady)
                                    if (!everything) {
                                        assertFalse("People scope must suppress wall hints: $name", current.wallDetected)
                                        assertFalse("People scope must suppress ground hints: $name", current.groundHazard)
                                        assertTrue("People scope cannot expose other classes: $name", current.detections.all { it.classId == 0 && it.className == "person" })
                                        if (name != "bus.jpg") {
                                            assertTrue("No people in labeled pavement fixture: $name", current.detections.isEmpty())
                                            assertEquals("Pavement must not alert in People: $name", AlertLevel.NONE, current.highestAlert)
                                            assertNull("Pavement must not retain a LOOK UP label: $name", current.lookUpLabel)
                                        }
                                    }
                                }
                                if (name == "bus.jpg") {
                                    assertTrue("Positive control must complete actual-frame readiness", result!!.alertsReady)
                                    assertTrue("Positive control must still detect a person in $model/$everything",
                                        result!!.detections.any { it.classId == 0 && it.className == "person" })
                                    // No assertion on estimated distance/alert severity: this image is not calibrated.
                                }
                                // Pale/overexposed pavement may deliberately fail frame-readiness checks.
                                // Its per-frame status is recorded; only the person control must become ready.
                            }
                        } finally { bitmap.recycle() }
                    }
                } finally { engine.close() }
            }
            report.put("completed", true)
        } catch (failure: Throwable) {
            report.put("failure", failure.toString())
            throw failure
        } finally {
            File(context.filesDir, "private-replay-results.json").writeText(report.toString(2))
            // Gradle can uninstall the disposable app after the run. Preserve bounded case
            // records in its captured test log too; no image pixels or personal data are logged.
            for (index in 0 until rows.length()) android.util.Log.i("PrivateReplayCase", rows.getJSONObject(index).toString())
            android.util.Log.i("PrivateReplaySummary", JSONObject().put("completed", report.getBoolean("completed"))
                .put("caseCount", rows.length()).put("scope", report.getString("scope")).toString())
        }
    }

    private fun frame(bitmap: Bitmap): Pair<ImageProxy, () -> Boolean> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        pixels.forEach { bytes.put((it shr 16).toByte()); bytes.put((it shr 8).toByte()); bytes.put(it.toByte()); bytes.put(255.toByte()) }
        bytes.rewind()
        var closed = false
        val plane = Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
            when (method.name) { "getBuffer" -> bytes; "getRowStride" -> width * 4; "getPixelStride" -> 4; else -> error(method.name) }
        } as ImageProxy.PlaneProxy
        val timestamp = SystemClock.elapsedRealtimeNanos()
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getRotationDegrees" -> 0; "getTimestamp" -> timestamp
                "getSensorToBufferTransformMatrix" -> Matrix(); "getTagBundle" -> TagBundle.emptyBundle()
                else -> error(method.name)
            }
        } as ImageInfo
        val image = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth" -> width; "getHeight" -> height; "getCropRect" -> Rect(0, 0, width, height)
                "getPlanes" -> arrayOf(plane); "getImageInfo" -> info; "getFormat" -> 1
                "close" -> { check(!closed); closed = true; null }; else -> error(method.name)
            }
        } as ImageProxy
        return image to { closed }
    }
}
