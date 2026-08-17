# 06 · On-device Verification (Galaxy S24+, Exynos 2400, Android 15)

The app was built and driven live on a **Samsung SM-S926B** (Exynos 2400 `s5e9945`, arm64-v8a only,
Android 15 / API 35) via adb. This records what was verified and the fixes the real device forced —
several are corrections to assumptions in docs 01–05.

## Verified working (real gallery: 4,926 media items)

| Surface / feature | Result |
|---|---|
| Compose search pop-up | Renders: translucent card, auto-focused field, suggestions, keyboard raised |
| Glance widget provider | Registered; `androidx.glance.session.SessionWorker` runs |
| Permission flow | READ_MEDIA_* + POST_NOTIFICATIONS granted; index enqueues |
| Foreground-service indexing | Runs as `dataSync` FGS with progress notification (id 4201) |
| AI labeling (ML Kit) | 300+ items labeled — `Dog, Selfie, Smile, Screenshot, Bird, Room, Jacket…` |
| OCR (ML Kit) | 170+ text extractions (letters, screenshots incl. code/UI text) |
| Category derivation | `screenshot(48), people(33), document(14), food(10), nature(7)` |
| Consolidated profile | "AI-revision" text per item assembled from name+tags+OCR+album |
| Search ("dog") | Returns **49 photos** with real dog thumbnails, confidence dots, snippets |
| Tap → clipboard | Toast "Copied RDT_2024…​.jpg"; media URI + text placed on clipboard |
| Resume / incremental | Re-triggering after a kill resumes the ledger; `done` grows, no dup work |

## Fixes the device forced (design deltas)

1. **FTS5 → FTS4.** This Samsung system-SQLite ships **without the FTS5 module**, so
   `CREATE VIRTUAL TABLE … USING fts5(…)` aborted and rolled back Room's whole `onCreate` (every table
   gone). Switched to **FTS4** (universally present). Cost: no `bm25()` — the keyword leg orders by
   recency and relevance comes from RRF + the vector legs. To restore FTS5/bm25, bundle a SQLite with
   FTS5 (requery / androidx `sqlite-bundled`). See `04-SEARCH.md`, `FinderDatabase.CREATE_FTS`.

2. **PRAGMAs that return a row can't use `execSQL`.** Android's `execSQL` throws
   *"Queries can be performed using query or rawQuery only"* for `journal_mode=WAL`, `busy_timeout`,
   `mmap_size`, `wal_autocheckpoint`. WAL is now set via the Room builder (`setJournalMode`); the
   value-returning PRAGMAs run through `query().close()`. This was the bug that failed **every** DB open.

3. **Per-kind MediaStore projection.** The Images table has no `DURATION`; the Audio table has no
   `DATE_TAKEN/WIDTH/HEIGHT`. A single all-columns projection threw `IllegalArgumentException` on the
   first row. `MediaStoreReader` now builds the projection per media kind.

4. **Bulk scan.** The first index queried MediaStore **once per item** (~5k single-row queries) — the
   dominant cost. Replaced with `readAll()` (one query per volume) + chunked insert transactions:
   scanning ~5k items dropped from minutes to seconds.

5. **Cheap passes share a priority tier.** With strict global priority, *all* metadata for the gallery
   ran before *any* labeling, so labels didn't appear for ~12 min on a 5k gallery. METADATA/IMAGE_LABEL/
   OCR now share priority 10, so ordered by `(priority, id)` each item is fully text-searchable
   progressively. See `Pass.kt`.

6. **Model affinity only for heavy models.** The claim's `ORDER BY (requires_model = :resident) DESC`
   made no-model (METADATA) units sort ahead of MLKIT (label/OCR) units when nothing heavy was resident
   — re-introducing the "all metadata first" problem. Affinity now applies only when a heavy model
   (CLIP/Whisper/embedder, code ≥ 2) is resident.

7. **WorkManager FGS type in the manifest.** Android 14+/15 requires the app manifest to declare
   `android:foregroundServiceType="dataSync"` on `androidx.work.impl.foreground.SystemForegroundService`
   (merger override), else `startForeground` throws and kills the process. Added.

## Round 2 — why indexing appeared "stuck at 1094 / 19657"

The widget froze at ~1,100 units and never advanced, across several attempts. Four independent causes,
all found from on-device evidence and all fixed:

8. **Progress was being *destroyed*, not stalled (the big one).** `media_generation` was persisted as
   `0` for every row (`toEntity` hardcoded it) while MediaStore reports a real
   `GENERATION_MODIFIED`. So `MediaDiffer` flagged **all 4,928 items as "changed"** on every fresh
   run → purge + re-enqueue → all completed AI work thrown away. Confirmed on device:
   `items with media_generation = 0 : 4928`, and `done` collapsing from 1,781 → 368 when a new run
   started. Fixed by persisting the digest's generation *and* by only comparing generations when both
   sides are non-zero (3 regression tests in `MediaDifferTest`).

9. **WorkManager's ~10-minute limit** killed every run mid-flight (`::timeout-reg` / `::anr` quota
   entries in `dumpsys jobscheduler`). Now indexing runs in 4-minute slices that self-reschedule.

10. **No duty cycle** → CPU pinned, AP at 46 °C with the thermal governor throttling. Now governed by
    `PowerPolicy` (per-unit pause + cool-down between slices, scaled by thermal/charge/level), and the
    slice runs on a `THREAD_PRIORITY_BACKGROUND` thread instead of inheriting the foreground service's
    elevated priority.

11. **Wasted per-unit work**: three `COUNT(*)` over 19,657 rows *per unit* (now in-memory counters),
    each photo decoded twice (now a single-entry `DecodedImageCache`), and FTS+profile rebuilt once per
    pass (now once per item).

See `07-BATTERY-POLICY.md` for the full policy. Verified after the fix: notification shows
`1,766 / 19,657 · 8% · recognizing content` with a working **Stop**, the widget mirrors it live, and
progress advances monotonically across slice boundaries without resetting.

## Toolchain notes (this machine)

- Only `android-36.1` platform was installed; **android-35 platform was fetched** for `compileSdk 35`
  (kept `targetSdk 35` = Android 15). `buildToolsVersion 36.0.0`. Gradle **8.9** wrapper generated to
  pair with AGP **8.7.3** (stable, proven library matrix) rather than the machine's cached AGP 9.2.1 /
  Gradle 9.4.1. arm64-v8a-only ABI filter for the Exynos device.

## Known limitations (not yet addressed)

- **Native models are still stubs**: CLIP/text embedders return zero vectors (search runs FTS-only);
  whisper.cpp `.so` isn't built, so TRANSCRIBE fails per A/V file (caught → marked FAILED, images
  unaffected). Wiring these is the next milestone.
- **Per-unit overhead**: each pass completion rebuilds the item's FTS row + profile in its own
  transaction. Correct but redundant across an item's cheap passes; batching is a follow-up.
- **Full index of ~5k items** on cheap passes takes a few minutes; heavy passes (once real) longer —
  hence the charge-only ASR setting and progressive searchability.
