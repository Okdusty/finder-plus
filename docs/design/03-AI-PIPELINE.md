# 03 · On-device AI Pipeline

Every model runs offline. Each analyzer is a `PassHandler` (see `01-DB-ENGINE.md §11`) that reads a `Checkpoint`, does one micro-batch, and hands the engine a `(PartialResult, newCheckpoint)` to commit atomically. That is what ties inference to the resume/stop guarantees.

```
                     ┌──────────────────────────────────────────────┐
 MediaItem  ───────► │            IndexOrchestrator                 │
                     │  claim → route by Pass → checkpoint → commit  │
                     └───────┬───────────┬───────────┬──────────────┘
                             ▼           ▼           ▼
                     ImageAnalyzer  VideoAnalyzer  AudioAnalyzer
                       │  │  │          │  │            │
                  labels ocr clip   keyframes│       whisper(chunked)
                                      demux───┘            │
                                                       TextEmbedder
```

## 1. `ai-vision` — images & keyframes

```kotlin
interface ImageAnalyzer {
  suspend fun labels(bitmap: Bitmap): List<Tag>          // ML Kit Image Labeling, conf >= 0.6
  suspend fun ocr(bitmap: Bitmap): OcrResult             // ML Kit Text Recognition v2
  suspend fun embed(bitmap: Bitmap): FloatArray          // CLIP image tower (ONNX), L2-normalized
  suspend fun category(labels: List<Tag>, path: String?): String  // screenshot/document/food/…
}
data class OcrResult(val fullText: String, val lang: String?, val keywords: List<String>)

interface ClipImageEncoder { suspend fun encode(bitmap: Bitmap): FloatArray }  // 512/768-d
interface ClipTextEncoder  { suspend fun encode(text: String): FloatArray }    // same space, for search
```

- **Decode**: downscale to ≤1024 px long edge, apply EXIF rotation, ARGB_8888. Reused bitmap buffers to bound RAM.
- **Labels**: ML Kit on-device labeler (400+ concepts), keep `confidence ≥ 0.6` → `Tag(source=LABEL)`.
- **OCR**: Text Recognition v2 (Latin + CJK/Devanagari/Korean/Cyrillic script packs) → `document(OCR)` + top keywords as `Tag(source=OCR_KEYWORD)`.
- **CLIP image embedding**: ONNX Runtime Mobile, MobileCLIP/SigLIP-small image tower. Written to `embedding(IMAGE_CLIP)`. The matching **text tower** lives here too so search can encode the query into the same space. Prefer a **multilingual** CLIP variant so non-English queries hit.
- **Category**: derived from label sets + path heuristics (`…/Screenshots/` → screenshot; receipt/document detection from OCR density + keywords).

## 2. `ai-vision` — video (keyframes), resumable

```kotlin
interface VideoAnalyzer {
  /** Extracts up to [maxFrames] adaptive keyframes; resumes at cp.nextFrameIndex.
   *  Each frame runs the image pipeline and is committed before the checkpoint advances. */
  suspend fun analyze(item: MediaItem, cp: Checkpoint.Keyframes,
                      emit: suspend (FramePartial, Checkpoint) -> Unit, stop: StopSignal)
}
data class FramePartial(val frameIndex: Int, val timestampMs: Long,
                        val tags: List<Tag>, val ocr: OcrResult?, val embedding: FloatArray)
```

- `MediaMetadataRetriever.getFrameAtTime` at adaptive spacing (1 frame / N s, capped e.g. ≤20). Each frame's tags/embedding carry `source_ref = frameIndex` and `segment.start_ms = timestampMs` → search can point to **where in the video** a scene appears.
- After each frame commits, `checkpoint.nextFrameIndex++`. Kill at frame 7/20 → resume at 7 (matrix row #2).
- The audio track is demuxed and handed to §3.

## 3. `ai-speech` — multilingual ASR (the heavy pass), chunked & resumable

```kotlin
interface SpeechRecognizer {
  /** Transcribes [item] from cp.nextChunkStartMs to end, emitting one committed batch per chunk.
   *  Auto-detects language on the first chunk. Resumable mid-file. */
  suspend fun transcribe(item: MediaItem, cp: Checkpoint.Transcribe,
                         emit: suspend (List<Segment>, Checkpoint) -> Unit, stop: StopSignal)
}

// JNI bridge to whisper.cpp (native lib built for arm64-v8a, armeabi-v7a, x86_64)
internal object WhisperNative {
  external fun init(modelPath: String, threads: Int): Long          // -> ctx handle
  external fun full(ctx: Long, pcm16k: FloatArray, langHint: String?): Int
  external fun segmentCount(ctx: Long): Int
  external fun segment(ctx: Long, i: Int): WhisperSegment           // text,t0,t1
  external fun detectedLanguage(ctx: Long): String
  external fun free(ctx: Long)
  init { System.loadLibrary("whisper_jni") }
}
data class WhisperSegment(val text: String, val t0Ms: Long, val t1Ms: Long)
```

- **Decode**: `MediaCodec` → PCM, resample to **16 kHz mono float**. Videos reuse the demuxed track.
- **Chunking**: process ~30 s windows (whisper's native frame) with small overlap; commit each window's segments (`start_ms` absolute) + advance `checkpoint.nextChunkStartMs`. A 1-hour recording is ~120 chunks; a kill loses at most one chunk (matrix row #3). `UNIQUE(item_id, start_ms)` makes a redone chunk idempotent.
- **Language**: auto-detected on chunk 0, cached in the checkpoint so resume keeps the same language.
- **Cost control**: whisper is the heaviest model — gated behind priority 60, single-resident, and optionally "only while charging."

## 4. `ai-text` — transcript & query embeddings

```kotlin
interface TextEmbedder {
  suspend fun embed(text: String): FloatArray     // multilingual sentence encoder (ONNX), L2-normalized
  fun dimension(): Int
}
object LanguageUtils { fun detect(text: String): String?; fun normalize(text: String): String }
```

- Transcripts chunked (~30 s / ~200 tokens) → `embedding(TEXT_TRANSCRIPT, source_ref=chunkIndex)`.
- The **same** encoder embeds the search query → semantic transcript match across languages.

## 5. `ModelManager` / `ModelCoordinator`

```kotlin
interface ModelManager {
  fun catalog(): List<ModelSpec>                     // whisper tiny/base/small, CLIP, embedder
  fun isInstalled(id: String): Boolean
  suspend fun download(id: String): Flow<DownloadProgress>   // one-time, verified by SHA-256
  fun delete(id: String)
  fun installedFootprintBytes(): Long
}
data class ModelSpec(val id: String, val role: ModelRole, val sizeBytes: Long,
                     val sha256: String, val url: String, val languages: Int)

/** Enforces "one heavy model resident at a time" + memory/thermal guards. */
class ModelCoordinator {
  private val mutex = Mutex()
  suspend fun <T> withModel(role: ModelRole, block: suspend (ModelSession) -> T): T = mutex.withLock {
    ensureLoaded(role); try { block(session(role)) } finally { maybeUnloadUnderPressure() }
  }
}
enum class ModelRole { MLKIT, CLIP, WHISPER, TEXT_EMBEDDER }
```

- **Residency**: the `Mutex` guarantees a single {whisper|CLIP|embedder} loaded; the ledger's model-affinity claim (see DB doc §4.1) drains all units for the resident model before swapping.
- **Footprint** (user-selectable, optional downloads): whisper `tiny ≈ 75 MB`, `base ≈ 140 MB`, `small ≈ 460 MB`; CLIP ≈ 90–180 MB; text embedder ≈ 90–120 MB. Default ships nothing heavy; first-run offers **base**.
- **Guards**: `onTrimMemory`/thermal status → unload at next checkpoint boundary, run pauses (`RunStatus.PAUSED`), resumes when clear.
- **Versioning**: each `ModelSpec` maps to a `pipeline_version`; swapping a model bumps it → selective re-index of only stale units (DB doc §9).

## 6. Checkpoint contract summary

| Pass | micro-batch | checkpoint cursor | idempotency key |
|---|---|---|---|
| METADATA | whole item | — | item row upsert |
| IMAGE_LABEL/OCR | whole image | — | `tag`/`document` unique |
| KEYFRAMES | one frame | `nextFrameIndex` | `segment/embedding source_ref=frame` |
| IMAGE_EMBED | one image/frame | — | `embedding` unique |
| TRANSCRIBE | ~30 s chunk | `nextChunkStartMs` | `segment(item_id,start_ms)` unique |
| TEXT_EMBED | one chunk | `nextChunkIndex` | `embedding source_ref=chunk` |

Every heavy pass is interruptible at a micro-batch boundary and resumes exactly — the AI layer inherits crash-safety for free from the engine.
