package ai.dusty.finderplus.index

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * The processor's temperature from Android's thermal service, read through root on phones that keep
 * the kernel's thermal zones from apps. One root shell is kept open while a screen shows the reading,
 * so the user grants root once rather than at every reading. The service reports temperatures by
 * standard type, so the processor is found the same way on any chip.
 */
object RootThermal {

    /** Where root managers commonly install `su`; an app's PATH does not reach all of them. */
    private val SU = listOf("/system_ext/bin/su", "/debug_ramdisk/su", "/system/bin/su", "/system/xbin/su", "/sbin/su")

    private const val END = "__finder_end__"

    @Volatile private var proc: Process? = null
    private var input: OutputStreamWriter? = null
    private var output: BufferedReader? = null

    fun available(): Boolean = SU.any { File(it).exists() }

    val running: Boolean get() = proc?.isAlive == true

    /** Opens the root shell; the root manager may ask the user first. False when root is not granted. */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val su = SU.firstOrNull { File(it).exists() } ?: return false
        return runCatching {
            val p = ProcessBuilder(su).redirectErrorStream(true).start()
            proc = p
            input = OutputStreamWriter(p.outputStream)
            output = BufferedReader(InputStreamReader(p.inputStream))
            // A shell that does not answer is one that was refused.
            ask("id -u")?.firstOrNull()?.trim() == "0"
        }.getOrDefault(false).also { if (!it) stop() }
    }

    /** Hottest CPU-type sensor the thermal service reports, °C, or null. */
    @Synchronized
    fun processorC(): Float? {
        if (!running) return null
        val lines = ask("dumpsys thermalservice") ?: return null
        // "Temperature{mValue=46.4, mType=0, mName=AP, …}": type 0 is CPU in Android's thermal API.
        return lines.asSequence()
            .filter { "Temperature{" in it && "mType=0," in it }
            .mapNotNull { Regex("""mValue=(-?[\d.]+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            .filter { it in 1f..150f }
            .maxOrNull()
    }

    @Synchronized
    fun stop() {
        runCatching { input?.write("exit\n"); input?.flush() }
        runCatching { proc?.destroy() }
        proc = null; input = null; output = null
    }

    /** Runs [command] in the root shell and returns its output, or null if the shell did not answer in time. */
    private fun ask(command: String): List<String>? {
        val w = input ?: return null
        val r = output ?: return null
        w.write("$command\necho $END\n"); w.flush()
        val lines = ArrayList<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        while (System.nanoTime() < deadline) {
            if (!r.ready()) { Thread.sleep(20); if (proc?.isAlive != true) return null; continue }
            val line = r.readLine() ?: return null
            if (line == END) return lines
            lines += line
        }
        return null
    }

    /** Long enough for the user to answer the root manager's prompt the first time. */
    private const val TIMEOUT_S = 30L
}
