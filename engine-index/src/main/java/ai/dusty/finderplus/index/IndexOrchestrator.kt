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
import ai.dusty.finderplus.model.MediaKind
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
        budgetMs: Long = PowerPolicy.sliceBudgetMs(),
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
    private val coordinator: ModelCoordinator,
    private val power: PowerPolicy,
    private val imageCache: DecodedImageCache,
    private val booster: CpuBooster,
    private val vaultPolicy: VaultPolicy,
    private val appContext: android.content.Context,
    private val statusListener: IndexStatusListener,
    private val frames: ai.dusty.finderplus.media.FrameExtractor,
    passHandlers: Set<PassHandler>,
    private val beforeRun: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : IndexOrchestrator {

    private val handlers: Map<Int, PassHandler> = passHandlers.associateBy { it.pass.ordinal }
    private val _progress = MutableStateFlow(IndexProgress.IDLE)

    private data class RunHandle(val runId: Long, val stop: StopSignal)

    @Volatile private var current: RunHandle? = null

    // Recounted once per slice, then incremented, so the drain loop never runs COUNT(*) per unit.
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
            if (ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.ProcessingSettings.FAST_CORES]) booster.boost()

            // 1. Reclaim units abandoned by a killed process (checkpoints preserved).
            ledger.reconcileOrphans()
            db.indexRunDao().closeAbandoned(run.id, RunState.STOPPED, now())

            // 2. Scan on a fresh run and on every explicit "Update": find new, changed and deleted
            //    files and backfill passes added since the items were indexed. Continuation slices
            //    skip straight to work.
            if (run.total_units == 0 || trigger == Trigger.MANUAL || trigger == Trigger.SCHEDULED) {
                // Edits the user held back are applied as a run starts, so the run indexes them.
                runCatching { beforeRun() }.onFailure { android.util.Log.w(TAG, "held edits not applied: ${it.message}") }
                db.indexRunDao().setStatus(run.id, RunState.SCANNING)
                emit(run.id, RunStatus.SCANNING, null, onProgress)
                val summary = scanner.scan(run.id)

                // Hide new arrivals in private folders right away. Enqueued rather than run inline so
                // encryption never stalls the scan; a no-op when nothing matches.
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
                    // Priority and residency are copied onto each row at enqueue time, so a pass that
                    // changes tier would leave existing rows on the old one and look ignored forever.
                    reconciled += db.workUnitDao().reconcilePassMetadata(p.ordinal, p.priority, p.model.ordinal)
                }
                if (requeued > 0) android.util.Log.i(TAG, "requeued $requeued units for bumped pass versions")
                val dropped = Pass.retired.sumOf { db.workUnitDao().dropUnfinished(it.ordinal) }
                if (dropped > 0) android.util.Log.i(TAG, "dropped $dropped unfinished units of retired passes")
                // Passes switched off for a kind of media: what they already found stays, the rest is
                // dropped. Switching one on again is picked up by the scan's backfill.
                val off = MediaKind.entries.sumOf { kind ->
                    (Pass.pipelineFor(kind) - Pass.forKind(kind).toSet())
                        .sumOf { db.workUnitDao().dropUnfinishedForKind(it.ordinal, kind.ordinal) }
                }
                if (off > 0) android.util.Log.i(TAG, "dropped $off unfinished units of passes switched off")
                OrientationRepair.runOnce(appContext, db, now())
                runCatching { MediaMoments.backfill(db, frames) }
                    .onSuccess { if (it > 0) android.util.Log.i(TAG, "filled in media times for $it items") }
                if (reconciled > 0) android.util.Log.i(TAG, "reconciled $reconciled units to current pass priorities")

                // Recount the corpus before rebuilding artifacts: the counts decide which words are
                // distinctive, so rebuilding first would bake in stale (or, on a first run, no)
                // statistics.
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
            val drainStart = now()
            val doneBefore = done
            reason = drain(run.id, stop, deadline, onProgress)
            ai.dusty.finderplus.settings.Diagnostics.record(ai.dusty.finderplus.settings.Diagnostics.SLICE, reason.name, now() - drainStart, "${done - doneBefore} items")
            if (reason != YieldReason.NONE && reason != YieldReason.BUDGET) {
                val h = power.heat()
                ai.dusty.finderplus.settings.Diagnostics.record(
                    ai.dusty.finderplus.settings.Diagnostics.PAUSE, reason.name, 0,
                    "battery ${h.batteryC ?: "?"} °C · status ${h.thermal} · headroom ${h.headroom ?: "?"} · free ${power.availableMb()} MB",
                )
            }

            val stopped = stop.isStopRequested()
            val remaining = db.workUnitDao().remainingCount() > 0
            val status = when {
                stopped -> RunStatus.STOPPED
                !remaining -> RunStatus.DONE
                reason != YieldReason.NONE && reason != YieldReason.BUDGET -> RunStatus.PAUSED
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
     * Refresh search artifacts for items whose profile predates the current format. Bounded per slice
     * so a large library catches up over several Updates instead of blocking one slice.
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
        var starting = true
        while (true) {
            if (stop.isStopRequested()) return YieldReason.NONE
            if (now() >= deadline) return YieldReason.BUDGET
            power.yieldReason(starting).let { if (it != YieldReason.NONE) return it }
            starting = false

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

        // Claim the fast cores for every pass, including speech. A wider cpuset alone does not help
        // ggml: its thread pool waits for the slowest thread on every layer, so a pool straddling big
        // and little cores runs at little-core speed. Affinity pinning is what fixes that.
        booster.ensureBoosted()

        val item = entity.toMediaItem()
        val checkpoint = Checkpoints.decode(unit.checkpoint)
        val writer = ResultWriter(db, item.id, unit.id, WorkLedger.DEFAULT_LEASE_MS, stop, deadline, now)
        try {
            val started = System.nanoTime()
            val outcome = handler.process(item, unit, checkpoint, writer)
            ai.dusty.finderplus.settings.Diagnostics.record(ai.dusty.finderplus.settings.Diagnostics.PASS, handler.pass.name, (System.nanoTime() - started) / 1_000_000, outcome.name)
            when (outcome) {
                PassOutcome.COMPLETED -> {
                    db.withTransaction { ledger.complete(unit.id, handler.pass.version) }
                    done++
                    // New text (a transcript, caption or concept) makes the item's text vectors stale;
                    // requeue them so the semantic leg sees it. A no-op until TEXT_EMBED has run.
                    if (handler.pass.contributesText && handler.pass != ai.dusty.finderplus.model.Pass.TEXT_EMBED) {
                        db.workUnitDao().requeueForItems(ai.dusty.finderplus.model.Pass.TEXT_EMBED.ordinal, listOf(item.id), now())
                    }
                    // Rebuild search artifacts once the item's cheap text passes are through, and again
                    // whenever a later one adds text. Do not wait for every text pass: CONCEPTS cannot
                    // run until every item is embedded, so keyword search would stay empty until then.
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
