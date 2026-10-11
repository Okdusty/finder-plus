package ai.dusty.finderplus.model

/** The heavy model a [Pass] needs resident. NONE and MLKIT are lightweight and exempt from the
 *  single-heavy-model residency lock; the rest are gated by it. Ordinals are persisted onto work
 *  units — append only. */
enum class RequiredModel { NONE, MLKIT, CLIP_IMG, ASR, TEXT_EMB, VLM }

/**
 * A unit of indexing work applied to one media item. [priority] drives cheap-before-expensive
 * ordering (lower runs first); [model] tells the engine which heavy model the pass needs so it can
 * batch by residency. [version] is the pipeline version: bumping it (e.g. after a model upgrade)
 * lets the engine selectively re-index only stale units for this pass.
 *
 * Ordinals are persisted — do not reorder existing entries.
 */
enum class Pass(val priority: Int, val model: RequiredModel, val version: Int) {
    // The cheap passes share priority tier 10 so that, ordered by (priority, id), one item's cheap
    // passes run together before the next item's. Items become searchable one at a time instead of
    // all metadata running first.
    METADATA(10, RequiredModel.NONE, version = 1),

    /**
     * Retired: no longer enqueued by [forKind], kept only because ordinals are persisted.
     *
     * ML Kit's fixed 400-concept labeler. Most of its output was body parts and abstractions nobody
     * searches for, and its useful labels are covered by [CONCEPTS] through an open vocabulary with
     * calibrated confidence.
     */
    IMAGE_LABEL(10, RequiredModel.MLKIT, version = 1),
    // version 2: reads the detector's [1, 1, H, W] output correctly (v1 units completed empty) and
    // decodes photos upright.
    OCR(10, RequiredModel.MLKIT, version = 2),
    KEYFRAMES(40, RequiredModel.NONE, version = 2),
    // version 2: re-embeds the all-zero vectors written by a stub encoder.
    // version 3: CLIP ViT-B/32 to ViT-B/16. Both are 512-d but live in different spaces, so every item
    // must be re-embedded before any can be compared; a mixed index returns nonsense without failing.
    IMAGE_EMBED(50, RequiredModel.CLIP_IMG, version = 3),
    TRANSCRIBE(60, RequiredModel.ASR, version = 3),

    /**
     * Semantic text vectors from EmbeddingGemma 2: one for the item's whole description (caption,
     * summary, labels, OCR, place) and one per minute of transcript.
     *
     * Last in priority because it reads the text every other pass produced. Parks as SKIPPED until the
     * model is installed and revives once it is.
     *
     * version 2: a real model replaces a stub that returned zero vectors, which tie with everything
     * under cosine, and every item's text is embedded, not just transcripts. Each run replaces the
     * item's text vectors wholesale, so old zero rows are purged as it goes.
     * version 3: transcript windows are built from speech only, no longer mixed with text read off
     * keyframes, and each window's start in the media is stored.
     * version 4: the item document no longer includes the caption sentence.
     */
    TEXT_EMBED(70, RequiredModel.TEXT_EMB, version = 4),

    /**
     * Describing: a caption and located objects (OBJECT tags) for images and video thumbnails, from one
     * read of the describe model. Doubted object labels are put to the user (see LabelDoubt).
     *
     * Priority 55, between [CONCEPTS] and [TRANSCRIBE]: it is the most expensive pass per photo, so the
     * cheap passes make an item searchable first. VLM residency, since the model is too large to share
     * memory with another heavy one.
     *
     * version 5: Florence-2 replaces both YOLOX-tiny's 80 classes and the separate caption pass.
     * version 6: only objects the caption grounds are kept, and a video is described from its most
     * typical keyframe rather than the platform thumbnail.
     */
    OBJECTS(55, RequiredModel.VLM, version = 6),

    /**
     * Face detection for the people tags ([TagSource.FACE]). Cheap tier. The write path clears an
     * item's faces before inserting, so a re-run converges rather than duplicating.
     *
     * version 2: stops using CLIP for face crops, since CLIP does not separate identity.
     * version 3: upright decoding, and people written once under their own FACE source.
     */
    FACES(10, RequiredModel.MLKIT, version = 3),

    /**
     * Open-vocabulary concept labelling: scores the stored image embedding against the seeded concept
     * vocabulary and the user's learned prototypes, and writes the winners as tags. It needs no model
     * of its own, so re-labelling everything after a threshold or vocabulary change is cheap.
     *
     * Shares [IMAGE_EMBED]'s priority tier on purpose. It consumes that pass's vector, and the ledger
     * orders ties by (item_id, id) with this pass enqueued second, so within one item the embedding
     * comes first. A later tier would hold back every concept tag until all items were embedded.
     *
     * On video it averages per-frame posteriors over the keyframe vectors from [KEYFRAMES], giving one
     * statement about the video rather than a union of per-frame guesses. With [IMAGE_LABEL] retired it
     * is the main label source alongside [OBJECTS], and it also writes the coarse category.
     *
     * version 2: auto-apply band raised from 0.20 to 0.35; category written for every kind.
     * version 3: taught labels need exemplar-scaled confidence so a prototype built from a few examples
     * cannot spread widely, entity names always land as SUGGESTED, and re-runs clear stale LEARNED tags.
     */
    CONCEPTS(50, RequiredModel.NONE, version = 3),

    /** Retired: [OBJECTS] writes the caption from the same read of the image. Kept because ordinals are persisted. */
    CAPTION(55, RequiredModel.VLM, version = 1);

    /**
     * Whether this pass produces text that belongs in the FTS row or the profile.
     *
     * [IMAGE_EMBED] is the notable false case: a vector is not text, so rebuilding an item's searchable
     * artifacts after it changes nothing, and it is the most frequently run pass.
     */
    val contributesText: Boolean
        get() = when (this) {
            METADATA, IMAGE_LABEL, OCR, KEYFRAMES, TRANSCRIBE, OBJECTS, FACES, CONCEPTS, CAPTION -> true
            IMAGE_EMBED, TEXT_EMBED -> false
        }

    /** Short human phase label for the widget / notification status line. */
    fun uiLabel(): String = when (this) {
        METADATA -> "reading details"
        IMAGE_LABEL -> "recognizing content"
        OCR -> "reading text"
        KEYFRAMES -> "scanning video"
        IMAGE_EMBED -> "building visual search"
        TRANSCRIBE -> "transcribing speech"
        TEXT_EMBED -> "building text search"
        OBJECTS -> "describing photos"
        FACES -> "finding people"
        CONCEPTS -> "recognizing concepts"
        CAPTION -> "describing scenes"
    }

    companion object {
        /** Every pass the pipeline has for [kind], whether or not the user has it switched on. */
        fun pipelineFor(kind: MediaKind): List<Pass> = when (kind) {
            // IMAGE_LABEL is retired; see its KDoc.
            MediaKind.IMAGE -> listOf(METADATA, OCR, OBJECTS, FACES, IMAGE_EMBED, CONCEPTS, TEXT_EMBED)
            // CONCEPTS consumes the keyframe vectors; its later priority tier (50 vs KEYFRAMES' 40)
            // makes them exist first. A video whose frames are not embedded yet reports SKIPPED, which
            // leaves the unit eligible rather than done-and-empty.
            MediaKind.VIDEO -> listOf(METADATA, OBJECTS, KEYFRAMES, TRANSCRIBE, CONCEPTS, TEXT_EMBED)
            MediaKind.AUDIO -> listOf(METADATA, TRANSCRIBE, TEXT_EMBED)
        }

        /** The passes that run on [kind]: its pipeline, less those switched off in the settings. */
        fun forKind(kind: MediaKind): List<Pass> =
            pipelineFor(kind).filter { ai.dusty.finderplus.settings.MediaSettings.enabled(kind, it) }

        /** Passes no media kind has any more; their unfinished units are dropped rather than left to fail. */
        val retired: Set<Pass> get() = entries.toSet() - MediaKind.entries.flatMap(::pipelineFor).toSet()
    }
}
