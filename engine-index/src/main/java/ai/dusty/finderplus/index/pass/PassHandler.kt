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
     * @param clearSources sources to clear even when [tags] contains none of them. Needed so a pass can
     *   *remove* a tag it once wrote — otherwise a source only ever grows, since the sources to clear
     *   are inferred from what is being written.
     */
    data class LabelTags(
        val tags: List<Tag>,
        val clearSources: Set<ai.dusty.finderplus.model.TagSource> = emptySet(),
    ) : PartialResult
    data class OcrText(val text: String, val lang: String?, val keywords: List<String>) : PartialResult
    data class TranscriptSegments(val segments: List<ai.dusty.finderplus.model.Segment>) : PartialResult
    data class ImageVector(val vec: FloatArray, val sourceRef: Int, val modelId: String) : PartialResult
    data class TextVector(val vec: FloatArray, val sourceRef: Int, val modelId: String) : PartialResult

    /** One-sentence VLM description of the item (image, or a video's thumbnail frame). */
    data class Caption(val text: String) : PartialResult
    /** Detected faces plus the derived people tags; the writer stores boxes and clusters them. */
    data class Faces(
        val faces: List<ai.dusty.finderplus.vision.DetectedFace>,
        val tags: List<Tag>,
    ) : PartialResult

    /**
     * One video keyframe: its recognized text and its embedding.
     *
     * Carries no tags by design — see [ai.dusty.finderplus.index.pass.KeyframesPassHandler] for why
     * per-frame classifications must not become tags on the whole video.
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
 * Per-unit sink handed to a [PassHandler]. [emit] commits a result AND advances the checkpoint in a
 * single transaction — the handler never touches the DB directly, so the invariant "checkpoint never
 * ahead of committed results" cannot be violated. See docs/design/01-DB-ENGINE.md §11.
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
