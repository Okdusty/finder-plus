package ai.dusty.finderplus.model

enum class TagSource {
    LABEL, OBJECT, OCR_KEYWORD, CATEGORY, USER,

    /**
     * Produced by a VLM. Its own source so model-generated labels can be ranked differently, shown as
     * "AI suggested", or purged wholesale without touching the deterministic signals.
     */
    VLM,

    /**
     * Applied automatically because it matched a learned prototype with high confidence. Distinct from
     * USER (ground truth the person typed) and from VLM, so a learned label can be re-evaluated or
     * revoked wholesale when its prototype changes.
     */
    LEARNED,

    /**
     * Recognized from the shipped concept vocabulary by the zero-shot head: the backbone's own
     * knowledge, not something the user taught it.
     *
     * Separate from [LEARNED] because the two have different trust and lifetimes: the whole CONCEPT
     * layer is rebuilt whenever the vocabulary or encoder changes, while a LEARNED tag is backed by
     * exemplars the user supplied.
     */
    CONCEPT,

    /**
     * Recognized, but not confidently enough to assert; awaits the user's yes/no. Low-confidence
     * guesses applied as tags pollute search while looking authoritative, so they wait in a queue.
     *
     * Excluded from the profile and the FTS row, so invisible to search until confirmed. Accepting one
     * promotes it to [USER] and teaches the prototype; rejecting it sharpens the prototype's negative
     * side.
     */
    SUGGESTED,

    /**
     * People as the face pass sees them: exactly one of `portrait`, `one person`, `two people`,
     * `group of people`, plus `smiling`. Separate from OBJECT so these stay distinct from the
     * detector's `person`, and re-running one pass does not clear the other's tags.
     */
    FACE,

    /**
     * A detection the detector cannot stand behind, waiting for the user's vote. Like [SUGGESTED] it is
     * kept out of search, the profile and the summary until confirmed. Currently: boxes covering ≥ 90%
     * of the frame, where an object outside the detector's 80 classes gets forced onto the nearest one
     * with high confidence.
     */
    OBJECT_REVIEW,
}

data class Tag(
    val itemId: Long,
    val source: TagSource,
    val label: String,
    val confidence: Float,
)

enum class DocSource { OCR, TRANSCRIPT, /** VLM-generated caption/summary. */ CAPTION }

/** Full extracted text for an item (one per (item, source)). */
data class Document(
    val itemId: Long,
    val source: DocSource,
    val lang: String?,
    val text: String,
)

/** A timestamped slice of A/V text (transcript window or keyframe OCR) — enables "where in the media". */
data class Segment(
    val itemId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Which vector space an embedding lives in. Ordinals are persisted — append only.
 *
 * [TEXT] keeps the ordinal of the former `TEXT_TRANSCRIPT`. Rows from the old stub encoder (384-d
 * zeros) are skipped by dimension and purged on re-index.
 */
enum class EmbeddingKind { IMAGE_CLIP, TEXT }

/**
 * A vector for an item. [sourceRef] disambiguates multiple vectors per item (keyframe index or
 * transcript chunk index). [vec] is stored L2-normalized so a dot product equals cosine similarity.
 */
data class Embedding(
    val itemId: Long,
    val kind: EmbeddingKind,
    val sourceRef: Int,
    val modelId: String,
    val vec: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Embedding) return false
        return itemId == other.itemId && kind == other.kind && sourceRef == other.sourceRef &&
            modelId == other.modelId && vec.contentEquals(other.vec)
    }

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + sourceRef
        result = 31 * result + modelId.hashCode()
        result = 31 * result + vec.contentHashCode()
        return result
    }
}
