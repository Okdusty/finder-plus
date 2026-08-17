package ai.rightone.finderplus.index

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wall-clock governor for VLM captioning — the mechanism behind "if it takes too long, cancel and
 * move on".
 *
 * Captioning is the only pass whose cost is *unknown until measured on this device*: the same model
 * spans ~1 s to ~20 s per image depending on backend and thermals. Everything else in the pipeline has
 * a measured, bounded cost. So this pass gets a budget rather than trust, and three rules:
 *
 *  1. **Per-item ceiling.** A caption slower than [PER_ITEM_CEILING_MS] earns a strike;
 *     [MAX_STRIKES] consecutive strikes disables captioning for the rest of the run. This catches the
 *     "device is fundamentally too slow / thermally collapsing" case within the first few items
 *     instead of discovering it hours later.
 *  2. **Projection.** After [WARMUP] measured captions, remaining × median must fit inside
 *     [TOTAL_BUDGET_MS]; if it does not, the remainder is parked. This catches "each item is fine but
 *     the gallery is huge".
 *  3. **Parked, not failed.** A unit stopped by the governor reports SKIPPED, so it revives on a later
 *     run — overnight on the charger the same units get another window of budget.
 *
 * Strikes must be *consecutive*: a single slow caption while ASR holds the GPU is contention, not
 * capability, and one fast caption resets the count.
 */
@Singleton
class CaptionBudget @Inject constructor() {

    private val durations = ArrayList<Long>()
    private var consecutiveStrikes = 0
    private var disabledReason: String? = null
    private var spentMs = 0L

    @Synchronized
    fun record(ms: Long) {
        durations += ms
        spentMs += ms
        if (ms > PER_ITEM_CEILING_MS) {
            consecutiveStrikes++
            if (consecutiveStrikes >= MAX_STRIKES && disabledReason == null) {
                disabledReason = "$MAX_STRIKES consecutive captions over ${PER_ITEM_CEILING_MS / 1000}s"
                log("captioning disabled: $disabledReason")
            }
        } else {
            consecutiveStrikes = 0
        }
    }

    /**
     * Whether the next caption is worth attempting, given [remaining] items still queued.
     *
     * The rule is *spend the budget, then stop* — deliberately NOT "stop if the whole gallery won't
     * fit". The first version projected `median × remaining` against the budget and parked when it
     * overflowed, which on a 4,876-item gallery at a measured 4.4 s median meant parking at the
     * warmup boundary: exactly 15 captions ran and 4,861 were skipped with 74 minutes of budget
     * still unspent. A budget that refuses to start what it cannot finish buys nothing; a thousand
     * captioned items this run and the rest next run is the whole point of parking being revivable.
     * The projection survives only as a log line, because knowing the completion horizon is useful —
     * acting on it was the bug.
     */
    @Synchronized
    fun shouldContinue(remaining: Int): Boolean {
        disabledReason?.let { return false }
        if (spentMs >= TOTAL_BUDGET_MS) {
            disabledReason = "budget spent (${spentMs / 60000} min, ${durations.size} captioned)"
            log("captioning parked: $disabledReason")
            return false
        }
        if (durations.size == WARMUP) {
            val median = durations.sorted()[durations.size / 2]
            log(
                "projection: ~${median * remaining / 60000} min to caption all $remaining remaining " +
                    "at ${median}ms median; this run will spend ${(TOTAL_BUDGET_MS - spentMs) / 60000} min of budget",
            )
        }
        return true
    }

    /**
     * Fresh allowance for a new indexing run. Without this the singleton's spent-counter and
     * disabled-flag outlive the run that exhausted them, and every later run starts pre-parked.
     */
    @Synchronized
    fun reset() {
        durations.clear()
        consecutiveStrikes = 0
        disabledReason = null
        spentMs = 0L
    }

    @Synchronized
    fun stats(): String =
        "captions=${durations.size} spent=${spentMs / 1000}s " +
            (if (durations.isEmpty()) "" else "median=${durations.sorted()[durations.size / 2]}ms ") +
            (disabledReason?.let { "PARKED: $it" } ?: "active")

    /** android.util.Log is not on the JVM test classpath; the governor's logic must not need it. */
    private fun log(msg: String) {
        runCatching { android.util.Log.i(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    companion object {
        private const val TAG = "finderCaption"

        /** One caption slower than this is suspicious; three in a row means stop. */
        const val PER_ITEM_CEILING_MS = 15_000L

        const val MAX_STRIKES = 3

        /** Measured medians below this many samples are noise. */
        const val WARMUP = 15

        /**
         * Total captioning time one run may spend: 75 minutes of the user's 2-hour target, leaving the
         * rest for embedding, detection and everything else. Parked units revive next run, so this is
         * per-run pacing, not a permanent cut.
         */
        const val TOTAL_BUDGET_MS = 75L * 60 * 1000
    }
}
