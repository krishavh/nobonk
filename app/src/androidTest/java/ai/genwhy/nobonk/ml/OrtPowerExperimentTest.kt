package ai.genwhy.nobonk.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in experiment: process CPU time is a workload proxy, not measured battery energy. */
class OrtPowerExperimentTest {
    @Test fun compareSpinPoliciesAtTenFramesPerSecond() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPowerExperiment") == "true")
        val env = OrtEnvironment.getEnvironment()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (model in listOf("yolo26n_416.onnx", "yolo26s_416.onnx")) {
            val bytes = context.assets.open(model).use { it.readBytes() }
            // Forward then reverse ordering: shared-host load still prevents phone-performance claims.
            for (policy in listOf("default", "off", "bounded", "bounded", "off", "default")) {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    if (policy == "off") options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    if (policy == "bounded") {
                        options.addConfigEntry("session.intra_op.spin_duration_us", "1000")
                        options.addConfigEntry("session.intra_op.spin_backoff_max", "8")
                    }
                    env.createSession(bytes, options).use { session ->
                        val input = ByteBuffer.allocateDirect(4 * 3 * 416 * 416)
                            .order(ByteOrder.nativeOrder()).asFloatBuffer()
                        OrtFloatRunner(env, session, input, longArrayOf(1, 3, 416, 416)).use { runner ->
                            repeat(5) { runner.run { _, _ -> Unit } }
                            val samples = mutableListOf<Double>()
                            val cpuStart = Process.getElapsedCpuTime()
                            val wallStart = SystemClock.elapsedRealtime()
                            repeat(20) {
                                val start = SystemClock.elapsedRealtimeNanos()
                                runner.run { _, _ -> Unit }
                                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                                samples += elapsed
                                SystemClock.sleep((100 - elapsed.toLong()).coerceAtLeast(0))
                            }
                            Log.i("NoBonkPowerBench", "$model $policy medianMs=${samples.sorted()[10]} " +
                                "cpuMsPerFrame=${(Process.getElapsedCpuTime() - cpuStart) / 20.0} " +
                                "wallMs=${SystemClock.elapsedRealtime() - wallStart}")
                        }
                    }
                }
            }
        }
    }
}
