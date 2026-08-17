package ai.rightone.finderplus.index.pass

import android.content.Context
import ai.rightone.finderplus.db.dao.ContentDao
import ai.rightone.finderplus.index.DecodedImageCache
import ai.rightone.finderplus.index.ModelCoordinator
import ai.rightone.finderplus.index.work.Checkpoint
import ai.rightone.finderplus.media.FrameExtractor
import ai.rightone.finderplus.model.MediaItem
import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.Pass
import ai.rightone.finderplus.model.RequiredModel
import ai.rightone.finderplus.model.Segment
import ai.rightone.finderplus.model.Tag
import ai.rightone.finderplus.model.TagSource
import ai.rightone.finderplus.speech.SpeechRecognizer
import ai.rightone.finderplus.speech.TranscribeCursor
import ai.rightone.finderplus.text.TextEmbedder
import ai.rightone.finderplus.vision.ImageAnalyzer
import ai.rightone.finderplus.db.entity.WorkUnitEntity

private const val MAX_IMAGE_EDGE = 1024

/**
 * Smallest edge ML Kit will accept. Below this every detector throws
 * `InputImage width and height should be at least 32!`.
 *
 * Checked up front rather than caught, because a thrown pass is *retried*: on a live index a 23 KB `.gif`
 * and a 2.5 KB `.ico` each burned 4 attempts on both OCR and FACES — 16 pointless model invocations — and
 * then marked their items `FAILED`, which reads as "this photo is broken" when the truth is "this file is
 * a 32-pixel icon". A file too small to analyze is not applicable, not failed.
 */
private const val MIN_ML_KIT_EDGE = 32

/** True when [bmp] is large enough for ML Kit to accept. */
private fun analyzable(bmp: android.graphics.Bitmap): Boolean =
    bmp.width >= MIN_ML_KIT_EDGE && bmp.height >= MIN_ML_KIT_EDGE

/** EXIF geolocation + offline reverse-geocode (single-shot). */
class MetadataPassHandler(private val context: Context) : PassHandler {
    override val pass = Pass.METADATA
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        // TODO: read EXIF GPS via androidx.exifinterface; reverse-geocode offline to a place label.
        ctx.emit(PartialResult.Meta(place = item.place, lat = item.lat, lon = item.lon), Checkpoint.None)
        return PassOutcome.COMPLETED
    }
}

/** ML Kit labels + derived category (single-shot). Reuses the item's cached decode. */
class ImageLabelPassHandler(private val images: DecodedImageCache, private val analyzer: ImageAnalyzer) : PassHandler {
    override val pass = Pass.IMAGE_LABEL
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        val bmp = images.get(item.uri, MAX_IMAGE_EDGE) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) return PassOutcome.SKIPPED
        val labels = analyzer.labels(bmp).map { it.copy(itemId = item.id) }
        val category = analyzer.category(labels, item.displayName)
        val tags = labels + Tag(item.id, TagSource.CATEGORY, category, 1f)
        ctx.emit(PartialResult.LabelTags(tags), Checkpoint.None)
        return PassOutcome.COMPLETED
    }
}

/** ML Kit OCR (single-shot). Reuses the decode the label pass just made for this item. */
class OcrPassHandler(private val images: DecodedImageCache, private val analyzer: ImageAnalyzer) : PassHandler {
    override val pass = Pass.OCR
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        val bmp = images.get(item.uri, MAX_IMAGE_EDGE) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) return PassOutcome.SKIPPED
        val ocr = analyzer.ocr(bmp)
        if (ocr.fullText.isNotBlank()) {
            ctx.emit(PartialResult.OcrText(ocr.fullText, ocr.lang, ocr.keywords), Checkpoint.None)
        }
        return PassOutcome.COMPLETED
    }
}

/**
 * Object detection → concrete COCO-class OBJECT tags (YOLOX). For video, the platform thumbnail is
 * what gets detected — the same frame the user sees in their gallery, per "label the thumbnail".
 *
 * SKIPPED (not completed-empty) while the detector model is missing, so every unit revives the moment
 * it is installed rather than being recorded as done-with-nothing.
 */
class ObjectsPassHandler(
    private val context: Context,
    private val images: DecodedImageCache,
    private val detector: ai.rightone.finderplus.vision.ObjectDetector,
) : PassHandler {
    override val pass = Pass.OBJECTS
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        if (!detector.isReady()) return PassOutcome.SKIPPED
        val video = item.kind == MediaKind.VIDEO
        val bmp = (if (video) ai.rightone.finderplus.index.MediaThumbs.load(context, item.uri, MAX_IMAGE_EDGE)
                   else images.get(item.uri, MAX_IMAGE_EDGE)) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) { if (video) bmp.recycle(); return PassOutcome.SKIPPED }
        val tags = detector.detect(bmp).map { it.copy(itemId = item.id) }
        if (video) bmp.recycle()
        // Emitted even when empty so a re-run CLEARS stale detections from the previous detector.
        ctx.emit(PartialResult.LabelTags(tags, clearSources = setOf(TagSource.OBJECT)), Checkpoint.None)
        return PassOutcome.COMPLETED
    }
}

/**
 * One-sentence VLM caption per item — an image directly, a video via its thumbnail.
 *
 * Two exits besides success, both deliberate:
 *  - **model missing** → SKIPPED, revived by install;
 *  - **budget exhausted** ([CaptionBudget]) → SKIPPED, revived next run. Captioning is the only pass
 *    whose per-item cost is unknowable in advance, so it runs under a wall-clock allowance instead of
 *    trust; when the device cannot afford it, everything else continues unharmed.
 */
class CaptionPassHandler(
    private val context: Context,
    private val images: DecodedImageCache,
    private val captioner: ai.rightone.finderplus.speech.VlmCaptioner,
    private val coordinator: ModelCoordinator,
    private val budget: ai.rightone.finderplus.index.CaptionBudget,
    private val remainingCaptions: suspend () -> Int,
) : PassHandler {
    override val pass = Pass.CAPTION
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        if (!captioner.isReady()) return PassOutcome.SKIPPED
        if (!budget.shouldContinue(remainingCaptions())) return PassOutcome.SKIPPED

        val video = item.kind == MediaKind.VIDEO
        val bmp = (if (video) ai.rightone.finderplus.index.MediaThumbs.load(context, item.uri, MAX_IMAGE_EDGE)
                   else images.get(item.uri, MAX_IMAGE_EDGE)) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) { if (video) bmp.recycle(); return PassOutcome.SKIPPED }

        val t0 = System.currentTimeMillis()
        val text = coordinator.withModel(RequiredModel.VLM) { captioner.caption(bmp) }
        budget.record(System.currentTimeMillis() - t0)
        if (video) bmp.recycle()

        // An empty caption is a model failure, not a description; store nothing rather than "".
        if (text.isNotBlank()) ctx.emit(PartialResult.Caption(text), Checkpoint.None)
        return PassOutcome.COMPLETED
    }
}

/**
 * Face detection → people signal. Emits searchable tags immediately (how many people, smiling,
 * selfie) and persists each face box. There is deliberately no identity stage: grouping the same
 * person across photos needed a margin-trained face model whose only practical weights are
 * non-commercial, which is a licensing blocker for a FOSS build, and the feature proved to have no
 * real use case here. Detection stays because its tags are searchable signal on their own.
 */
class FacesPassHandler(
    private val images: DecodedImageCache,
    private val faces: ai.rightone.finderplus.vision.FaceAnalyzer,
) : PassHandler {
    override val pass = Pass.FACES
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        if (!faces.isReady()) return PassOutcome.SKIPPED
        val bmp = images.get(item.uri, MAX_IMAGE_EDGE) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) return PassOutcome.SKIPPED
        val detected = faces.detect(bmp)
        if (detected.isEmpty()) return PassOutcome.COMPLETED

        val tags = mutableListOf<Tag>()
        tags += Tag(item.id, TagSource.CATEGORY, "people", 1f)
        tags += when (detected.size) {
            1 -> Tag(item.id, TagSource.OBJECT, "one person", 1f)
            2 -> Tag(item.id, TagSource.OBJECT, "two people", 1f)
            else -> Tag(item.id, TagSource.OBJECT, "group of people", 1f)
        }
        // A single large face is almost always a selfie/portrait — a common way people search.
        detected.maxByOrNull { it.areaRatio }?.let { biggest ->
            if (detected.size == 1 && biggest.areaRatio > 0.10f) {
                tags += Tag(item.id, TagSource.OBJECT, "portrait", 1f)
            }
        }
        if (detected.any { (it.smiling ?: 0f) > 0.7f }) {
            tags += Tag(item.id, TagSource.OBJECT, "smiling", 1f)
        }

        ctx.emit(PartialResult.Faces(detected, tags), Checkpoint.None)
        return PassOutcome.COMPLETED
    }
}

/** CLIP image embedding, under the heavy-model residency lock (single-shot). */
class ImageEmbedPassHandler(
    private val images: DecodedImageCache,
    private val analyzer: ImageAnalyzer,
    private val coordinator: ModelCoordinator,
    private val clipModelId: String,
) : PassHandler {
    override val pass = Pass.IMAGE_EMBED
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        // Sampled timing. Whole-gallery re-embeds are the longest job this app runs, and without the
        // decode/inference split there is no way to tell a slow model from slow I/O — the two call for
        // opposite fixes.
        val t0 = System.currentTimeMillis()
        val bmp = images.get(item.uri, MAX_IMAGE_EDGE) ?: return PassOutcome.COMPLETED
        val t1 = System.currentTimeMillis()
        val vec = coordinator.withModel(RequiredModel.CLIP_IMG) { analyzer.embed(bmp) }
        val t2 = System.currentTimeMillis()
        if (unit.id % TIMING_SAMPLE_EVERY == 0L) {
            android.util.Log.i(
                "finderEmbedTiming",
                "decode=${t1 - t0}ms embed=${t2 - t1}ms ${bmp.width}x${bmp.height} ${item.displayName}",
            )
        }
        ctx.emit(PartialResult.ImageVector(vec, sourceRef = 0, modelId = clipModelId), Checkpoint.None)
        return PassOutcome.COMPLETED
    }

    private companion object {
        const val TIMING_SAMPLE_EVERY = 10L
    }
}

/**
 * Video keyframes — resumable. Extracts frames one at a time, running OCR + CLIP per frame, and
 * advances [Checkpoint.Keyframes] after each committed frame so a kill resumes at the next index.
 *
 * ### Why this pass no longer writes per-frame labels
 *
 * It used to also run the ML Kit labeler on every frame and attach the results to the video as tags.
 * Because each frame contributed independently and nothing required agreement between them, a video
 * accumulated the *union* of twenty frames' guesses: measured on this gallery, 608 videos carried 6,902
 * label tags — 11.4 each, against 3.5 for a photo — including sets as self-contradictory as
 * `Aircraft, Bird, Dog, Musical instrument, Toe, Wool` on one clip. Every one of those labels was
 * individually above ML Kit's confidence floor; the union is what was wrong, not the classifier.
 *
 * Per-frame *labels* were also the redundant half of this pass. The per-frame **embedding** written
 * below already supports finding the moment a dog appears — by open-vocabulary similarity rather than a
 * fixed 400-word list — so removing the labels costs no retrieval, and what a video is *about* is now
 * answered by [Pass.CONCEPTS] from these same vectors, frame-averaged and calibrated. Dropping the
 * labeler also removes one model invocation per frame, which makes video indexing measurably faster.
 *
 * OCR stays, per frame and timestamped: recognized text is a transcription rather than a guess, so it
 * cannot mislead the way a classification can.
 */
class KeyframesPassHandler(
    private val frames: FrameExtractor,
    private val analyzer: ImageAnalyzer,
    private val coordinator: ModelCoordinator,
    private val clipModelId: String,
    private val maxFrames: Int = 20,
) : PassHandler {
    override val pass = Pass.KEYFRAMES
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        val start = checkpoint as? Checkpoint.Keyframes
        val total = start?.totalFrames ?: frames.frameCount(item, maxFrames)
        var index = start?.nextFrameIndex ?: 0
        while (index < total) {
            if (ctx.isStopRequested()) return PassOutcome.STOPPED
            val frame = frames.frameAt(item, index, total)
            if (frame != null) {
                val ocr = analyzer.ocr(frame.bitmap)
                val vec = coordinator.withModel(RequiredModel.CLIP_IMG) { analyzer.embed(frame.bitmap) }
                ctx.emit(
                    PartialResult.Frame(index, frame.timestampMs, ocr.fullText.ifBlank { null }, vec, clipModelId),
                    Checkpoint.Keyframes(index + 1, total),
                )
            } else {
                ctx.emit(PartialResult.LabelTags(emptyList()), Checkpoint.Keyframes(index + 1, total))
            }
            index++
        }
        return PassOutcome.COMPLETED
    }
}

/**
 * Whisper transcription — resumable mid-file. Delegates chunking to the recognizer, which calls back
 * per ~30 s window; each callback commits segments + advances [Checkpoint.Transcribe].
 */
class TranscribePassHandler(private val recognizer: SpeechRecognizer, private val coordinator: ModelCoordinator) : PassHandler {
    override val pass = Pass.TRANSCRIBE
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        // No speech model installed (they are large and optional) — park the unit instead of failing
        // it, so the rest of the gallery still indexes cleanly and it revives after a download.
        if (!recognizer.isReady()) return PassOutcome.SKIPPED

        val cp = checkpoint as? Checkpoint.Transcribe ?: Checkpoint.Transcribe(0L, null)
        coordinator.withModel(RequiredModel.ASR) {
            recognizer.transcribe(
                item = item,
                from = TranscribeCursor(cp.nextChunkStartMs, cp.lang),
                emit = { segments, next ->
                    ctx.emit(PartialResult.TranscriptSegments(segments), Checkpoint.Transcribe(next.nextChunkStartMs, next.lang))
                },
                isStopRequested = { ctx.isStopRequested() },
            )
        }
        return if (ctx.isStopRequested()) PassOutcome.STOPPED else PassOutcome.COMPLETED
    }
}

/** Sentence embeddings of the transcript chunks (idempotent per chunk; stop-aware). */
class TextEmbedPassHandler(
    private val contentDao: ContentDao,
    private val embedder: TextEmbedder,
    private val coordinator: ModelCoordinator,
) : PassHandler {
    override val pass = Pass.TEXT_EMBED
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        val segments: List<Segment> = contentDao.segmentsForItem(item.id)
            .map { Segment(it.item_id, it.start_ms, it.end_ms, it.text) }
        if (segments.isEmpty()) return PassOutcome.COMPLETED
        val chunks = chunkByWindow(segments, windowMs = 30_000L)
        chunks.forEachIndexed { i, text ->
            if (ctx.isStopRequested()) return PassOutcome.STOPPED
            val vec = coordinator.withModel(RequiredModel.TEXT_EMB) { embedder.embed(text) }
            ctx.emit(PartialResult.TextVector(vec, sourceRef = i, modelId = embedder.modelId), Checkpoint.None)
        }
        return PassOutcome.COMPLETED
    }

    private fun chunkByWindow(segments: List<Segment>, windowMs: Long): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var windowStart = segments.first().startMs
        for (s in segments) {
            if (s.startMs - windowStart >= windowMs && sb.isNotEmpty()) {
                out += sb.toString().trim(); sb.setLength(0); windowStart = s.startMs
            }
            sb.append(s.text).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }
}

/**
 * Writes open-vocabulary concept labels for an item that already has an image embedding.
 *
 * Uses no model. It scores the stored vector against the seeded concept prototypes and the user's
 * learned ones, which is why it can re-label the entire gallery in seconds after a vocabulary change —
 * the expensive step (encoding) happened once, in [Pass.IMAGE_EMBED], and its result is durable.
 *
 * Reports SKIPPED rather than COMPLETED when there is nothing to score against, so the unit stays
 * eligible for a later requeue instead of being recorded as done-with-no-output. That matters on first
 * run, where concepts are reachable only after the vocabulary has been seeded.
 */
class ConceptsPassHandler(
    private val classifier: ai.rightone.finderplus.index.ConceptClassifier,
) : PassHandler {
    override val pass = Pass.CONCEPTS

    override suspend fun process(
        item: MediaItem,
        unit: WorkUnitEntity,
        checkpoint: Checkpoint,
        ctx: PassContext,
    ): PassOutcome {
        val reading = classifier.read(item.id, limit = CONCEPT_TAGS_PER_ITEM)
        val concepts = reading.concepts

        // The coarse domain the whole item belongs to — "people", "screen", "vehicles". It comes free
        // from stage 1 and is the most reliable thing this head produces, because the 13 domains are
        // visually distinct in a way individual concepts are not.
        //
        // Written for video only, and not because images would not benefit: IMAGE_LABEL already writes
        // their CATEGORY, and since a re-emitted source replaces that source wholesale, writing it here
        // too would have one pass silently overwrite the other's answer. Video is the kind with no
        // category at all, because IMAGE_LABEL never runs on it.
        val category = reading.domain
            ?.takeIf { it !in NON_CATEGORY_DOMAINS }
            ?.let { Tag(item.id, TagSource.CATEGORY, it, reading.domainConfidence) }

        if (concepts.isEmpty() && category == null) return PassOutcome.SKIPPED

        // Three bands, because a single cutoff cannot serve both precision and recall here.
        //
        // Everything the classifier returned used to be written as a searchable tag, and measured on a
        // real gallery that meant 60% of concept tags sat below 0.10 joint confidence — noise that made
        // search worse while reading as fact. Now: assert only what is confident, ask about what is
        // plausible, and discard what is neither rather than spending the user's attention on it.
        val tags = concepts.mapNotNull {
            val source = when {
                // A label backed by the user's own exemplars is trusted at its own (image-space) scale.
                it.taught -> ai.rightone.finderplus.model.TagSource.LEARNED
                // A *name* is never asserted, only proposed. Identity and brand guesses were documented
                // from the start as suggestion-grade ("confusing one person for another is the one
                // output a user would call broken"), yet the band let a confident one auto-apply.
                it.isEntity ->
                    ai.rightone.finderplus.model.TagSource.SUGGESTED
                it.score >= AUTO_APPLY_CONFIDENCE -> ai.rightone.finderplus.model.TagSource.CONCEPT
                it.score >= REVIEW_CONFIDENCE -> ai.rightone.finderplus.model.TagSource.SUGGESTED
                else -> return@mapNotNull null
            }
            Tag(itemId = item.id, source = source, label = it.label, confidence = it.score)
        } + listOfNotNull(category)

        // CONCEPT, SUGGESTED and LEARNED are cleared even when this run produced none of them, so a
        // re-label after a threshold or prototype change removes what no longer qualifies. LEARNED is
        // in the set for the same reason as the others — it is machine-derived, and leaving it made
        // taught-label mistakes permanent: 73 stale `cem yılmaz` applications would have survived the
        // very threshold fix that ended them.
        val clear = setOf(TagSource.CONCEPT, TagSource.SUGGESTED, TagSource.LEARNED) +
            if (category != null) setOf(TagSource.CATEGORY) else emptySet()

        // Still COMPLETED with nothing to write: "this item has no confident concepts" is a real,
        // final answer, not a failure to retry.
        ctx.emit(PartialResult.LabelTags(tags, clearSources = clear), Checkpoint.None)
        return PassOutcome.COMPLETED
    }

    private companion object {
        /** Enough to describe an item without turning the profile into a bag of weak guesses. */
        const val CONCEPT_TAGS_PER_ITEM = 8

        /**
         * Joint confidence at or above which a concept is asserted **without asking**.
         *
         * Set where a label is not merely plausible but unmistakable. Measured over 612 indexed videos,
         * moving this from 0.20 to 0.35 takes auto-applied tags from 0.45 to 0.14 per item and the items
         * carrying any from 251 to 83 — and what survives reads as correct on inspection: `person with a
         * beard` 0.59, `screenshot of a weather forecast` 0.48, `religious ceremony` 0.47, `airplane` 0.40.
         *
         * The cost is deliberate and worth stating: labels that hand-checking previously confirmed as
         * correct — `jewelry` 0.251, `bus` 0.237, `ring` 0.201 — no longer get asserted. They move to the
         * review band instead, where the user confirms them. An index the user has agreed with is worth
         * more than one the classifier merely believes, and a wrong tag costs more than a missing one
         * because it is indistinguishable from a right one once written.
         */
        const val AUTO_APPLY_CONFIDENCE = 0.35f

        /**
         * Below [AUTO_APPLY_CONFIDENCE] but worth one question. Under this, discarded silently.
         *
         * 0.12 rather than 0.10 because the measured junk floor sits just under it: `soup` 0.037 and
         * `person dancing` 0.037 were the kind of thing that used to reach the database.
         */
        const val REVIEW_CONFIDENCE = 0.12f

        /**
         * Domains that win the gate but say nothing about *what* an item is.
         *
         * `entities` is an identity branch — "this contains someone famous" is not a category — and
         * `quality` describes photographic style. Both are useful as concepts and useless as the one
         * word summarizing a file.
         */
        val NON_CATEGORY_DOMAINS = setOf("entities", "quality")
    }
}
