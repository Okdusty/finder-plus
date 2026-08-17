package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.dao.IndexRunDao
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative cancellation. The in-memory flag is the fast path checked between micro-batches; the
 * durable index_run.stop_requested column is the backstop that survives process death so a resumed
 * run still honors a stop requested before the crash. See docs/design/01-DB-ENGINE.md §7.
 */
class StopSignal(
    private val runId: Long,
    private val runDao: IndexRunDao,
) {
    private val flag = AtomicBoolean(false)

    fun request() { flag.set(true) }

    suspend fun isStopRequested(): Boolean = flag.get() || runDao.isStopRequested(runId) == 1
}
