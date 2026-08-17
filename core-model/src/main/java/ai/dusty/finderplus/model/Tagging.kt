package ai.dusty.finderplus.model

enum class TagSource {
    LABEL, OBJECT, OCR_KEYWORD, CATEGORY, USER,

    /**
     * Produced by the VLM (Gemma 4). Kept as its own source so model-generated labels carry explicit
     * provenance — they can be ranked differently, shown as "AI suggested", or purged wholesale if a
     * model turns out to be unreliable, without touching the deterministic signals.
     */
    VLM,

    /**
     * Applied automatically because it matched a learned prototype with high confidence. Kept distinct
     * from USER (ground truth the person typed) and from VLM, so a learned label can be re-evaluated or
     * revoked wholesale when its prototype changes.
     */
    LEARNED,

    /**
     * Recognized from the shipped concept vocabulary by the zero-shot head — the backbone's own world
     * knowledge, not something this gallery taught it.
     *
     * Separate from [LEARNED] (which came from the user's examples) because the two have different
     * trust and different lifetimes: the whole CONCEPT layer is discarded and rebuilt whenever the
     * vocabulary or encoder changes, while a LEARNED tag is backed by exemplars the user supplied.
     */
    CONCEPT,

    /**
     * Recognized, but not confidently enough to assert. Awaits the user's yes/no.
     *
     * This exists because auto-applying everything the classifier returned was actively harmful: 60% of
     * concept tags landed below 0.10 joint confidence — `soup` at 0.037, `person dancing` at 0.037 — and
     * every one of them polluted search while looking authoritative. A guess the model cannot stand
     * behind belongs in a queue, not in the index.
     *
     * SUGGESTED tags are deliberately excluded from the profile and the FTS row, so they are invisible to
     * search until confirmed. Accepting one promotes it to [USER] and teaches the prototype; rejecting it
     * sharpens the prototype's negative side.
     */
    SUGGESTED,
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

enum class EmbeddingKind { IMAGE_CLIP, TEXT_TRANSCRIPT }

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
