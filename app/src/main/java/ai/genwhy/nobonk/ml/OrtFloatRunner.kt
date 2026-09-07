package ai.genwhy.nobonk.ml

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Reuses caller-owned input/output tensors for a fixed float graph. No result-sized Java
 * array copy is needed. The engine owns this object and serializes input writes, inference,
 * model replacement and close. Output must be consumed before the next run.
 *
 * ORT Result.close does NOT close pinned outputs; we close both tensors exactly once here.
 * Dynamic output shapes retain a copying fallback, whose result stays alive while consumed.
 */
internal class OrtFloatRunner(
    environment: OrtEnvironment,
    private val session: OrtSession,
    input: FloatBuffer,
    inputShape: LongArray
) : AutoCloseable {
    private val outputName = session.outputNames.first()
    private val outputInfo = session.outputInfo.getValue(outputName).info as TensorInfo
    private val shape = outputInfo.shape
    private val inputTensor: OnnxTensor
    private val outputTensor: OnnxTensor?
    private val outputBuffer: FloatBuffer?
    private val inputs: Map<String, OnnxTensor>
    private val outputs: Map<String, OnnxTensor>
    private var closed = false

    init {
        require(outputInfo.type == OnnxJavaType.FLOAT) { "Expected a float model output" }
        val elements = shape.fold(1L) { size, dimension ->
            if (size <= 0 || dimension <= 0 || dimension > Int.MAX_VALUE / 4L / size) -1L
            else size * dimension
        }
        outputBuffer = if (elements > 0) ByteBuffer.allocateDirect((elements * 4).toInt())
            .order(ByteOrder.nativeOrder()).asFloatBuffer() else null
        inputTensor = OnnxTensor.createTensor(environment, input, inputShape)
        try {
            outputTensor = outputBuffer?.let { OnnxTensor.createTensor(environment, it, shape) }
        } catch (failure: Exception) {
            inputTensor.close()
            throw failure
        }
        inputs = mapOf(session.inputNames.first() to inputTensor)
        outputs = outputTensor?.let { mapOf(outputName to it) } ?: emptyMap()
    }

    @Synchronized fun <T> run(consume: (FloatBuffer, LongArray) -> T): T {
        check(!closed) { "Inference buffers are closed" }
        return if (outputBuffer != null) {
            session.run(inputs, outputs).use { consume(outputBuffer, shape) }
        } else {
            session.run(inputs, setOf(outputName)).use { result ->
                val tensor = result[0] as OnnxTensor
                consume(tensor.floatBuffer, tensor.info.shape)
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { outputTensor?.close() } finally { inputTensor.close() }
    }
}
