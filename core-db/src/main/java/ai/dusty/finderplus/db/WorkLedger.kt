package ai.dusty.finderplus.db

import ai.dusty.finderplus.db.dao.WorkUnitDao
import ai.dusty.finderplus.db.entity.WorkUnitEntity

/**
 * Thin, correctness-focused wrapper over [WorkUnitDao] that implements the two operations the raw
 * DAO cannot express as a single statement: the optimistic claim retry loop, and startup
 * reconciliation. Everything else (checkpoint, complete, fail) is a direct atomic DAO call.
 *
 * The atomic "commit partial result + advance checkpoint" spans multiple tables and therefore lives
 * in engine-index inside a `withTransaction { }`; this class deliberately touches only work_unit.
 * See docs/design/01-DB-ENGINE.md §4, §5.
 */
class WorkLedger(
    private val dao: WorkUnitDao,
    private val leaseMs: Long = DEFAULT_LEASE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Claim the next runnable unit, preferring [residentModel] to avoid heavy-model thrash. Retries
     * when another worker wins the optimistic race. Returns null when the queue is drained.
     */
    suspend fun claimNext(runId: String, residentModel: Int, affinityOnly: Boolean): WorkUnitEntity? {
        while (true) {
            val id = dao.pickNext(residentModel, affinityOnly) ?: return null
            val won = dao.claim(id, runId, now(), leaseMs)
            if (won == 1) return dao.byId(id)
            // Lost the race (0 rows): the row changed state under us — loop and pick another.
        }
    }

    /** Reclaim units abandoned by a crashed worker. Call once at engine start before draining. */
    suspend fun reconcileOrphans(): Int = dao.reclaimOrphans(now())

    suspend fun markRunning(id: Long) = dao.markRunning(id, now())

    suspend fun renewCheckpoint(id: Long, cursor: String) = dao.checkpoint(id, cursor, now(), leaseMs)

    suspend fun complete(id: Long, version: Int) = dao.complete(id, version, now())

    suspend fun fail(id: Long, error: String) = dao.fail(id, error, now())

    /** Release a claimed unit back to PENDING on cooperative stop (checkpoint preserved). */
    suspend fun release(id: Long) = dao.release(id, now())

    /** Park a unit whose prerequisite is missing; [requeueSkipped] revives it once that changes. */
    suspend fun skip(id: Long, reason: String) = dao.skip(id, reason, now())

    suspend fun requeueSkipped(pass: Int) = dao.requeueSkipped(pass, now())

    companion object {
        /** Lease window: long enough to cover one heavy micro-batch, short enough for prompt recovery. */
        const val DEFAULT_LEASE_MS = 2 * 60_000L
    }
}
