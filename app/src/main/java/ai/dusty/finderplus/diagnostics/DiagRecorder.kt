package ai.dusty.finderplus.diagnostics

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.DiagEventEntity
import ai.dusty.finderplus.settings.Diagnostics
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stores [Diagnostics] measurements: held in memory and written in batches every few seconds, so
 * recording on a hot path never waits on the database. Only the newest [KEEP] are kept.
 */
class DiagRecorder(private val db: FinderDatabase) : Diagnostics.Sink {

    private val queue = ConcurrentLinkedQueue<DiagEventEntity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writes = 0

    init {
        scope.launch {
            while (true) {
                delay(FLUSH_MS)
                flush()
            }
        }
    }

    override fun record(kind: String, name: String, ms: Long, detail: String?) {
        queue.add(DiagEventEntity(kind = kind, name = name, ms = ms, detail = detail, at = System.currentTimeMillis()))
    }

    suspend fun flush() {
        val batch = generateSequence { queue.poll() }.toList()
        if (batch.isEmpty()) return
        runCatching {
            db.diagDao().insert(batch)
            if (++writes % PRUNE_EVERY == 0) db.diagDao().prune(KEEP)
        }
    }

    private companion object {
        const val FLUSH_MS = 5_000L
        const val PRUNE_EVERY = 20
        const val KEEP = 5_000
    }
}
