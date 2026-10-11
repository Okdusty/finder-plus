package ai.dusty.finderplus.index

import java.io.File

/**
 * The processor's temperature from the kernel's thermal zones, on phones that let apps read them; many
 * do not, and Android offers apps no other source. Zone names differ by chip, so the processor is
 * recognised by names its zones commonly carry, and the hottest of them is reported, since that is
 * the part that throttles first.
 */
object ThermalZones {

    private val ROOT = File("/sys/class/thermal")

    /** Words in a zone's name that mark it as part of the processor. */
    private val PROCESSOR = listOf("cpu", "soc", "cluster", "big", "mid", "little", "tsens", "apss")

    /** Words that mark a zone as something else, even when it also carries a processor word. */
    private val OTHER = listOf("batt", "bms", "chg", "charger", "usb", "skin", "pa_", "modem", "wifi", "gpu", "g3d", "npu", "isp", "camera", "flash", "xo", "ufs", "pmic", "sdm", "nr_", "lte")

    /** Hottest processor zone in °C, or null when none can be read. */
    fun processorC(): Float? = runCatching {
        ROOT.listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { zone ->
            val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: return@mapNotNull null
            val isProcessor = (type == "ap" || PROCESSOR.any { it in type }) && OTHER.none { it in type }
            if (!isProcessor) return@mapNotNull null
            celsius(runCatching { File(zone, "temp").readText().trim().toLong() }.getOrNull())
        }.maxOrNull()
    }.getOrNull()

    /** Kernels report millidegrees, a few whole degrees; anything outside a sane range is a bad read. */
    internal fun celsius(raw: Long?): Float? {
        raw ?: return null
        val c = if (raw > 1000 || raw < -1000) raw / 1000f else raw.toFloat()
        return c.takeIf { it in 1f..150f }
    }
}
