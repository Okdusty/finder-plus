# 10 · Video Throughput — getting to ~1 fps affordably

Video is the most expensive media to classify: a 30-second clip at 1 fps is 30 frames, and each frame
naively costs a full-resolution decode plus labeling, OCR and an embedding. With 1,304 videos in the
test gallery, the naive path is tens of thousands of inferences. Four changes make ~1 fps sampling
practical, in descending order of impact.

## 1. Don't run models on frames that look the same (biggest win)

Video is enormously redundant at 1 fps — a talking-head clip is essentially one image repeated. Each
extracted frame is reduced to a 64-bit **dHash** (9x8 grayscale, adjacent-pixel comparisons) and
compared with the previous frame; if the Hamming distance is ≤ 6 the frame is dropped **before any
model runs**.

This attacks inference cost rather than decode cost, which is where the real time goes: labeling + OCR
+ embedding is far more expensive than pulling a small frame. On typical phone video (static shots,
slow pans) this removes the large majority of frames while keeping every genuine scene change.

Cost of the hash itself is trivial: one 9x8 rescale and 64 comparisons.

## 2. Decode straight to the analysis size

`MediaMetadataRetriever.getScaledFrameAtTime` (API 27+) lets the decoder emit a small bitmap directly
instead of handing back a full 4K frame that we immediately shrink. Frames are requested at **512 px**
long edge — the size the analysis stage wants anyway.

For 4K source video this avoids decoding and allocating ~8 M pixels per frame (≈33 MB as ARGB_8888) in
favour of ~0.26 M pixels, cutting both time and GC pressure per frame substantially.

## 3. One retriever per video, not per frame

The original code constructed and released a `MediaMetadataRetriever` **inside `frameAt()`**, so every
single frame re-opened the file and re-parsed the container. The extractor now caches the retriever
keyed by URI and releases it when the video changes (`AutoCloseable`). Since the engine walks a video's
frames consecutively, this collapses N container parses into one.

## 4. Bound the frame count, and keep the checkpoint

`frameCount()` computes `duration × 1 fps` but clamps to `maxFrames` (default 20). This is deliberate:
1 fps is the *sampling ceiling*, not a promise — a 10-minute clip must not become 600 inference rounds.
Long videos therefore get coarser-than-1 fps coverage, which is the right trade for a background
indexer.

Per-frame checkpointing is unchanged: `Checkpoint.Keyframes(nextFrameIndex, totalFrames)` still advances
after each committed frame, so a kill mid-video resumes at the next frame rather than restarting.

## What was deliberately not done

**Sequential MediaCodec + ImageReader decode.** Streaming the video through `MediaCodec` to a Surface
and sampling as frames emerge is faster still than repeated `OPTION_CLOSEST_SYNC` seeks, because it
avoids seek-and-re-decode per sample entirely. It was skipped for now because it is a large amount of
state (codec + extractor + surface + format changes + EOS handling) and it breaks the simple
`frameAt(index)` contract that makes per-frame checkpointing trivial. The dedup + scaled-decode +
retriever-reuse changes capture most of the available win at a fraction of the complexity and risk.

If video volume grows enough to justify it, the migration path is: replace `AndroidFrameExtractor` with
a streaming decoder that emits `(index, timestamp, bitmap)` in order and keeps the same interface, so
the pass handler and checkpoint logic do not change.

## Interaction with the battery governor

These savings compound with `PowerPolicy` (`07-BATTERY-POLICY.md`): fewer inferences per video means
fewer per-unit throttle pauses and less heat, so more of each 4-minute slice goes to useful work rather
than cooling down. Frame dedup also reduces DB writes — dropped frames produce no tags, no segments and
no embeddings.
