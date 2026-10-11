package ai.dusty.finderplus.settings

/**
 * Measurements for the diagnostics page: how long each step took, how long models took to load, and
 * why work paused. Any module records here; the app installs a [sink] that stores them. Recording never
 * throws and costs nothing until a sink is installed.
 */
object Diagnostics {
    const val PASS = "pass"
    const val LOAD = "load"
    const val PAUSE = "pause"
    const val SLICE = "slice"

    fun interface Sink {
        fun record(kind: String, name: String, ms: Long, detail: String?)
    }

    @Volatile var sink: Sink = Sink { _, _, _, _ -> }

    fun record(kind: String, name: String, ms: Long, detail: String? = null) {
        runCatching { sink.record(kind, name, ms, detail) }
    }

    /** Runs [block], recording how long it took under [kind] and [name]. */
    inline fun <T> timed(kind: String, name: String, detail: String? = null, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(kind, name, (System.nanoTime() - start) / 1_000_000, detail)
        }
    }
}
