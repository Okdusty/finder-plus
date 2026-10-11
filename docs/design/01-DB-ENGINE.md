# 01 · Flagship: The Resume/Stop Indexing DB & Engine

This is the core of finder+. It guarantees that indexing the whole gallery is **crash-safe**, **resumable to the sub-item level**, **stoppable near-instantly**, and **idempotent** — while coordinating heavy local AI models (whisper.cpp, CLIP, embedders, ML Kit) so only one big model is resident at a time and cheap passes finish before expensive ones.

The design principle in one line:

> D**The database is the only source of truth for progress. The engine keeps no essential state that cannot be rebuilt from a query. A process death loses only the single in-flight micro-batch, which is bounded and safe to redo.**

---

## 1. Why a "specialized" DB

A naïve indexer keeps a `for file in files` loop and a boolean `indexed` column. That fails every hard requirement: killing the app loses the loop position, a 1-hour recording restarts from zero, a model upgrade forces a full re-index, and pressing "update" twice double-processes.

finder+ instead models indexing as a **durable work ledger**: every unit of work is a row with an atomic state, a lease, and a resumable cursor. Progress is a set of committed row transitions, not a program counter. That is what makes kill/stop/resume trivial: on restart the engine just asks the DB "what's left?".

---

## 2. Schema (Room / SQLite)

All tables are Room `@Entity`. DDL shown as SQLite for precision.

### 2.1 `media_item` — inventory mirror of MediaStore

```sql
CREATE TABLE media_item (
  id                INTEGER PRIMARY KEY,          -- = MediaStore _ID (stable per volume)
  content_uri       TEXT    NOT NULL,
  kind              INTEGER NOT NULL,             -- 0 IMAGE, 1 VIDEO, 2 AUDIO
  display_name      TEXT,
  mime              TEXT,
  size_bytes        INTEGER NOT NULL DEFAULT 0,
  date_taken        INTEGER,                      -- epoch ms
  date_modified     INTEGER NOT NULL,             -- MediaStore DATE_MODIFIED (seconds)
  media_generation  INTEGER NOT NULL DEFAULT 0,   -- MediaStore.getGeneration() bucket
  duration_ms       INTEGER,                      -- video/audio only
  width             INTEGER,
  height            INTEGER,
  lat               REAL,
  lon               REAL,
  place             TEXT,                          -- offline reverse-geocoded label
  bucket_id         INTEGER,                       -- album/folder id
  bucket_name       TEXT,
  content_hash      TEXT,                          -- optional, lazy (change detection fallback)
  index_state       INTEGER NOT NULL DEFAULT 0,    -- 0 NEW,1 PARTIAL,2 DONE,3 FAILED,4 STALE
  pipeline_version  INTEGER NOT NULL DEFAULT 0,    -- max applied pipeline version
  first_seen_at     INTEGER NOT NULL,
  last_scanned_at   INTEGER NOT NULL,
  deleted           INTEGER NOT NULL DEFAULT 0     -- soft-delete tombstone during purge
);
CREATE INDEX ix_item_state  ON media_item(index_state) WHERE deleted = 0;
CREATE INDEX ix_item_bucket ON media_item(bucket_id);
CREATE INDEX ix_item_date   ON media_item(date_taken);
```

### 2.2 `work_unit` — **the resumable ledger (heart of the engine)**

One row per `(item_id, pass)`. This is where resume/stop lives.

```sql
CREATE TABLE work_unit (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id           INTEGER NOT NULL REFERENCES media_item(id) ON DELETE CASCADE,
  pass              INTEGER NOT NULL,   -- see Pass enum §3
  state             INTEGER NOT NULL DEFAULT 0, -- 0 PENDING,1 CLAIMED,2 RUNNING,3 DONE,4 FAILED,5 SKIPPED
  priority          INTEGER NOT NULL,   -- lower = earlier (cheap passes first)
  requires_model    INTEGER NOT NULL DEFAULT 0, -- 0 NONE,1 MLKIT,2 CLIP_IMG,3 WHISPER,4 TEXT_EMB
  checkpoint        TEXT,               -- resumable cursor (JSON/scalar), NULL when not started/done
  attempt_count     INTEGER NOT NULL DEFAULT 0,
  max_attempts      INTEGER NOT NULL DEFAULT 4,
  lease_owner       TEXT,               -- run-id of the worker holding it
  lease_expires_at  INTEGER,            -- epoch ms; past = reclaimable
  pipeline_version  INTEGER NOT NULL DEFAULT 0,
  last_error        TEXT,
  updated_at        INTEGER NOT NULL,
  UNIQUE(item_id, pass)                 -- makes enqueue idempotent (INSERT OR IGNORE)
);
-- The claim hot-path index: find the next runnable unit cheaply.
CREATE INDEX ix_wu_claim ON work_unit(state, priority, requires_model, id);
CREATE INDEX ix_wu_item  ON work_unit(item_id);
```

`checkpoint` holds the **sub-item resume cursor**:
- `TRANSCRIBE` → `{"nextChunkStartMs": 1830000, "lang": "tr", "partialDocId": 42}` — resume a 1-hour file mid-file.
- `KEYFRAMES` → `{"nextFrameIndex": 7, "totalFrames": 20}` — resume a video mid-scan.
- Cheap passes (single-shot) → `NULL`.

### 2.3 `index_run` — one row per "update" press / scheduled run

```sql
CREATE TABLE index_run (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  trigger        INTEGER NOT NULL,     -- 0 MANUAL,1 SCHEDULED,2 BOOT_RESUME
  status         INTEGER NOT NULL,     -- 0 SCANNING,1 RUNNING,2 PAUSED,3 STOPPING,4 STOPPED,5 DONE,6 FAILED
  stop_requested INTEGER NOT NULL DEFAULT 0,   -- cooperative-cancellation flag (also mirrored in RAM)
  total_units    INTEGER NOT NULL DEFAULT 0,
  done_units     INTEGER NOT NULL DEFAULT 0,
  failed_units   INTEGER NOT NULL DEFAULT 0,
  started_at     INTEGER NOT NULL,
  finished_at    INTEGER,
  last_generation INTEGER NOT NULL DEFAULT 0    -- MediaStore generation watermark for fast-skip
);
```

### 2.4 Derived content (search corpus)

```sql
CREATE TABLE tag (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id INTEGER NOT NULL REFERENCES media_item(id) ON DELETE CASCADE,
  source INTEGER NOT NULL,     -- 0 LABEL,1 OBJECT,2 OCR_KEYWORD,3 CATEGORY,4 USER
  label TEXT NOT NULL,
  confidence REAL NOT NULL DEFAULT 1.0,
  UNIQUE(item_id, source, label)      -- re-running a pass converges, never duplicates
);

CREATE TABLE document (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id INTEGER NOT NULL REFERENCES media_item(id) ON DELETE CASCADE,
  source INTEGER NOT NULL,     -- 0 OCR, 1 TRANSCRIPT
  lang TEXT,
  text TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(item_id, source)
);

CREATE TABLE segment (             -- timestamped A/V transcript + keyframe OCR = "where in the media"
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  document_id INTEGER NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  item_id INTEGER NOT NULL,
  start_ms INTEGER NOT NULL,
  end_ms INTEGER NOT NULL,
  text TEXT NOT NULL,
  UNIQUE(item_id, source_ref, start_ms)   -- idempotent per-chunk re-commit
  -- source_ref distinguishes transcript vs keyframe streams
);

CREATE TABLE embedding (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id INTEGER NOT NULL REFERENCES media_item(id) ON DELETE CASCADE,
  kind INTEGER NOT NULL,       -- 0 IMAGE_CLIP, 1 TEXT_TRANSCRIPT
  source_ref INTEGER NOT NULL DEFAULT 0,  -- keyframe index or transcript chunk index
  dim INTEGER NOT NULL,
  model_id TEXT NOT NULL,
  vec BLOB NOT NULL,           -- float32[dim], L2-normalized at write time
  UNIQUE(item_id, kind, source_ref)
);
CREATE INDEX ix_emb_kind ON embedding(kind);
```

### 2.5 Full-text index (FTS5, external-content, trigger-synced)

```sql
CREATE VIRTUAL TABLE media_fts USING fts5(
  name, tags, ocr, transcript, place, bucket,
  content='',                 -- contentless: we control inserts
  tokenize = 'unicode61 remove_diacritics 2'
);
```

FTS rows are (re)built per item by the engine after its text-producing passes commit, keyed by `rowid = item_id`, so a re-index replaces the row (`INSERT OR REPLACE`). This avoids trigger fan-out on every tag insert and keeps FTS writes batched. (Trigger-based sync is an alternative documented in §9; the contentless+manual-rebuild path is chosen for write-amplification control.)

---

## 3. Passes and their ordering

```kotlin
enum class Pass(val priority: Int, val model: RequiredModel) {
  METADATA   (10, RequiredModel.NONE),     // EXIF, geocode, category-from-path  (all kinds)
  IMAGE_LABEL(20, RequiredModel.MLKIT),    // ML Kit labels/objects              (image, keyframes)
  OCR        (30, RequiredModel.MLKIT),    // ML Kit Text Recognition v2         (image, keyframes)
  KEYFRAMES  (40, RequiredModel.NONE),     // extract frames                     (video) -> spawns label/ocr/embed per frame
  IMAGE_EMBED(50, RequiredModel.CLIP_IMG), // CLIP image embedding               (image, keyframes)
  TRANSCRIBE (60, RequiredModel.WHISPER),  // whisper.cpp multilingual ASR       (video, audio)
  TEXT_EMBED (70, RequiredModel.TEXT_EMB), // sentence embeddings of transcripts (video, audio)
}
```

**Cheap-before-expensive** falls straight out of `priority`: the claim query orders by it, so metadata → labels → OCR complete across the whole gallery before whisper ever loads. Search becomes useful within minutes; items sit at `index_state = PARTIAL` until their heavy passes finish.

Which passes are enqueued per item is decided at scan time by `kind`:
- IMAGE → METADATA, IMAGE_LABEL, OCR, IMAGE_EMBED
- VIDEO → METADATA, KEYFRAMES(→per-frame label/ocr/embed), TRANSCRIBE, TEXT_EMBED
- AUDIO → METADATA, TRANSCRIBE, TEXT_EMBED

---

## 4. The item / work-unit state machine

```
 enqueue: INSERT OR IGNORE (item_id, pass) state=PENDING
                       │
                       ▼
                   ┌────────┐   claim (atomic)    ┌────────┐   begin    ┌─────────┐
                   │PENDING │ ──────────────────► │CLAIMED │ ─────────► │ RUNNING │
                   └────────┘                     └────────┘            └────┬────┘
                       ▲                              │                      │ checkpoint(cursor)   ← atomic, periodic
                       │ requeue (attempt<max)        │ lease expiry         │ (partial results already committed)
                       │                              │ (reconcile)          │
                       │                              ▼                      ▼
                       └────────────────────────── PENDING           ┌──────────────┐
                                                                      │ commit result│
                                             stop_requested? ────────►│  + state:    │
                                             (at checkpoint boundary) │  DONE        │
                                             release lease→PENDING    │  or FAILED   │
                                                                      └──────────────┘
```

Item `index_state` is a **projection** of its work_units, recomputed when any unit completes:
- all required units `DONE`/`SKIPPED` → `DONE`
- some `DONE`, some pending/running → `PARTIAL`
- any at `max_attempts` `FAILED` → `FAILED` (still searchable on whatever succeeded)

### 4.1 Atomic claim (single-writer safe)

```sql
-- 1) pick a candidate honoring priority + model affinity (prefer already-resident model)
SELECT id FROM work_unit
 WHERE state = 0                                   -- PENDING
   AND (requires_model = :residentModel OR :residentModel = 0 OR NOT :affinityOnly)
 ORDER BY (requires_model = :residentModel) DESC,  -- affinity boost: avoid model thrash
          priority, id
 LIMIT 1;

-- 2) claim it, but only if still PENDING (optimistic; retry on 0 rows)
UPDATE work_unit
   SET state = 1, lease_owner = :runId,
       lease_expires_at = :now + :leaseMs,
       attempt_count = attempt_count + 1, updated_at = :now
 WHERE id = :id AND state = 0;
```

SQLite has a single writer; Room serializes writes, so two coroutine workers never claim the same row (the second `UPDATE ... WHERE state=0` affects 0 rows and retries).

### 4.2 Checkpoint (the sub-item resume guarantee)

```sql
-- called every micro-batch of a heavy pass, INSIDE the same transaction that
-- committed the partial results up to :cursor. Invariant: checkpoint never > committed results.
UPDATE work_unit
   SET checkpoint = :cursorJson, lease_expires_at = :now + :leaseMs, updated_at = :now
 WHERE id = :id;
```

### 4.3 Complete / fail

```sql
UPDATE work_unit SET state = 3, checkpoint = NULL,
       pipeline_version = :ver, updated_at = :now WHERE id = :id;   -- DONE

UPDATE work_unit
   SET state = CASE WHEN attempt_count >= max_attempts THEN 4 ELSE 0 END,  -- FAILED or back to PENDING
       last_error = :err, lease_owner = NULL, updated_at = :now
 WHERE id = :id;
```

---

## 5. Resume protocol

On **every** engine start (fresh process after a kill, WorkManager restart, or a new "update" press):

1. **Reclaim orphans.** A crashed process cannot renew leases, so its units time out:
   ```sql
   UPDATE work_unit
      SET state = 0, lease_owner = NULL, updated_at = :now
    WHERE state IN (1,2)               -- CLAIMED or RUNNING
      AND (lease_expires_at IS NULL OR lease_expires_at < :now);
   ```
   `checkpoint` is untouched, so the pass resumes from its cursor, not from zero.
2. **Rebuild the run.** Create/attach an `index_run`, recount `total/done` from `work_unit`.
3. **Drain.** Loop: claim next PENDING (§4.1) → dispatch to the pass handler, which reads `checkpoint` and continues (e.g. whisper seeks to `nextChunkStartMs`). Commit micro-batches. Recompute item state on completion.

Because partial results are already committed and the checkpoint is never ahead of them, resume is exact: **no lost work, no duplicated work.** Re-processing the current micro-batch is safe by construction (idempotent writes, §6).

---

## 6. Idempotency (why re-running never corrupts)

Every write that a pass emits is convergent:
- **Enqueue**: `INSERT OR IGNORE` on `UNIQUE(item_id, pass)`.
- **Tags**: `INSERT OR REPLACE` on `UNIQUE(item_id, source, label)`; a pass first `DELETE FROM tag WHERE item_id=? AND source=?` then inserts its full set → re-run replaces.
- **Documents / segments**: `UNIQUE(item_id, source[, start_ms])`; a resumed chunk re-writes the same `(item_id, start_ms)` row.
- **Embeddings**: `UNIQUE(item_id, kind, source_ref)`.
- **FTS**: contentless row rebuilt with `rowid = item_id`.

Therefore: run a pass twice, resume it mid-way, or crash and redo the last chunk — the DB converges to the identical state. This is the formal backbone of "idempotent."

---

## 7. Stop protocol (cooperative, near-instant)

Stop is not a thread-kill. It is a flag the workers honor at safe boundaries:

1. Widget/notification "Stop" → `UPDATE index_run SET stop_requested=1, status=3` **and** flip an in-memory `AtomicBoolean` (fast path; DB flag is the durable backstop).
2. Workers check the flag at three points: **before claiming** the next unit, **between micro-batches** inside a unit (per keyframe / per audio chunk), and **never mid-transaction**.
3. On observing stop: commit the current checkpoint (already at a txn boundary), set the unit back to `PENDING` (lease released), set `index_run.status = STOPPED`, unload the resident model, return.

**Guarantee:** all progress up to the last checkpoint is durable. **Worst-case lost work** = one micro-batch: a single image, one video keyframe, or one ≤30 s audio chunk. A subsequent "update" resumes seamlessly via §5.

---

## 8. Incremental update (diff / purge)

Pressing "update" runs a diff, not a full re-scan:

1. **Fast-skip:** read `MediaStore.getGeneration(volume)`. If unchanged vs `index_run.last_generation` and no forced rebuild → nothing to do; still resume any leftover PENDING units.
2. **Scan:** project `(_ID, DATE_MODIFIED, generation, size)` for all media.
3. Diff against `media_item`:
   - **new `_ID`** → INSERT item (`index_state=NEW`) + `INSERT OR IGNORE` its passes.
   - **changed** (`DATE_MODIFIED`/generation/size differ) → mark `STALE`, `DELETE` derived rows for it, reset its `work_unit`s to `PENDING`.
   - **missing** (in DB, absent from MediaStore) → **purge**: cascade-delete tags/documents/segments/embeddings/work_units (via FK `ON DELETE CASCADE`) + FTS row, then delete `media_item`. Purge is idempotent and re-entrant.
4. Update `last_generation` watermark.

Handles Android 14 **partial photo access** (`READ_MEDIA_VISUAL_USER_SELECTED`): the scan simply sees fewer items; the widget surfaces "partial access" so the "whole gallery" promise is honestly qualified.

---

## 9. Local-model coordination

- **Ordering:** `priority` guarantees cheap→expensive globally.
- **Single heavy model resident:** a `ModelCoordinator` owns a mutex; only one of {whisper, CLIP, embedder} is loaded at once. ML Kit is lightweight and exempt. The claim query's **affinity boost** (`requires_model = :residentModel DESC`) drains all units needing the resident model before swapping, eliminating load/unload thrash.
- **Memory/thermal guards:** under `onTrimMemory`/thermal throttling the coordinator unloads the model at the next checkpoint boundary and the run pauses (status `PAUSED`), resuming when pressure clears. ASR can be gated to "only while charging" (user setting) since whisper is the heaviest pass.
- **Model/pipeline versioning:** each `Pass` has a version constant. A model upgrade bumps it; a maintenance sweep requeues **only** units where `work_unit.pipeline_version < currentVersion(pass)` — selective, not global, re-index.

---

## 10. PRAGMAs, transactions, write amplification

```sql
PRAGMA journal_mode = WAL;          -- concurrent readers during indexing (search stays live)
PRAGMA synchronous  = NORMAL;       -- safe with WAL; far fewer fsyncs than FULL
PRAGMA busy_timeout = 5000;
PRAGMA foreign_keys = ON;
PRAGMA temp_store   = MEMORY;
PRAGMA mmap_size    = 268435456;    -- 256 MB
PRAGMA wal_autocheckpoint = 1000;
```

- **Batch boundaries:** cheap passes commit ~8 items/txn (amortize fsync). Heavy A/V passes commit **per checkpoint** (per keyframe / per audio chunk) — the checkpoint update and its partial results share one transaction, preserving the §4.2 invariant.
- **FTS** rebuilt in the same txn as the item's text passes complete → no separate write storm.
- Periodic `PRAGMA wal_checkpoint(TRUNCATE)` between waves keeps the WAL bounded.
- **Cancellation cadence:** the stop flag is polled once per micro-batch — cheap (an atomic read), and always at a txn boundary so a stop never tears a write.

---

## 11. Kotlin surface (implemented in `core-db` + `engine-index`)

```kotlin
// ---- Entities (Room) ----
@Entity(tableName = "work_unit",
        indices = [Index("state","priority","requires_model","id"),
                   Index("item_id"), Index(value=["item_id","pass"], unique=true)])
data class WorkUnitEntity(
  @PrimaryKey(autoGenerate=true) val id: Long = 0,
  val itemId: Long, val pass: Int, val state: Int, val priority: Int,
  val requiresModel: Int, val checkpoint: String?, val attemptCount: Int,
  val maxAttempts: Int, val leaseOwner: String?, val leaseExpiresAt: Long?,
  val pipelineVersion: Int, val lastError: String?, val updatedAt: Long,
)

// ---- DAO: the claim/checkpoint/complete hot path ----
@Dao interface WorkUnitDao {
  @Query("""SELECT id FROM work_unit WHERE state=0
            AND (requires_model=:resident OR :resident=0 OR :affinityOnly=0)
            ORDER BY (requires_model=:resident) DESC, priority, id LIMIT 1""")
  suspend fun pickNext(resident: Int, affinityOnly: Boolean): Long?

  @Query("""UPDATE work_unit SET state=1, lease_owner=:run,
            lease_expires_at=:now+:leaseMs, attempt_count=attempt_count+1, updated_at=:now
            WHERE id=:id AND state=0""")
  suspend fun claim(id: Long, run: String, now: Long, leaseMs: Long): Int   // 1 = won

  @Query("UPDATE work_unit SET state=2, updated_at=:now WHERE id=:id")
  suspend fun markRunning(id: Long, now: Long)

  @Query("UPDATE work_unit SET checkpoint=:cursor, lease_expires_at=:now+:leaseMs, updated_at=:now WHERE id=:id")
  suspend fun checkpoint(id: Long, cursor: String, now: Long, leaseMs: Long)

  @Query("UPDATE work_unit SET state=3, checkpoint=NULL, pipeline_version=:ver, updated_at=:now WHERE id=:id")
  suspend fun complete(id: Long, ver: Int, now: Long)

  @Query("""UPDATE work_unit
            SET state=CASE WHEN attempt_count>=max_attempts THEN 4 ELSE 0 END,
                last_error=:err, lease_owner=NULL, updated_at=:now WHERE id=:id""")
  suspend fun fail(id: Long, err: String, now: Long)

  @Query("""UPDATE work_unit SET state=0, lease_owner=NULL, updated_at=:now
            WHERE state IN (1,2) AND (lease_expires_at IS NULL OR lease_expires_at<:now)""")
  suspend fun reclaimOrphans(now: Long): Int

  @Query("SELECT * FROM work_unit WHERE id=:id") suspend fun byId(id: Long): WorkUnitEntity?
  @Query("SELECT COUNT(*) FROM work_unit WHERE state=3") suspend fun doneCount(): Int
  @Query("SELECT COUNT(*) FROM work_unit") suspend fun totalCount(): Int
}

// ---- Cooperative cancellation ----
class StopSignal(private val runId: Long, private val runDao: IndexRunDao) {
  private val flag = AtomicBoolean(false)
  fun request() { flag.set(true) }                       // fast path
  suspend fun isStopRequested(): Boolean =
    flag.get() || runDao.isStopRequested(runId)          // durable backstop
}

// ---- Resumable cursor a heavy pass reads/writes ----
sealed interface Checkpoint {
  data class Transcribe(val nextChunkStartMs: Long, val lang: String?, val partialDocId: Long?) : Checkpoint
  data class Keyframes(val nextFrameIndex: Int, val totalFrames: Int) : Checkpoint
  data object None : Checkpoint
}

// ---- The orchestrator ----
interface IndexOrchestrator {
  /** Idempotent. Reconciles orphans, then drains PENDING work honoring priority + model affinity,
   *  checkpointing heavy passes, until the queue is empty or [stop] is requested. Safe to call
   *  again after any crash — it resumes from the DB. */
  suspend fun run(trigger: Trigger, stop: StopSignal)
  fun progress(): Flow<IndexProgress>   // total/done/failed/currentPass -> widget + notification
}

// ---- A pass handler: reads checkpoint, emits committed micro-batches ----
interface PassHandler {
  val pass: Pass
  suspend fun process(item: MediaItem, unit: WorkUnitEntity, cp: Checkpoint,
                      emit: suspend (PartialResult, Checkpoint) -> Unit,  // commits result+cursor atomically
                      stop: StopSignal)
}
```

The `emit` callback is the linchpin: a handler computes one micro-batch (one audio chunk's transcript segments, say), calls `emit(result, newCursor)`, and the engine writes the result **and** the advanced checkpoint in a single transaction. The handler never touches the DB directly, so the §4.2 invariant is impossible to violate.

---

## 12. Resume/stop test matrix

| # | Kill / stop point | Persisted before event | On restart, expected recovery | Duplication risk |
|---|---|---|---|---|
| 1 | After `claim`, before `markRunning` | lease row | orphan reclaimed by lease expiry → re-claim | none |
| 2 | Mid-video, after keyframe 3/20 committed | frames 0–3 tags/embeds + `checkpoint.nextFrameIndex=4` | resume at frame 4 | frames 0–3 not reprocessed |
| 3 | Mid-audio, after chunk `[0,1800s)` committed | segments 0–1800s + `nextChunkStartMs=1800000` | whisper seeks to 1800s, continues | `UNIQUE(item_id,start_ms)` blocks dup segments |
| 4 | After OCR `DONE`, before `IMAGE_EMBED` | OCR doc + FTS row | claim `IMAGE_EMBED` (still PENDING) | none |
| 5 | During purge of a deleted item | partial cascade delete | purge is re-entrant; finishes on next run | none |
| 6 | Forced **Stop** during whisper chunk | last committed chunk + checkpoint | status STOPPED; next update resumes at cursor | none |
| 7 | Power loss mid-transaction | WAL not yet committed | SQLite WAL rolls back the torn txn | atomic — partial txn discarded |
| 8 | Model upgrade (pipeline bump) | old embeddings at v1 | maintenance requeues only `pipeline_version<v2` units | selective, not full |
| 9 | Double "update" tap | run A in progress | WorkManager `KEEP` dedups; run B is a no-op | unique work name |

---

## 13. Failure & backoff

- Per-unit `attempt_count`/`max_attempts` (default 4) with exponential backoff via `lease_expires_at`; exhausted → `FAILED`, item still searchable on partial results.
- A poison item (repeated OOM on a giant video) is quarantined at `FAILED` and never blocks the queue.
- `last_error` retained for diagnostics surfaced in Settings → index stats.

This engine is what makes the rest of finder+ possible: the AI passes can be as slow and heavy as they need to be, because the ledger makes their progress durable, interruptible, and exact.
