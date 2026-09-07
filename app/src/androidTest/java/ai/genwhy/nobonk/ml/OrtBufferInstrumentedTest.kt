package ai.genwhy.nobonk.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/** Runs the shipped Android ORT library and both real assets; no mocked native inference. */
class OrtBufferInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun buffer(size: Int) = ByteBuffer.allocateDirect(size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val inputShape = longArrayOf(1, 3, 416, 416)
    private val transform = Letterbox.compute(320, 640, 416)

    @Test fun pinnedParityLifetimeAndAllocation() {
        val env = OrtEnvironment.getEnvironment()
        assertEquals("1.29.0", env.version)
        for (model in listOf("yolo26n_416.onnx", "yolo26s_416.onnx")) {
            val bytes = instrumentation.targetContext.assets.open(model).use { it.readBytes() }
            for (provider in listOf("CPU", "XNNPACK")) {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(if (provider == "CPU") 2 else 1)
                    if (provider == "XNNPACK") options.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                    env.createSession(bytes, options).use { session ->
                        verifyAndMeasure(env, session, model, provider)
                    }
                }
            }
        }
    }

    private fun verifyAndMeasure(env: OrtEnvironment, session: OrtSession, model: String, provider: String) {
        val input = buffer(3 * 416 * 416)
        val name = session.inputNames.first()
        val runner = OrtFloatRunner(env, session, input, inputShape)
        var firstOutput: FloatBuffer? = null
        try {
            // Different frames catch stale input/output reuse, while a direct reference comparison
            // covers every box coordinate and all 80 classes, including classes the UI filters out.
            for (seed in 0..2) {
                for (i in 0 until input.capacity()) input.put(i, ((i * 17 + seed * 53) % 256) / 255f)
                val reference = OnnxTensor.createTensor(env, input, inputShape).use { tensor ->
                    session.run(mapOf(name to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        result[0].value as Array<Array<FloatArray>>
                    }
                }
                runner.run { actual, shape ->
                    assertTrue(shape.contentEquals(longArrayOf(1, 84, 3549)))
                    if (firstOutput == null) firstOutput = actual else assertSame(firstOutput, actual)
                    for (channel in 0 until 84) for (box in 0 until 3549) {
                        val expected = reference[0][channel][box]
                        val found = actual.get(channel * 3549 + box)
                        if (abs(expected - found) > 1e-5f + abs(expected) * 1e-5f)
                            fail("$model/$provider channel=$channel box=$box: $expected vs $found")
                    }
                    val old = Nms.apply(CocoRawHeadDecoder.decode(reference, true, 80, transform, .4f), .45f)
                    val fresh = Nms.apply(CocoRawHeadDecoder.decode(actual, true, 80, 3549, transform, .4f), .45f)
                    assertEquals(old.map { Triple(it.classId, it.boundingBox, it.confidence) },
                        fresh.map { Triple(it.classId, it.boundingBox, it.confidence) })
                } // Result is closed after each call; caller-owned output must survive the next run.
            }
            val measurements = mutableListOf<String>()
            // ABBA ordering reduces simple warm-up/order bias. Emulator timing is not phone timing.
            for (pinned in listOf(false, true, true, false)) {
                val samples = mutableListOf<Double>()
                val before = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                repeat(20) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    if (pinned) runner.run { out, _ ->
                        Nms.apply(CocoRawHeadDecoder.decode(out, true, 80, 3549, transform, .4f), .45f)
                    } else OnnxTensor.createTensor(env, input, inputShape).use { tensor ->
                        session.run(mapOf(name to tensor)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val out = result[0].value as Array<Array<FloatArray>>
                            Nms.apply(CocoRawHeadDecoder.decode(out, true, 80, transform, .4f), .45f)
                        }
                    }
                    samples += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                }
                val allocated = (Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - before) / 20
                measurements += "${if (pinned) "pinned" else "array"}: medianMs=${samples.sorted()[10]}, bytesPerFrame=$allocated"
            }
            Log.i("NoBonkBufferBench", "$model $provider ${measurements.joinToString(" | ")}")
        } finally { runner.close() }
        runner.close() // idempotent cleanup
        try { runner.run { _, _ -> Unit }; fail("Closed buffers must reject a run") }
        catch (_: IllegalStateException) { /* expected */ }
        // Closing the buffer owner must not close the caller's session.
        OnnxTensor.createTensor(env, input, inputShape).use { session.run(mapOf(name to it)).close() }
    }
}
