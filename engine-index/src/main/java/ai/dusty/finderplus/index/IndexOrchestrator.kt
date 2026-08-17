package ai.dusty.finderplus.index

import androidx.room.withTransaction
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.RunState
import ai.dusty.finderplus.db.WorkLedger
import ai.dusty.finderplus.db.entity.IndexRunEntity
import ai.dusty.finderplus.db.entity.WorkUnitEntity
import ai.dusty.finderplus.db.toMediaItem
import ai.dusty.finderplus.index.pass.PassHandler
import ai.dusty.finderplus.index.pass.PassOutcome
import ai.dusty.finderplus.index.work.Checkpoints
import ai.dusty.finderplus.model.IndexProgress
import ai.dusty.finderplus.model.Pass
import ai.dusty.finderplus.model.RunStatus
import ai.dusty.finderplus.model.Trigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of one bounded work slice — tells the worker whether to schedule another. */
data class SliceOutcome(
    val workRemaining: Boolean,
    val stopped: Boolean,
    val yieldReason: YieldReason,
    val progress: IndexProgress,
)

interface IndexOrchestrator {
    /**
     * Run ONE bounded slice of indexing: reconcile orphans, scan (first slice only), then drain work
     * units until [budgetMs] elapses, the queue empties, a stop is requested, or the device gets too
     * hot / too low on battery. Idempotent and crash-safe — every slice resumes from the ledger.
     */
    suspend fun runSlice(
        trigger: Trigger,
        budgetMs: Long = PowerPolicy.SLICE_BUDGET_MS,
        onProgress: suspend (IndexProgress) -> Unit = {},
    ): SliceOutcome

    /** Cooperative stop: flips the in-memory + durable flag; the drain halts at the next boundary. */
    suspend fun requestStop()

    fun progress(): Flow<IndexProgress>
}

internal class DefaultIndexOrchestrator(
    private val db: FinderDatabase,
    private val scanner: Scanner,
    private val ledger: WorkLedger,
    private val finalizer: ItemFinalizer,
    private val terms: TermStats,
    private val captionBudget: CaptionBudget,
    private val coordinator: ModelCoordinator,
    private val power: PowerPolicy,
    private val imageCache: DecodedImageCache,
    private val booster: CpuBooster,
    private val vaultPolicy: VaultPolicy,
    private val appContext: android.content.Context,
    private val statusListener: IndexStatusListener,
    passHandlers: Set<PassHandler>,
    private val now: () -> Long = System::currentTimeMillis,
) : IndexOrchestrator {

    private val handlers: Map<Int, PassHandler> = passHandlers.associateBy { it.pass.ordinal }
    private val _progress = MutableStateFlow(IndexProgress.IDLE)

    private data class RunHandle(val runId: Long, val stop: StopSignal)

    @Volatile private var current: RunHandle? = null

    // In-memory counters. Recomputed once per slice, then incremented — replaces the old
    // three-COUNT(*)-per-unit accounting that scanned ~60k rows for every single work unit.
    private var total = 0
    private var done = 0
    private var failed = 0

    override fun progress(): Flow<IndexProgress> = _progress.asStateFlow()

    override suspend fun runSlice(
        trigger: Trigger,
        budgetMs: Long,
        onProgress: suspend (IndexProgress) -> Unit,
    ): SliceOutcome {
        val run = resolveRun(trigger)
        val stop = StopSignal(run.id, db.indexRunDao())
        current = RunHandle(run.id, stop)
        val deadline = now() + budgetMs
        var reason = YieldReason.NONE

        try {
            // 0. Ask for the big cores. Android puts a background worker in a cpuset limited to the
            //    little cores, and re-applies that whenever process importance changes — so this is
            //    requested per slice, not once. No-op without root; see [CpuBooster].
            booster.boost()

            // 1. Reclaim units abandoned by a killed process (checkpoints preserved).
            ledger.reconcileOrphans()
            db.indexRunDao().closeAbandoned(run.id, RunState.STOPPED, now())

            // 2. Scan on a fresh run and on every explicit "Update" (that is what the button means:
            //    look for new/changed/deleted files, and backfill any pass added to the pipeline since
            //    these items were indexed). Continuation slices skip straight to work.
            if (run.total_units == 0 || trigger == Trigger.MANUAL) {
                db.indexRunDao().setStatus(run.id, RunState.SCANNING)
                emit(run.id, RunStatus.SCANNING, null, onProgress)
                val summary = scanner.scan(run.id)

                // New media just appeared; if the user has folders marked private, hide the new
                // arrivals now rather than letting them sit in the gallery until someone remembers
                // to run it. Enqueued (not inline) so a long encryption pass never stalls the scan,
                // and a no-op when nothing matches.
                if (summary.added > 0 && vaultPolicy.auto && vaultPolicy.hasHideRules() &&
                    ai.dusty.finderplus.media.VaultCrypto.isRecoveryKeySaved(appContext)
                ) {
                    VaultWorker.enqueue(appContext)
                }
                if (summary.backfilled > 0) {
                    android.util.Log.i(TAG, "backfilled ${summary.backfilled} work units for newly added passes")
                }

                // A pass whose version was bumped (better algorithm, fixed output) must re-run on the
                // items it already processed. Only DONE units below the current version are requeued,
                // so this is selective rather than a full re-index.
                var requeued = 0
                var reconciled = 0
                for (p in Pass.entries) {
                    requeued += db.workUnitDao().requeueStaleVersion(p.ordinal, p.version, now())
                    // Priority and residency are denormalized onto each row at enqueue time, so a pass
                    // that changes tier leaves every existing row on the old one — and the symptom is
                    // not an error but a pass that appears to be ignored forever.
                    reconciled += db.workUnitDao().reconcilePassMetadata(p.ordinal, p.priority, p.model.ordinal)
                }
                if (requeued > 0) android.util.Log.i(TAG, "requeued $requeued units for bumped pass versions")
                if (reconciled > 0) android.util.Log.i(TAG, "reconciled $reconciled units to current pass priorities")

                // A new run gets a fresh caption allowance, and units the previous run's governor
                // parked come back — that pairing is what makes the budget per-run pacing rather
                // than a permanent cut.
                captionBudget.reset()
                val revivedCaptions = db.workUnitDao().requeueSkipped(
                    ai.dusty.finderplus.model.Pass.CAPTION.ordinal, now())
                if (revivedCaptions > 0) android.util.Log.i(TAG, "revived $revivedCaptions parked captions")

                // Recount the corpus BEFORE rebuilding artifacts: which words are distinctive is
                // decided by these counts, so rebuilding first would bake in the previous scan's
                // statistics — and on a first run, no statistics at all.
                runCatching { terms.rebuild() }
                    .onSuccess { android.util.Log.i(TAG, "corpus terms counted: $it") }
                    .onFailure { android.util.Log.w(TAG, "term counting failed: ${it.message}") }

                // Re-derive stale profiles/FTS rows. Pure SQL over existing signals — no model work.
                rebuildStaleArtifacts()
            }

            db.indexRunDao().setStatus(run.id, RunState.RUNNING)
            total = db.workUnitDao().totalCount()
            done = db.workUnitDao().doneCount()
            failed = db.workUnitDao().failedCount()
            db.indexRunDao().setCounts(run.id, total, done, failed)
            emit(run.id, RunStatus.RUNNING, null, onProgress)

            // 3. Drain, bounded by budget / thermal / battery / stop.
            reason = drain(run.id, stop, deadline, onProgress)

            val stopped = stop.isStopRequested()
            val remaining = db.workUnitDao().remainingCount() > 0
            val status = when {
                stopped -> RunStatus.STOPPED
                !remaining -> RunStatus.DONE
                reason == YieldReason.THERMAL || reason == YieldReason.LOW_BATTERY -> RunStatus.PAUSED
                else -> RunStatus.RUNNING // more slices to come
            }
            db.indexRunDao().setCounts(run.id, total, done, failed)
            if (stopped) {
                db.indexRunDao().finish(run.id, RunState.STOPPED, now())
            } else if (!remaining) {
                db.indexRunDao().finish(run.id, RunState.DONE, now())
            } else {
                db.indexRunDao().setStatus(run.id, if (status == RunStatus.PAUSED) RunState.PAUSED else RunState.RUNNING)
            }
            emit(run.id, status, null, onProgress)
            return SliceOutcome(remaining && !stopped, stopped, reason, _progress.value)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "index slice failed", t)
            db.indexRunDao().setStatus(run.id, RunState.RUNNING) // resumable; do not bury the run
            emit(run.id, RunStatus.FAILED, null, onProgress)
            throw t
        } finally {
            imageCache.clear()
            current = null
        }
    }

    /**
     * Refresh derived search artifacts for items whose profile predates the current format. Bounded per
     * slice so a large gallery is caught up over successive Updates rather than blocking one slice.
     */
    private suspend fun rebuildStaleArtifacts() {
        val version = ItemFinalizer.SEARCH_ARTIFACT_VERSION
        val pending = db.mediaItemDao().countStaleArtifacts(version)
        if (pending == 0) return
        android.util.Log.i(TAG, "rebuilding search artifacts for $pending items")
        var processed = 0
        while (processed < MAX_ARTIFACT_REBUILDS) {
            val batch = db.mediaItemDao().itemsWithStaleArtifacts(version, 200)
            if (batch.isEmpty()) break
            for (id in batch) {
                db.withTransaction {
                    finalizer.rebuildSearch(id)
                    finalizer.projectState(id)
                }
                processed++
            }
        }
        android.util.Log.i(TAG, "rebuilt $processed profiles (${(pending - processed).coerceAtLeast(0)} left)")
    }

    /** Reuse an unfinished, un-stopped run so progress is continuous across slices. */
    private suspend fun resolveRun(trigger: Trigger): IndexRunEntity {
        db.indexRunDao().latestResumable()?.let { return it }
        val id = db.indexRunDao().insert(
            IndexRunEntity(
                trigger = trigger.ordinal, status = RunState.SCANNING, stop_requested = 0,
                total_units = 0, done_units = 0, failed_units = 0,
                started_at = now(), finished_at = null, last_generation = 0,
            )
        )
        return db.indexRunDao().byId(id)!!
    }

    private suspend fun drain(
        runId: Long,
        stop: StopSignal,
        deadline: Long,
        onProgress: suspend (IndexProgress) -> Unit,
    ): YieldReason {
        val owner = runId.toString()
        var lastEmit = 0L
        var lastPersist = now()
        while (true) {
            if (stop.isStopRequested()) return YieldReason.NONE
            if (now() >= deadline) return YieldReason.BUDGET
            power.yieldReason().let { if (it != YieldReason.NONE) return it }

            // Prefer the resident heavy model (affinity) to avoid load/unload thrash; fall back to any
            // work so an empty affinity queue never ends the slice early.
            val unit = ledger.claimNext(owner, coordinator.residentCode(), affinityOnly = true)
                ?: ledger.claimNext(owner, coordinator.residentCode(), affinityOnly = false)
                ?: return YieldReason.NONE

            processUnit(unit, stop, deadline)

            val nowMs = now()
            if (nowMs - lastEmit >= PROGRESS_EMIT_MS) {
                lastEmit = nowMs
                emit(runId, RunStatus.RUNNING, Pass.entries[unit.pass], onProgress)
            }
            if (nowMs - lastPersist >= COUNT_PERSIST_MS) {
                lastPersist = nowMs
                db.indexRunDao().setCounts(runId, total, done, failed)
            }

            // Duty cycle: the pause that keeps average CPU (and battery drain) low.
            power.throttleMs().takeIf { it > 0 }?.let { delay(it) }
        }
    }

    private suspend fun processUnit(unit: WorkUnitEntity, stop: StopSignal, deadline: Long) {
        ledger.markRunning(unit.id)
        val entity = db.mediaItemDao().byId(unit.item_id)
        if (entity == null) {
            ledger.fail(unit.id, "item missing"); failed++; return
        }
        val handler = handlers[unit.pass]
        if (handler == null) {
            ledger.fail(unit.id, "no handler for pass ${unit.pass}"); failed++; return
        }

        // Claim the fast cores for every pass, including speech.
        //
        // An earlier version skipped ASR, on the evidence that moving to the `top-app` cpuset changed
        // transcription not at all (51.4 s vs 52.3 s per window) while running hotter. That evidence
        // stands, but it only rules out the *cpuset* — and the cpuset merely widens which cores are
        // permitted. It does not stop ggml's thread pool from straddling a 3.21 GHz core and four
        // 1.96 GHz ones, and ggml waits for its slowest thread on every layer, so a straddling pool runs
        // at little-core speed no matter how wide the cpuset is. Affinity pinning is the distinct fix for
        // that, so speech gets it too and the result is measured rather than assumed.
        booster.ensureBoosted()

        val item = entity.toMediaItem()
        val checkpoint = Checkpoints.decode(unit.checkpoint)
        val writer = ResultWriter(db, item.id, unit.id, WorkLedger.DEFAULT_LEASE_MS, stop, deadline, now)
        try {
            when (handler.process(item, unit, checkpoint, writer)) {
                PassOutcome.COMPLETED -> {
                    db.withTransaction { ledger.complete(unit.id, handler.pass.version) }
                    done++
                    // Rebuild the search artifacts as soon as the item's *cheap* text passes are
                    // through, and again whenever a later one (transcript, concepts) adds to them.
                    // Waiting for every text pass meant waiting on CONCEPTS, which cannot run until the
                    // whole gallery is embedded — so nothing was ever searchable by keyword.
                    if (handler.pass.contributesText && db.workUnitDao().remainingCheapTextPassesForItem(item.id) == 0) {
                        db.withTransaction {
                            finalizer.rebuildSearch(item.id)
                            finalizer.projectState(item.id)
                        }
                    }
                }
                PassOutcome.STOPPED -> ledger.release(unit.id) // back to PENDING; checkpoint intact
                PassOutcome.SKIPPED -> {
                    // Prerequisite absent (e.g. no speech model). Park it, and still finish the item's
                    // search artifacts so everything else about it stays findable.
                    ledger.skip(unit.id, "prerequisite unavailable")
                    if (db.workUnitDao().remainingCheapTextPassesForItem(item.id) == 0) {
                        db.withTransaction {
                            finalizer.rebuildSearch(item.id)
                            finalizer.projectState(item.id)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            ledger.fail(unit.id, t.message ?: t.javaClass.simpleName)
            failed++
            finalizer.projectState(item.id)
        }
    }

    override suspend fun requestStop() {
        val handle = current
        if (handle != null) {
            handle.stop.request()
            db.indexRunDao().requestStop(handle.runId)
            _progress.value = _progress.value.copy(status = RunStatus.STOPPING)
        } else {
            // No slice in flight: mark the resumable run stopped so a queued slice exits immediately.
            db.indexRunDao().latestResumable()?.let {
                db.indexRunDao().requestStop(it.id)
                db.indexRunDao().finish(it.id, RunState.STOPPED, now())
            }
            _progress.value = _progress.value.copy(status = RunStatus.STOPPED)
        }
    }

    private suspend fun emit(
        runId: Long,
        status: RunStatus,
        pass: Pass?,
        onProgress: suspend (IndexProgress) -> Unit,
    ) {
        val progress = IndexProgress(runId, status, total, done, failed, pass, etaSeconds = null)
        _progress.value = progress
        onProgress(progress)
        runCatching { statusListener.onProgress(progress) }
    }

    private companion object {
        const val TAG = "finderIndex"
        const val PROGRESS_EMIT_MS = 1_200L
        const val COUNT_PERSIST_MS = 5_000L
        const val MAX_ARTIFACT_REBUILDS = 6_000
    }
}
