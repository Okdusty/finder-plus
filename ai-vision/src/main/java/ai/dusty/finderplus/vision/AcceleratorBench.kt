package ai.dusty.finderplus.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Times one model on the processor and through NNAPI over the same inputs, and compares their outputs.
 * The NNAPI session has no fallback to the processor, so a model it cannot run reports as such rather
 * than being timed on the processor.
 */
object AcceleratorBench {

    /** Per-input times after a first, untimed run; [acceleratorMs] null when NNAPI cannot run it. */
    class Result(val cpuMs: List<Long>, val acceleratorMs: List<Long>?, val agreement: Float?)

    /** [inputs] are (shape, data) for the model's first input. Null when the model will not load at all. */
    fun measure(path: String, inputs: List<Pair<LongArray, FloatArray>>): Result? {
        val cpu = OrtSessions.create(path, OrtSessions.Provider.CPU) ?: return null
        val acc = OrtSessions.create(path, OrtSessions.Provider.NNAPI, fallback = false)
        try {
            val cpuMs = ArrayList<Long>(); val accMs = ArrayList<Long>(); var worst = 1f
            inputs.forEachIndexed { i, (shape, data) ->
                val (a, ta) = run(cpu, shape, data)
                if (i > 0) cpuMs += ta
                if (acc != null) {
                    val (b, tb) = runCatching { run(acc, shape, data) }.getOrElse { return Result(cpuMs, null, null) }
                    if (i > 0) accMs += tb
                    worst = minOf(worst, cosine(a, b))
                }
            }
            return Result(cpuMs, if (acc != null) accMs else null, if (acc != null) worst else null)
        } finally {
            cpu.close(); acc?.close()
        }
    }

    private fun run(session: OrtSession, shape: LongArray, data: FloatArray): Pair<FloatArray, Long> {
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(data), shape).use { input ->
            val t0 = System.nanoTime()
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                val ms = (System.nanoTime() - t0) / 1_000_000
                val buf = (out.get(0) as OnnxTensor).floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) } to ms
            }
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (d / Math.sqrt(na * nb)).toFloat()
    }
}
