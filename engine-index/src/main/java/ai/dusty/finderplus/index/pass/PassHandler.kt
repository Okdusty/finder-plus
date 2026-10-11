package ai.dusty.finderplus.index.pass

import ai.dusty.finderplus.db.entity.WorkUnitEntity
import ai.dusty.finderplus.index.work.Checkpoint
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.Pass
import ai.dusty.finderplus.model.Tag

/** A committed micro-batch produced by a pass. The [PassContext] persists these atomically. */
sealed interface PartialResult {
    data class Meta(val place: String?, val lat: Double?, val lon: Double?) : PartialResult

    /**
     * Tags replacing everything this item previously had from the same sources.
     *
     * @param clearSources sources to clear even when [tags] has none of them, so a pass can remove a
     *   tag it once wrote. Without it a source only grows, since cleared sources are otherwise
     *   inferred from what is written.
     * @param questions when not null, replaces the item's pending label questions in the same write.
     * @param regions when not null, replaces the item's object-region vectors, by label.
     */
    data class LabelTags(
        val tags: List<Tag>,
        val clearSources: Set<ai.dusty.finderplus.model.TagSource> = emptySet(),
        val questions: List<ai.dusty.finderplus.db.entity.LabelQuestionEntity>? = null,
        val regions: Map<String, FloatArray>? = null,
        /** For a video: where in it the [regions] were seen. */
        val regionsAtMs: Long? = null,
    ) : PartialResult
    data class OcrText(val text: String, val lang: String?, val keywords: List<String>) : PartialResult
    data class TranscriptSegments(val segments: List<ai.dusty.finderplus.model.Segment>) : PartialResult
    data class ImageVector(val vec: FloatArray, val sourceRef: Int, val modelId: String) : PartialResult

    /**
     * Every text vector for the item, replacing whatever it had: index 0 is the whole-item document,
     * 1.. are transcript windows. Written as one set so a re-run converges instead of accumulating.
     */
    data class TextVectors(
        val vectors: List<FloatArray>,
        val modelId: String,
        /** Where each window starts in the media, aligned with [vectors]; null for the document at index 0. */
        val startsMs: List<Long?> = emptyList(),
    ) : PartialResult

    /** One-sentence description of the item: an image, or one frame of a video, at [atMs] when known. */
    data class Caption(val text: String, val atMs: Long? = null) : PartialResult
    /** Detected faces plus the derived people tags; the writer stores boxes and clusters them. */
    data class Faces(
        val faces: List<ai.dusty.finderplus.vision.DetectedFace>,
        val tags: List<Tag>,
    ) : PartialResult

    /**
     * One video keyframe: its recognized text and its embedding. Carries no tags by design; see
     * [ai.dusty.finderplus.index.pass.KeyframesPassHandler].
     */
    data class Frame(
        val frameIndex: Int, val timestampMs: Long,
        val ocrText: String?, val vec: FloatArray?, val modelId: String,
    ) : PartialResult
}

enum class PassOutcome {
    COMPLETED,
    STOPPED,

    /**
     * Not applicable / prerequisite absent (e.g. no speech model installed). Recorded as SKIPPED
     * rather than FAILED so it neither retries nor marks the item as broken.
     */
    SKIPPED,
}

/**
 * Per-unit sink handed to a [PassHandler]. [emit] commits a result and advances the checkpoint in one
 * transaction, and handlers never touch the DB directly, so the checkpoint is never ahead of committed
 * results. See docs/design/01-DB-ENGINE.md §11.
 */
interface PassContext {
    suspend fun emit(result: PartialResult, next: Checkpoint)
    suspend fun isStopRequested(): Boolean
}

/**
 * One indexing pass over one item. Reads [checkpoint] to resume, does work in micro-batches, and
 * returns [PassOutcome.STOPPED] if it observed a cooperative stop at a boundary (leaving the unit
 * resumable) or [PassOutcome.COMPLETED] when the whole pass is done.
 */
interface PassHandler {
    val pass: Pass
    suspend fun process(
        item: MediaItem,
        unit: WorkUnitEntity,
        checkpoint: Checkpoint,
        ctx: PassContext,
    ): PassOutcome
}
