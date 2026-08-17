package ai.rightone.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.rightone.finderplus.db.entity.WorkUnitEntity

/**
 * The resumable ledger's data access. Every transition here is a single atomic UPDATE so that a
 * process death between any two calls leaves the DB in a valid, recoverable state.
 * See docs/design/01-DB-ENGINE.md §4, §5, §11.
 */
@Dao
interface WorkUnitDao {

    /** Idempotent enqueue: UNIQUE(item_id, pass) makes a repeated insert a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(units: List<WorkUnitEntity>)

    /**
     * Pick the next runnable unit honoring priority first, then model affinity.
     *
     * The ordering matters more than it looks. Affinity exists to amortize the cost of loading a heavy
     * model, so it is the tie-breaker *within* a priority band — but it must not outrank priority
     * itself, or a cheap pass can be starved indefinitely by an expensive one. That is not
     * hypothetical: with affinity sorted first, a resident ASR model made 1,181 queued transcriptions
     * (~30 s each, so roughly ten hours) all run before a single higher-priority image embedding could
     * be claimed, which stalled a whole-gallery re-embed at exactly zero progress.
     *
     * `item_id` sorts above model affinity for the same reason, one level down. Affinity ahead of it
     * groups the *whole gallery's* work by model, so a pass that consumes another pass's output cannot
     * run until every item has finished the producing pass — on a full re-embed that meant zero concept
     * labels for hours. Grouping by item instead finishes each item completely; affinity remains the
     * tiebreak within an item, and the WHERE clause still restricts the candidate set to the resident
     * model, so a loaded model is not swapped out prematurely.
     */
    @Query(
        """
        SELECT id FROM work_unit
        WHERE state = 0
          AND (:affinityOnly = 0 OR :resident < 2 OR requires_model = :resident OR requires_model IN (0, 1))
        ORDER BY priority ASC,
                 item_id ASC,
                 (CASE WHEN :resident >= 2 AND requires_model = :resident THEN 0 ELSE 1 END),
                 id ASC
        LIMIT 1
        """
    )
    suspend fun pickNext(resident: Int, affinityOnly: Boolean): Long?

    /** Atomically claim [id] only if still PENDING. Returns rows affected (1 = won, 0 = lost race). */
    @Query(
        """
        UPDATE work_unit
        SET state = 1, lease_owner = :runId, lease_expires_at = :now + :leaseMs,
            attempt_count = attempt_count + 1, updated_at = :now
        WHERE id = :id AND state = 0
        """
    )
    suspend fun claim(id: Long, runId: String, now: Long, leaseMs: Long): Int

    @Query("UPDATE work_unit SET state = 2, updated_at = :now WHERE id = :id")
    suspend fun markRunning(id: Long, now: Long)

    /** Cooperative-stop release: hand the unit back to PENDING immediately, preserving its checkpoint. */
    @Query("UPDATE work_unit SET state = 0, lease_owner = NULL, updated_at = :now WHERE id = :id")
    suspend fun release(id: Long, now: Long)

    /** Prerequisite missing (e.g. speech model not installed): park the unit without failing it. */
    @Query(
        "UPDATE work_unit SET state = 5, checkpoint = NULL, lease_owner = NULL, last_error = :reason, updated_at = :now WHERE id = :id"
    )
    suspend fun skip(id: Long, reason: String, now: Long)

    /** Re-queue previously skipped units for [pass] — used once its model finishes downloading. */
    @Query(
        "UPDATE work_unit SET state = 0, attempt_count = 0, last_error = NULL, updated_at = :now WHERE pass = :pass AND state = 5"
    )
    suspend fun requeueSkipped(pass: Int, now: Long): Int

    @Query("SELECT COUNT(*) FROM work_unit WHERE pass = :pass AND state IN (0, 1, 2)")
    suspend fun pendingForPass(pass: Int): Int

    /**
     * Re-run [pass] for specific items. Used to *reconsider* — e.g. after a label is rejected, every
     * item that carries it gets its concepts re-derived against the updated prototype, without
     * touching the rest of the gallery.
     */
    @Query("UPDATE work_unit SET state = 0, checkpoint = NULL, lease_owner = NULL, updated_at = :now WHERE pass = :pass AND item_id IN (:itemIds) AND state IN (3, 5)")
    suspend fun requeueForItems(pass: Int, itemIds: List<Long>, now: Long): Int

    /**
     * Advance the sub-item resume cursor and renew the lease. MUST be called in the same
     * transaction that committed the partial results up to [cursor] so the invariant
     * "checkpoint never ahead of committed results" holds. See §4.2.
     */
    @Query(
        "UPDATE work_unit SET checkpoint = :cursor, lease_expires_at = :now + :leaseMs, updated_at = :now WHERE id = :id"
    )
    suspend fun checkpoint(id: Long, cursor: String, now: Long, leaseMs: Long)

    @Query(
        "UPDATE work_unit SET state = 3, checkpoint = NULL, pipeline_version = :version, lease_owner = NULL, updated_at = :now WHERE id = :id"
    )
    suspend fun complete(id: Long, version: Int, now: Long)

    /** Fail: back to PENDING for another attempt, or terminally FAILED once attempts are exhausted. */
    @Query(
        """
        UPDATE work_unit
        SET state = CASE WHEN attempt_count >= max_attempts THEN 4 ELSE 0 END,
            last_error = :error, lease_owner = NULL, updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun fail(id: Long, error: String, now: Long)

    /** Startup reconciliation: reclaim units abandoned by a crashed worker (expired lease). See §5. */
    @Query(
        """
        UPDATE work_unit
        SET state = 0, lease_owner = NULL, updated_at = :now
        WHERE state IN (1, 2) AND (lease_expires_at IS NULL OR lease_expires_at < :now)
        """
    )
    suspend fun reclaimOrphans(now: Long): Int

    /** Requeue only stale-version units for [pass] after a model upgrade (selective re-index). §9. */
    @Query(
        """
        UPDATE work_unit
        SET state = 0, checkpoint = NULL, lease_owner = NULL, updated_at = :now
        WHERE pass = :pass AND pipeline_version < :currentVersion AND state = 3
        """
    )
    suspend fun requeueStaleVersion(pass: Int, currentVersion: Int, now: Long): Int

    /**
     * Re-apply a pass's current priority and residency to its already-enqueued rows.
     *
     * Both are denormalized into `work_unit` at enqueue time so the claim query can order without a
     * join. That makes them stale the moment a pass definition changes, and the failure is silent:
     * rows keep the old priority forever and run in the wrong order — which is how a re-tiered pass
     * appeared to be ignored entirely. Cheap to run on every scan, so it is.
     */
    @Query("UPDATE work_unit SET priority = :priority, requires_model = :requiresModel WHERE pass = :pass AND (priority != :priority OR requires_model != :requiresModel)")
    suspend fun reconcilePassMetadata(pass: Int, priority: Int, requiresModel: Int): Int

    /** Reset a changed item's units to PENDING (used by incremental diff when a file is modified). */
    @Query(
        "UPDATE work_unit SET state = 0, checkpoint = NULL, lease_owner = NULL, attempt_count = 0, updated_at = :now WHERE item_id = :itemId"
    )
    suspend fun resetForItem(itemId: Long, now: Long)

    @Query("SELECT * FROM work_unit WHERE id = :id")
    suspend fun byId(id: Long): WorkUnitEntity?

    @Query("SELECT COUNT(*) FROM work_unit WHERE state = 3")
    suspend fun doneCount(): Int

    @Query("SELECT COUNT(*) FROM work_unit WHERE state = 4")
    suspend fun failedCount(): Int

    @Query("SELECT COUNT(*) FROM work_unit")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM work_unit WHERE state IN (0, 1, 2)")
    suspend fun remainingCount(): Int

    /** How many required passes remain for one item — used to project media_item.index_state. */
    @Query("SELECT COUNT(*) FROM work_unit WHERE item_id = :itemId AND state IN (0, 1, 2)")
    suspend fun remainingForItem(itemId: Long): Int

    @Query("SELECT COUNT(*) FROM work_unit WHERE item_id = :itemId AND state = 4")
    suspend fun failedForItem(itemId: Long): Int

    /**
     * Outstanding **cheap** text passes for this item: METADATA=0, IMAGE_LABEL=1, OCR=2, KEYFRAMES=3,
     * OBJECTS=7, FACES=8. All share the low priority tiers, so they finish close together and an item
     * becomes keyword-searchable as soon as they do.
     *
     * TRANSCRIBE=5 and CONCEPTS=9 are deliberately **excluded**, and this is the whole point of the
     * query. They sit in far later tiers — concepts cannot run until every keyframe in the gallery is
     * embedded — so including them made "is this item finished?" mean "is the entire gallery finished?".
     * Measured consequence on a live index: 4,847 items with metadata, OCR and faces committed, 2,427 OCR
     * documents, 27,496 tags, and **zero** FTS rows and zero profiles, because one pass per item was
     * permanently outstanding. Nothing was keyword-searchable at all.
     *
     * The later passes still refresh the artifacts when they land — see the orchestrator, which rebuilds
     * on any text pass completing once the cheap ones are through. Rebuilding is idempotent and pure SQL
     * over rows already committed, so doing it more than once per item is cheap; never doing it is not.
     */
    @Query("SELECT COUNT(*) FROM work_unit WHERE item_id = :itemId AND state IN (0,1,2) AND pass IN (0,1,2,3,7,8)")
    suspend fun remainingCheapTextPassesForItem(itemId: Long): Int
}
