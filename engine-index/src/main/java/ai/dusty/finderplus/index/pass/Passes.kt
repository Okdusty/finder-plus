package ai.dusty.finderplus.index.pass

import ai.dusty.finderplus.settings.DescribingSettings
import ai.dusty.finderplus.settings.MediaSettings
import ai.dusty.finderplus.settings.Settings
import android.content.Context
import ai.dusty.finderplus.db.dao.ContentDao
import ai.dusty.finderplus.index.DecodedImageCache
import ai.dusty.finderplus.index.ModelCoordinator
import ai.dusty.finderplus.index.work.Checkpoint
import ai.dusty.finderplus.media.FrameExtractor
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.Pass
import ai.dusty.finderplus.model.RequiredModel
import ai.dusty.finderplus.model.Segment
import ai.dusty.finderplus.model.Tag
import ai.dusty.finderplus.model.TagSource
import ai.dusty.finderplus.speech.SpeechRecognizer
import ai.dusty.finderplus.speech.TranscribeCursor
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.vision.ImageAnalyzer
import ai.dusty.finderplus.db.entity.WorkUnitEntity

/** Longest edge a photo is read at, from the settings. */
private fun analysisEdge(): Int = Settings[MediaSettings.PHOTO_EDGE].toInt()

/**
 * Smallest edge ML Kit accepts; below it every detector throws. Checked up front rather than caught,
 * because a thrown pass is retried and finally marked FAILED, while an image this small (an icon) is
 * not applicable rather than broken.
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
        val bmp = images.get(item.uri, analysisEdge()) ?: return PassOutcome.COMPLETED
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
        val bmp = images.get(item.uri, analysisEdge()) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) return PassOutcome.SKIPPED
        val ocr = analyzer.ocr(bmp)
        if (ocr.fullText.isNotBlank()) {
            ctx.emit(PartialResult.OcrText(ocr.fullText, ocr.lang, ocr.keywords), Checkpoint.None)
        }
        return PassOutcome.COMPLETED
    }
}

/**
 * The describe pass: a caption and located objects from the describe model, for images and for one
 * frame of each video, its most typical keyframe (see [ai.dusty.finderplus.index.MediaThumbs.describedFrame]).
 * Object names go through [DetectionReview] before they are written; the caption becomes the item's
 * caption document. For a video both keep where the frame is, so previews open there.
 *
 * SKIPPED, not completed-empty, while the model is missing, so every unit revives once it is installed.
 */
class ObjectsPassHandler(
    private val context: Context,
    private val images: DecodedImageCache,
    private val embeddings: ai.dusty.finderplus.db.dao.EmbeddingDao,
    private val describer: ai.dusty.finderplus.vision.ImageDescriber,
    private val coordinator: ModelCoordinator,
    private val review: ai.dusty.finderplus.index.DetectionReview,
) : PassHandler {
    override val pass = Pass.OBJECTS
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        if (!describer.isReady()) return PassOutcome.SKIPPED
        val video = item.kind == MediaKind.VIDEO
        val frame = if (video) {
            ai.dusty.finderplus.index.MediaThumbs.describedFrame(context, embeddings, item.id, item.uri, analysisEdge())
                ?: return PassOutcome.COMPLETED
        } else null
        val bmp = frame?.bitmap ?: images.get(item.uri, analysisEdge()) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) { if (video) bmp.recycle(); return PassOutcome.SKIPPED }
        val (caption, outcome) = try {
            // The describe model and CLIP each run under their own residency, one after the other.
            val d = coordinator.withModel(RequiredModel.VLM) { describer.describe(bmp) }
            val filter = ai.dusty.finderplus.vision.DetectionFilter
            val about = if (Settings[DescribingSettings.GROUNDED_ONLY]) {
                filter.groundedOnly(d.objects, d.grounded, Settings[DescribingSettings.GROUNDING_OVERLAP].toFloat())
            } else d.objects
            d.caption to review.review(item.id, bmp, filter.classAgnosticNms(about, Settings[DescribingSettings.SAME_REGION_OVERLAP].toFloat()))
        } finally {
            if (video) bmp.recycle()
        }
        // Emitted even when empty so a re-run clears stale detections and stale questions.
        ctx.emit(
            PartialResult.LabelTags(
                outcome.tags,
                clearSources = setOf(TagSource.OBJECT, TagSource.OBJECT_REVIEW),
                questions = outcome.questions,
                regions = outcome.regions,
                regionsAtMs = frame?.atMs,
            ),
            Checkpoint.None,
        )
        // An empty caption is a model failure, not a description.
        if (caption.isNotBlank()) ctx.emit(PartialResult.Caption(caption, frame?.atMs), Checkpoint.None)
        return PassOutcome.COMPLETED
    }
}

/**
 * Face detection: searchable people tags (count, smiling, portrait) plus each face box. There is
 * deliberately no identity stage: the practical face-recognition weights are licensed for
 * non-commercial use only, which a FOSS build cannot ship.
 */
class FacesPassHandler(
    private val images: DecodedImageCache,
    private val faces: ai.dusty.finderplus.vision.FaceAnalyzer,
) : PassHandler {
    override val pass = Pass.FACES
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        if (!faces.isReady()) return PassOutcome.SKIPPED
        val bmp = images.get(item.uri, analysisEdge()) ?: return PassOutcome.COMPLETED
        if (!analyzable(bmp)) return PassOutcome.SKIPPED
        val detected = faces.detect(bmp)
        if (detected.isEmpty()) {
            // Still emitted: a re-run that finds no face must clear what an earlier run wrote.
            ctx.emit(PartialResult.Faces(emptyList(), emptyList()), Checkpoint.None)
            return PassOutcome.COMPLETED
        }

        // Exactly one people tag per item. A single large face is a portrait, a common search term.
        val single = detected.size == 1 && detected[0].areaRatio > 0.10f
        val people = when {
            single -> "portrait"
            detected.size == 1 -> "one person"
            detected.size == 2 -> "two people"
            else -> "group of people"
        }
        val tags = mutableListOf(
            Tag(item.id, TagSource.CATEGORY, "people", 1f),
            Tag(item.id, TagSource.FACE, people, 1f),
        )
        if (detected.any { (it.smiling ?: 0f) > 0.7f }) tags += Tag(item.id, TagSource.FACE, "smiling", 1f)

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
        // Sampled timing: the decode/inference split tells a slow model from slow I/O, which need
        // opposite fixes.
        val t0 = System.currentTimeMillis()
        val bmp = images.get(item.uri, analysisEdge()) ?: return PassOutcome.COMPLETED
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
 * Video keyframes, resumable. Extracts frames one at a time, running OCR and CLIP per frame, and
 * advances [Checkpoint.Keyframes] after each committed frame so a kill resumes at the next index.
 *
 * No per-frame labels: tagging frames independently gives a video the union of every frame's
 * guesses, which contradict each other. The per-frame embeddings already find moments by similarity,
 * and what the video is about comes from [Pass.CONCEPTS] over the same vectors. OCR stays per frame
 * because recognized text is a transcription, not a guess.
 */
class KeyframesPassHandler(
    private val frames: FrameExtractor,
    private val analyzer: ImageAnalyzer,
    private val coordinator: ModelCoordinator,
    private val clipModelId: String,
    /** Null follows the settings. */
    private val maxFrames: Int? = null,
) : PassHandler {
    override val pass = Pass.KEYFRAMES
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        val start = checkpoint as? Checkpoint.Keyframes
        val total = start?.totalFrames ?: frames.frameCount(item, maxFrames ?: Settings[MediaSettings.VIDEO_FRAMES].toInt())
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

    companion object {
        /**
         * Keyframes taken from one video before the setting existed, and so what the timestamps of
         * keyframes from older indexes are recomputed with.
         */
        const val MAX_FRAMES = 20
    }
}

/**
 * Whisper transcription — resumable mid-file. Delegates chunking to the recognizer, which calls back
 * per ~30 s window; each callback commits segments + advances [Checkpoint.Transcribe].
 */
class TranscribePassHandler(private val recognizer: SpeechRecognizer, private val coordinator: ModelCoordinator) : PassHandler {
    override val pass = Pass.TRANSCRIBE
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        // Speech models are large and optional: park the unit instead of failing it, so it revives
        // after a download.
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

/**
 * EmbeddingGemma 2 text vectors for one item: one for its whole description, plus one per minute of
 * speech.
 *
 * The description (the profile: summary, labels, OCR, place) lets a query match meaning
 * rather than spelling, across languages. Transcript windows get their own vectors because one vector
 * for a long recording matches none of its topics well.
 *
 * SKIPPED while the model is missing; idempotent because the writer replaces the item's text vectors
 * as a set; stops between windows.
 */
class TextEmbedPassHandler(
    private val contentDao: ContentDao,
    private val profileDao: ai.dusty.finderplus.db.dao.MediaProfileDao,
    private val embedder: TextEmbedder,
    private val coordinator: ModelCoordinator,
) : PassHandler {
    override val pass = Pass.TEXT_EMBED
    override suspend fun process(item: MediaItem, unit: WorkUnitEntity, checkpoint: Checkpoint, ctx: PassContext): PassOutcome {
        if (!embedder.isReady()) return PassOutcome.SKIPPED

        // The profile, not the caption: the caption sentence also names the background, and it has a
        // keyword table of its own for the searches it helps.
        val document = profileDao.text(item.id).orEmpty().trim()
        val windows = ai.dusty.finderplus.index.TranscriptWindows.of(
            contentDao.speechSegments(item.id).map { Segment(it.item_id, it.start_ms, it.end_ms, it.text) },
            (Settings[MediaSettings.SPEECH_WINDOW] * 1000).toLong(),
        )

        val vectors = ArrayList<FloatArray>(1 + windows.size)
        coordinator.withModel(RequiredModel.TEXT_EMB) {
            // Index 0 is always the document, even when empty, so window indices stay stable.
            vectors += if (document.isNotBlank()) embedder.embed(document) else FloatArray(0)
            for ((_, text) in windows) {
                if (ctx.isStopRequested()) return@withModel
                vectors += embedder.embed(text)
            }
        }
        if (ctx.isStopRequested() && vectors.size < 1 + windows.size) return PassOutcome.STOPPED
        ctx.emit(PartialResult.TextVectors(vectors, embedder.modelId, listOf(null) + windows.map { it.first }), Checkpoint.None)
        return PassOutcome.COMPLETED
    }

}

/**
 * Writes open-vocabulary concept labels for an item that already has an image embedding.
 *
 * Uses no model: it scores the stored vector against seeded and learned prototypes, so re-labelling
 * everything after a vocabulary change is cheap. SKIPPED when there is nothing to score against, so
 * the unit stays eligible for requeue once the vocabulary is seeded.
 */
class ConceptsPassHandler(
    private val classifier: ai.dusty.finderplus.index.ConceptClassifier,
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

        // The coarse domain ("people", "screen", "vehicles") is this head's most reliable output,
        // since domains are visually distinct in a way individual concepts are not. Written for video
        // only: IMAGE_LABEL already writes CATEGORY for images, and a re-emitted source replaces that
        // source wholesale, so writing it here would overwrite that pass's answer.
        val category = reading.domain
            ?.takeIf { it !in NON_CATEGORY_DOMAINS }
            ?.let { Tag(item.id, TagSource.CATEGORY, it, reading.domainConfidence) }

        if (concepts.isEmpty() && category == null) return PassOutcome.SKIPPED

        // Three bands, since one cutoff cannot serve both precision and recall: assert what is
        // confident, ask about what is plausible, discard the rest. Weak tags hurt search while
        // reading as fact.
        val tags = concepts.mapNotNull {
            val source = when {
                // A label backed by the user's own exemplars is trusted at its own (image-space) scale.
                it.taught -> ai.dusty.finderplus.model.TagSource.LEARNED
                // A name (person or brand) is only ever proposed, never asserted, however confident:
                // mistaking one person for another is the error users treat as broken.
                it.isEntity ->
                    ai.dusty.finderplus.model.TagSource.SUGGESTED
                it.score >= AUTO_APPLY_CONFIDENCE -> ai.dusty.finderplus.model.TagSource.CONCEPT
                it.score >= REVIEW_CONFIDENCE -> ai.dusty.finderplus.model.TagSource.SUGGESTED
                else -> return@mapNotNull null
            }
            Tag(itemId = item.id, source = source, label = it.label, confidence = it.score)
        } + listOfNotNull(category)

        // Cleared even when this run produced none, so a re-label after a threshold or prototype
        // change removes what no longer qualifies. LEARNED is machine-derived too; keeping it would
        // make taught-label mistakes permanent.
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
         * Joint confidence at or above which a concept is asserted without asking. Set where a label
         * is unmistakable, not merely plausible; correct labels below it go to review instead. A wrong
         * tag costs more than a missing one because, once written, it looks like a right one.
         */
        val AUTO_APPLY_CONFIDENCE: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.SimilaritySettings.CONCEPT_APPLY].toFloat()

        /** Below [AUTO_APPLY_CONFIDENCE] but worth one question; anything lower is discarded. */
        val REVIEW_CONFIDENCE: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.SimilaritySettings.CONCEPT_ASK].toFloat()

        /**
         * Domains that say nothing about what an item is: `entities` is an identity branch and
         * `quality` describes photographic style. Useful as concepts, not as a category.
         */
        val NON_CATEGORY_DOMAINS = setOf("entities", "quality")
    }
}
