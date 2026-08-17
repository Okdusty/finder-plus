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
    // The cheap passes share priority tier 10 so, ordered by (priority, id), an item's
    // metadata+label+OCR run as a group before the next item — each item becomes fully
    // text-searchable progressively, instead of all metadata across the gallery running first.
    METADATA(10, RequiredModel.NONE, version = 1),

    /**
     * **Retired** — no longer enqueued by [forKind]. Kept only because ordinals are persisted.
     *
     * ML Kit's 400-concept labeler, whose vocabulary is the wrong shape for search. Measured over 3,274
     * indexed photos, its most frequent labels were `Screenshot` 813, `Poster` 632, `Mouth` 410,
     * `Smile` 397, `Eyelash` 395, `Dude` 314, `Flesh` 309, `Fun` 297 — body-part fragments and
     * abstractions making up roughly 60% of its output, none of which anyone types into a search box.
     *
     * Its genuinely useful labels (`Screenshot`, `Selfie`, `Dog`, `Vehicle`) are all covered by
     * [CONCEPTS], which reaches them through an open vocabulary with calibrated confidence instead of a
     * fixed list with none. Removing it costs no recall and removes the noise.
     */
    IMAGE_LABEL(10, RequiredModel.MLKIT, version = 1),
    OCR(10, RequiredModel.MLKIT, version = 1),
    KEYFRAMES(40, RequiredModel.NONE, version = 2),
    // version 2: the first pass ran with a stubbed encoder and wrote 16,253 all-zero vectors. Bumping
    // forces those to be re-embedded once a real CLIP model is installed, rather than leaving a
    // degenerate space in which every item is "similar" to every other.
    // version 3: upgraded the encoder from CLIP ViT-B/32 to ViT-B/16. The two produce vectors in
    // DIFFERENT spaces despite both being 512-d, so a mixed index silently returns nonsense rather
    // than failing — every item must be re-embedded before any of them can be compared.
    IMAGE_EMBED(50, RequiredModel.CLIP_IMG, version = 3),
    TRANSCRIBE(60, RequiredModel.ASR, version = 3),

    /**
     * **Retired** — no longer enqueued by [forKind]. Kept only because ordinals are persisted.
     *
     * Semantic embedding of transcript chunks. Its encoder was never implemented: `OnnxTextEmbedder.embed`
     * returns `FloatArray(dim)` — all zeros — and the model it names has no download URL, so it cannot
     * become real by installing anything. The pass nonetheless ran, decoded and chunked every transcript,
     * and wrote those zero vectors into the embedding table.
     *
     * That is not a harmless no-op. A zero vector has cosine similarity 0 with everything, so it ties
     * with every other zero vector and injects arbitrary ordering into transcript search. The identical
     * bug already cost a full re-embed once, when a stubbed CLIP encoder wrote 16,253 zero vectors (see
     * [IMAGE_EMBED] version 2). Re-enable when there is an encoder, not before.
     */
    TEXT_EMBED(70, RequiredModel.TEXT_EMB, version = 1),

    /**
     * Object detection → concrete OBJECT tags with boxes. Runs on images and on video thumbnails.
     *
     * version 2: the detector changed from ML Kit's coarse categories to YOLOX-tiny's 80 COCO classes.
     * ML Kit's most common output on this gallery was literally `multiple objects` (723 items) followed
     * by `fashion good` — buckets nobody searches. The version bump re-runs detection everywhere so
     * `person`, `cup`, `bicycle` replace them. NONE rather than MLKIT residency: the ONNX session is
     * ~20 MB and lives outside the heavy-model lock, like the face embedder.
     */
    OBJECTS(10, RequiredModel.NONE, version = 2),

    /**
     * Face detection plus identity embedding of each crop. Cheap tier, ML Kit bundled.
     *
     * version 2: the first run embedded crops with CLIP, which measurably does not separate identity —
     * its most similar face crops on this gallery were different people in the same photo. Bumping
     * re-runs detection so MobileFaceNet produces the embeddings instead; the write path already clears
     * an item's faces before inserting, so a re-run converges rather than duplicating.
     */
    FACES(10, RequiredModel.MLKIT, version = 2),

    /**
     * Open-vocabulary concept labelling: scores the stored image embedding against the seeded concept
     * vocabulary and the user's learned prototypes, and writes the winners as tags.
     *
     * Needs no model of its own — it is pure arithmetic over rows already in the database, which is why
     * re-labelling the whole gallery after a threshold or vocabulary change costs seconds rather than a
     * re-index.
     *
     * Shares [IMAGE_EMBED]'s priority tier on purpose. It consumes that pass's vector, so it must not
     * run first — the ledger orders ties by (item_id, id) and this pass is enqueued second, so within
     * one item the embedding always precedes it. Giving it a *later* tier instead would be wrong in
     * practice: every embedding in the gallery would then have to finish before the first concept tag
     * appeared, which on a full re-embed means hours of indexing with nothing to show for it.
     *
     * Also runs on **video**, where it consumes the per-keyframe vectors written by [KEYFRAMES] and
     * averages the per-frame posteriors. That is now the only source of video labels: the keyframe pass
     * used to attach each frame's ML Kit guesses to the file, which produced the union of twenty
     * independent classifications rather than a statement about the video.
     *
     * Since [IMAGE_LABEL] was retired this is the main source of labels, joined by [OBJECTS]'s COCO
     * detections; it also writes the coarse category [IMAGE_LABEL] used to supply.
     *
     * version 2: the auto-apply band was tightened from 0.20 to 0.35, and the category is now written
     * for every kind rather than only video.
     *
     * version 3: taught labels now need exemplar-scaled confidence (a 3-example prototype propagated
     * `cem yılmaz` onto 73 screenshots at the flat 0.78 bar), entity names always land as SUGGESTED,
     * and re-runs clear stale LEARNED tags. Re-running everywhere is what retracts the 200+ existing
     * over-propagations — arithmetic over stored vectors, no model work.
     */
    CONCEPTS(50, RequiredModel.NONE, version = 3),

    /**
     * One-sentence VLM caption of the image, or of a video's thumbnail frame.
     *
     * Sits between [CONCEPTS] (50) and [TRANSCRIBE] (60) on purpose: cheap description first, and for
     * video the thumbnail caption lands *before* hours of ASR — a clip becomes findable by what it
     * looks like while its transcript is still queued. Thumbnail-only for video is deliberate: per-frame
     * VLM was measured at ~580× CLIP's cost per item, and a video's searchable detail is already carried
     * by its transcript and keyframe embeddings; the caption only has to say what kind of thing it is.
     *
     * Token-capped and governed by a wall-clock budget (see CaptionBudget) — when the device cannot
     * caption the whole gallery in the allowed time, the remainder parks as SKIPPED and revives on the
     * next run rather than blocking anything else.
     */
    CAPTION(55, RequiredModel.VLM, version = 1);

    /**
     * Whether this pass produces text that belongs in the FTS row or the profile.
     *
     * [IMAGE_EMBED] is the notable false case: a vector is not text, and rebuilding an item's searchable
     * artifacts after it changes nothing. Skipping the rebuild there matters because it is the single
     * most-run pass in the gallery.
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
        OBJECTS -> "detecting objects"
        FACES -> "finding people"
        CONCEPTS -> "recognizing concepts"
        CAPTION -> "describing scenes"
    }

    companion object {
        /** Passes enqueued for a freshly discovered item of [kind]. */
        fun forKind(kind: MediaKind): List<Pass> = when (kind) {
            // IMAGE_LABEL and TEXT_EMBED are deliberately absent — see their KDoc. They remain in
            // the enum because ordinals are persisted, but nothing enqueues them any more.
            MediaKind.IMAGE -> listOf(METADATA, OCR, OBJECTS, FACES, IMAGE_EMBED, CONCEPTS, CAPTION)
            // CONCEPTS consumes the keyframe vectors, and its later priority tier (50 vs KEYFRAMES' 40)
            // is what guarantees they exist first. A video whose frames are not embedded yet classifies
            // to nothing and reports SKIPPED, which leaves the unit eligible rather than done-and-empty.
            MediaKind.VIDEO -> listOf(METADATA, OBJECTS, KEYFRAMES, TRANSCRIBE, CONCEPTS, CAPTION)
            MediaKind.AUDIO -> listOf(METADATA, TRANSCRIBE)
        }
    }
}
