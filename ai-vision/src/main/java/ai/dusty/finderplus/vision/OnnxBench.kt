package ai.dusty.finderplus.vision

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Times one ONNX model with the indexer's session settings ([OrtSessions]) on synthetic inputs: floats
 * are 0, integers 1, bools false. Symbolic dimensions take their size from [dims] by name; unnamed or
 * unlisted ones are 1.
 */
object OnnxBench {

    fun run(model: File, dims: Map<String, Long>, runs: Int): String {
        val t0 = System.currentTimeMillis()
        val session = OrtSessions.create(model.absolutePath) ?: return "${model.name}: cannot open"
        val loadMs = System.currentTimeMillis() - t0
        val env = OrtEnvironment.getEnvironment()
        return session.use { s ->
            val inputs = s.inputInfo.mapValues { (_, node) -> tensorFor(env, node.info as TensorInfo, dims) }
            try {
                val shapes = inputs.entries.joinToString(" ") { (k, v) -> "$k${v.info.shape.contentToString()}" }
                val times = ArrayList<Long>()
                repeat(runs + 1) {
                    val t = System.nanoTime()
                    s.run(inputs).close()
                    times += (System.nanoTime() - t) / 1_000_000
                }
                // The first run includes one-time allocation and is reported apart.
                val warm = times.drop(1).sorted()
                "${model.name}: load ${loadMs}ms first ${times[0]}ms median ${warm[warm.size / 2]}ms min ${warm.first()}ms | $shapes"
            } catch (t: Throwable) {
                "${model.name}: run failed: ${t.message}"
            } finally {
                inputs.values.forEach { it.close() }
            }
        }
    }

    private fun tensorFor(env: OrtEnvironment, info: TensorInfo, dims: Map<String, Long>): OnnxTensor {
        val names = info.dimensionNames
        val shape = LongArray(info.shape.size) { i ->
            if (info.shape[i] >= 0) info.shape[i] else dims[names.getOrNull(i).orEmpty()] ?: 1L
        }
        val count = shape.fold(1L) { a, b -> a * b }.toInt()
        return when (info.type) {
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(env, direct(count * 4).asFloatBuffer(), shape)
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, direct(count * 8).asLongBuffer().also { b -> repeat(count) { b.put(1L) }; b.rewind() }, shape)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, direct(count * 4).asIntBuffer().also { b -> repeat(count) { b.put(1) }; b.rewind() }, shape)
            OnnxJavaType.BOOL -> OnnxTensor.createTensor(env, direct(count), shape, OnnxJavaType.BOOL)
            OnnxJavaType.FLOAT16 -> OnnxTensor.createTensor(env, direct(count * 2), shape, OnnxJavaType.FLOAT16)
            else -> error("unsupported input type ${info.type}")
        }
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}
