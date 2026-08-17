# finder+ — Project Plan

On-device AI media indexer for Android. Scans the user's gallery (images, videos, audio), extracts every findable signal — labels, objects, OCR text, speech transcripts, metadata — into a local searchable database, and exposes search **only** through a home-screen widget and a lightweight pop-up search bar. No full app GUI. 100% offline / on-device; no media or text ever leaves the phone.

---

## 1. Product definition

### 1.1 What it does
- **Indexes the whole gallery** into a local DB: every image, video, and audio file gets categorized, tagged, labelled, and (where applicable) transcribed.
- **"Internal SEO"** = a search index built from all extracted context per file: AI labels, detected objects, OCR text, speech transcripts, file metadata (date, location, album, camera), so any file can be located by describing its content.
- **Multi-language speech recognition** on video/audio files — transcripts stored in the DB so spoken content is searchable later.
- **Manual update trigger**: a refresh/update button (on the widget) re-scans the gallery. First run indexes everything; later runs are incremental — only new/changed files are added, and interrupted runs resume where they stopped.

### 1.2 What it deliberately is NOT
- No full-frame app UI. The only surfaces are:
  1. **Home-screen widget** — search field + update button + indexing status.
  2. **Search pop-up** — a floating, translucent dialog-style panel showing the query box and a result grid (thumbnails + confidence), overlaid on whatever the user is doing. Tapping a result opens the file in the system gallery/player via Intent.
  3. **Minimal settings sheet** (unavoidable): permission onboarding, model download/selection, index management. Implemented as a dialog-themed activity, not a full app frame.
- No cloud APIs, no accounts, no network access for inference (network only for optional one-time model downloads).

### 1.3 Primary user flows
1. **First run**: tap widget → permission onboarding (media access, notifications) → optional model download → "Build index" → foreground service indexes gallery with progress notification + widget progress.
2. **Search**: tap widget search bar → pop-up opens with keyboard up → type ("kids at the beach", "invoice from Vodafone", "the voicemail about the rent") → ranked thumbnails appear live → tap → file opens in gallery.
3. **Update**: press update button → incremental scan: diff MediaStore vs. DB, index new/modified files, purge deleted ones, resume any half-finished batch.

---

## 2. Architecture overview

```
┌─────────────────────────────────────────────────────────┐
│  UI surfaces                                            │
│  ├─ Glance AppWidget (search bar, update btn, status)   │
│  ├─ SearchPopupActivity (translucent dialog theme)      │
│  └─ SettingsActivity (dialog theme, onboarding/models)  │
├─────────────────────────────────────────────────────────┤
│  Domain                                                 │
│  ├─ SearchEngine  (hybrid FTS + vector ranking)         │
│  └─ IndexOrchestrator (scan diff, batching, resume)     │
├─────────────────────────────────────────────────────────┤
│  AI pipeline (all on-device)                            │
│  ├─ ImageAnalyzer   labels / objects / OCR / embedding  │
│  ├─ VideoAnalyzer   keyframes → ImageAnalyzer + audio   │
│  ├─ AudioAnalyzer   Whisper multilingual ASR            │
│  └─ TextEmbedder    query & transcript embeddings       │
├─────────────────────────────────────────────────────────┤
│  Data                                                   │
│  ├─ MediaStore reader (diff by id + generation)         │
│  ├─ Room + SQLite FTS5 (text index)                     │
│  └─ Vector store (embeddings, cosine search)            │
├─────────────────────────────────────────────────────────┤
│  Infra                                                  │
│  └─ WorkManager + Foreground Service (indexing jobs)    │
└─────────────────────────────────────────────────────────┘
```

### 2.1 Tech stack
| Concern | Choice | Notes |
|---|---|---|
| Language / min SDK | Kotlin, minSdk 26, target latest | Coroutines + Flow throughout |
| Widget | Jetpack **Glance** | Compose-style widget API |
| Pop-up UI | Compose in a `Dialog`-themed translucent Activity | `excludeFromRecents`, `launchMode=singleTop` |
| DB | **Room + SQLite FTS5** | BM25 full-text over all extracted text |
| Vector search | Embeddings as BLOBs + brute-force cosine (start), `sqlite-vec` or HNSW later | ~10–50k files is fine brute-force with SIMD |
| Image labeling | **ML Kit Image Labeling** (on-device, free, 400+ labels) → upgrade path: custom TFLite/LiteRT classifier | |
| Object detection | ML Kit Object Detection or a LiteRT EfficientDet-Lite | optional pass |
| OCR | **ML Kit Text Recognition v2** | Latin/CJK/Devanagari/Korean/Cyrillic scripts |
| Semantic image search | **CLIP-family embedding model** (e.g. MobileCLIP / SigLIP-small) via ONNX Runtime Mobile or LiteRT | this is what makes "dog jumping into lake" work without exact tags |
| Speech-to-text | **whisper.cpp (JNI)** with multilingual `base`/`small` model | ~99 languages, runs offline; `small` ≈ 460 MB, `base` ≈ 140 MB — user-selectable download |
| Text embeddings | Multilingual sentence embedder (e.g. multilingual-MiniLM / bge-m3-lite) via ONNX Runtime | matches queries against transcripts across languages |
| Background work | WorkManager + `setForeground()` dataSync service | survives process death, resumable |
| Media access | MediaStore API + `READ_MEDIA_IMAGES/VIDEO/AUDIO` (API 33+), `READ_EXTERNAL_STORAGE` fallback | also handle "Selected photos only" partial access (API 34+) |

---

## 3. Data model (Room)

```sql
-- One row per gallery file
media_item(
  id INTEGER PRIMARY KEY,          -- MediaStore _ID
  uri TEXT, kind INT,              -- IMAGE / VIDEO / AUDIO
  display_name TEXT, mime TEXT,
  date_taken INTEGER, date_modified INTEGER, size INTEGER,
  duration_ms INTEGER,             -- video/audio
  lat REAL, lon REAL, place TEXT,  -- EXIF GPS + offline reverse-geocode label
  bucket_name TEXT,                -- album/folder
  index_state INT,                 -- PENDING / PARTIAL / DONE / FAILED
  indexed_at INTEGER, pipeline_version INT
)

-- AI-extracted tags/labels (many per item)
tag(item_id, source, label, confidence)
  -- source: LABELER / OBJECT / OCR_KEYWORD / USER

-- Full transcript / OCR text per item (also mirrored into FTS)
document(item_id, source, lang, text, segments_json)
  -- source: TRANSCRIPT / OCR ; segments carry timestamps for A/V

-- FTS5 virtual table over: display_name, tags, OCR text, transcript, place, bucket
media_fts(content, item_id UNINDEXED, field)

-- Vector embeddings
embedding(item_id, kind, vec BLOB)   -- kind: IMAGE_CLIP / TEXT_TRANSCRIPT

-- Indexing bookkeeping (resume support)
index_run(id, started_at, finished_at, total, done, status)
```

**Delete/change tracking**: diff on each update by MediaStore `_ID` + `DATE_MODIFIED` (and `MediaStore.getGeneration()` on API 30+). New IDs → index; changed → re-index; missing → purge row + FTS + embeddings.

---

## 4. AI pipeline detail

### 4.1 Images
1. Decode downscaled bitmap (≤ 1024px, EXIF-rotated).
2. **ML Kit labeling** → tags with confidence (keep ≥ 0.6).
3. **OCR** → full text into `document(OCR)`; top keywords into `tag`.
4. **CLIP image embedding** → `embedding(IMAGE_CLIP)`.
5. EXIF: date, GPS (reverse-geocode offline to city/country via bundled dataset), camera model.
6. Category derivation (screenshot / document / receipt / people / nature / food …) from label sets + heuristics (e.g. path contains `Screenshots`).

### 4.2 Videos
1. Extract keyframes with `MediaMetadataRetriever` — adaptive: 1 frame per N seconds, capped (e.g. ≤ 20 frames).
2. Each keyframe → image pipeline (labels + CLIP embedding); aggregate tags, store per-frame timestamps so search can say *where* in the video.
3. Demux audio track → **4.3 audio pipeline** for the transcript.

### 4.3 Audio (and video soundtracks)
1. Decode to 16 kHz mono PCM (`MediaCodec` → resample).
2. **Whisper** (whisper.cpp JNI): auto language detection, multilingual transcript with timestamped segments → `document(TRANSCRIPT)`.
3. Transcript → sentence embeddings (chunked ~30 s windows) → `embedding(TEXT_TRANSCRIPT)`.
4. Long files processed in chunks with per-chunk checkpointing (a 1-hour recording must not restart from zero after interruption).

### 4.4 Search ("internal SEO") ranking
Query → in parallel:
- **FTS5 BM25** over names, tags, OCR, transcripts, places (handles exact words, any indexed language).
- **Vector search**: query → text embedding → cosine vs. `IMAGE_CLIP` (text-to-image, CLIP text tower) and vs. `TEXT_TRANSCRIPT` (semantic transcript match).

Merge with weighted score fusion (e.g. reciprocal-rank fusion), boost recency/exact-phrase hits, group by media kind, return top N with confidence scores. Also parse structured filters from the query: `type:video`, date words ("last summer"), place names.

---

## 5. Indexing engine (update button semantics)

- Update button → enqueue **unique** WorkManager job (`ExistingWorkPolicy.KEEP` so double-taps don't duplicate).
- Job = foreground service with progress notification + live widget progress ("1,240 / 8,900").
- Steps: ① MediaStore diff → work queue in `index_run` ② process in small batches (images batched ~8, A/V serially), commit DB after every batch → **crash/kill = resume from last committed batch** ③ purge deleted ④ mark run complete, stamp widget "Updated · 8,900 files".
- Ordering: cheap passes first (metadata + image labeling for everything), expensive passes second (Whisper on A/V) — search becomes useful early, `index_state=PARTIAL` until all passes done.
- Constraints: require battery-not-low; pause on thermal throttling; Whisper optionally "only while charging" (user setting).
- `pipeline_version` column lets a future model upgrade selectively re-index.

---

## 6. UI surfaces

### 6.1 Widget (Glance)
- Row: 🔍 search field (tap → pop-up) · 🔄 update button.
- Status line: idle ("8,900 files indexed"), progress bar while indexing, warning if permission lost.
- Sizes: 4×1 minimal and 4×2 with recent-search chips.

### 6.2 Search pop-up
- Translucent dialog-themed Activity (works from widget; no overlay permission needed).
- Search field auto-focused, results stream in as-you-type (debounced ~150 ms).
- Result grid: thumbnail, kind badge, confidence, matched snippet (highlighted transcript/OCR fragment); for A/V hits show timestamp of the match.
- Tap → `ACTION_VIEW` intent to system gallery/player (seek position via extras where supported). Long-press → share / show tags.
- Dismiss on outside-tap/back. Cold-open target < 400 ms.

### 6.3 Settings sheet (minimal)
Permissions status, Whisper model choice + download manager, indexing preferences (charge-only ASR, frame density), index stats, "Rebuild index", "Wipe data".

---

## 7. Permissions & Android policy
- API 33+: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`; API 34+ handle `READ_MEDIA_VISUAL_USER_SELECTED` (partial access) gracefully; ≤ 32: `READ_EXTERNAL_STORAGE`.
- `POST_NOTIFICATIONS` for indexing progress; `FOREGROUND_SERVICE_DATA_SYNC` type.
- A launcher activity is technically required for first-run permission grants — it's the dialog-themed settings sheet, so the "no full app" constraint holds.

---

## 8. Module layout

```
finder+/
├─ app/                    # widget, popup, settings, DI wiring
├─ core-db/                # Room, FTS5, vector store, DAOs
├─ core-media/             # MediaStore reader, diffing, thumbnails, PCM decode
├─ engine-index/           # orchestrator, WorkManager jobs, resume logic
├─ engine-search/          # query parsing, hybrid ranking
├─ ai-vision/              # ML Kit wrappers, CLIP runner (ONNX/LiteRT)
├─ ai-speech/              # whisper.cpp JNI + model manager
└─ ai-text/                # sentence embedder, language utils
```

---

## 9. Milestones

| # | Milestone | Contents | Exit criterion |
|---|---|---|---|
| 0 | Skeleton | Gradle multi-module, minSdk/target, CI lint | builds & installs |
| 1 | Media inventory | permissions flow, MediaStore reader, Room schema, diff/purge | DB mirrors gallery; update button syncs incrementally |
| 2 | Image intelligence | labeling + OCR + EXIF/category pipeline, FTS5 | keyword search over images works via adb/test UI |
| 3 | Search surfaces | Glance widget, pop-up with live results, open-in-gallery | end-to-end: widget → type → result opens |
| 4 | Semantic search | CLIP embeddings + text embedder, hybrid ranking | "dog on sofa" finds untagged matching photo |
| 5 | Speech | whisper.cpp integration, model downloader, audio+video transcripts, chunk resume | spoken phrase in a video is findable, incl. non-English |
| 6 | Video visuals | keyframe pipeline, per-timestamp matches | scene inside a video is findable |
| 7 | Hardening | thermal/battery policies, partial-access mode, 10k+ file perf, resume torture tests | full index of a large real gallery, interrupted twice, completes correctly |
| 8 | Polish | recent searches, filters (`type:`, dates), settings, localization | release candidate |

---

## 10. Risks & mitigations
| Risk | Mitigation |
|---|---|
| Whisper too slow on low-end phones (small model ≈ slower than real-time) | model size choice (tiny/base/small), charge-only setting, transcribe lazily/last, chunked resume |
| Model storage footprint (Whisper + CLIP + embedder can exceed 500 MB) | optional downloads, default to `base`, let user pick |
| First full index takes hours on big galleries | cheap-pass-first ordering so search is useful in minutes; honest progress UI |
| RAM pressure running multiple models | strict one-model-loaded-at-a-time per pass; batch by pass, not by file |
| CLIP text tower quality for non-English queries | use a multilingual CLIP variant, or translate-via-embedding (multilingual text encoder aligned to image space, e.g. SigLIP-multilingual) |
| Partial media access (API 34) breaks "whole gallery" promise | detect & surface in widget status; index what's granted |
| FTS relevance across languages | per-language tokenizers where possible; unicode61 tokenizer + trigram fallback |

---

## 11. Explicit privacy stance
All inference on-device. No analytics on media content. Network permission used solely by the model downloader; verifiable because indexing works in airplane mode. DB encrypted-at-rest optional (SQLCipher) as a later setting.
