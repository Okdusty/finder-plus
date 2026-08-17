package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.ItemState
import ai.dusty.finderplus.db.entity.MediaProfileEntity
import ai.dusty.finderplus.model.DocSource
import ai.dusty.finderplus.model.TagSource

/**
 * After a pass completes, refreshes the item's search artifacts — the FTS row AND the consolidated
 * "AI revision" profile — and re-projects its coarse index_state. The profile is the long searchable
 * text that navigates to specific content and is copied to the clipboard on tap.
 * See docs/design/01-DB-ENGINE.md §2.5, docs/design/05-MEDIA-PROFILE.md.
 */
internal class ItemFinalizer(
    private val db: FinderDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Corpus statistics used to decide which words and labels are distinctive here.
     *
     * Passed in and reused across a batch: counting is gallery-wide, so loading it per item would make
     * rebuilding 4,847 profiles quadratic in the corpus.
     */
    private val terms: TermStats? = null,
) {

    companion object {
        /**
         * Version of the derived search artifacts (profile text + FTS columns). Bump whenever the
         * profile format changes — e.g. when the fused summary and ranked key objects were added, every
         * already-indexed item still carried the old profile and needed re-deriving.
         */
        // 3: the profile gained concept labels, user labels, and date/duration/orientation words —
        // fields that were on every row since the first scan but had never been made searchable.
        // 4: every video profile was built from the union of its keyframes' ML Kit labels, so the text
        // that search matches on — and that a tap copies to the clipboard — still names things no frame
        // contained. Those tags are gone (migration 7→8) and the profiles quoting them must be rebuilt.
        // 5: OCR keyword tags were deleted (migration 8→9), and every profile built before that quoted
        // them in its "Tags:" line — the same tokens the "Text:" line already carried.
        // 6: keywords are now the corpus-distinctive words (TF-IDF against gallery document frequency)
        // rather than the first 24 tokens, and the summary names them instead of quoting a text prefix.
        // 7: profiles gained the VLM caption and YOLO object detections.
        const val SEARCH_ARTIFACT_VERSION = 7
    }

    /** Rebuild the FTS row + the consolidated profile from all committed passes for [itemId]. */
    suspend fun rebuildSearch(itemId: Long) {
        val item = db.mediaItemDao().byId(itemId) ?: return
        val content = db.contentDao()

        // Keep the TRANSCRIPT document text in sync so snippets/search read a full string.
        content.document(itemId, DocSource.TRANSCRIPT.ordinal)?.let { doc ->
            content.setDocumentText(doc.id, content.transcriptBlob(itemId) ?: "", doc.lang)
        }

        val name = item.display_name
        val ocr = content.documentText(itemId, DocSource.OCR.ordinal)
        val transcript = content.transcriptBlob(itemId)
        val caption = content.documentText(itemId, DocSource.CAPTION.ordinal)
        val place = item.place
        val bucket = item.bucket_name

        // Fuse every signal into one coherent description + ranked key objects, so the profile reads as
        // a description rather than a bag of duplicates.
        //
        // This used to be where video's per-frame label flood was compensated for, by de-duplicating
        // dozens of repeated keyframe labels down to something readable. That was treating the symptom:
        // the labels are no longer produced per frame at all, because summarizing a contradictory set
        // still yields a contradictory summary. See KeyframesPassHandler.
        val allTags = content.tagsForItem(itemId)
        fun tagsOf(source: TagSource) = allTags.filter { it.source == source.ordinal }.map { it.label }

        // Distinctive words from this item's own text, chosen by how rare they are across the gallery.
        // Written back as tags so they are searchable and visible as chips, and fed to the summarizer so
        // it can name what the item is about instead of quoting an arbitrary prefix of the text.
        val stats = terms?.cached()
        val keywords = stats?.keywordsFor(listOfNotNull(ocr, transcript).joinToString(" ").ifBlank { null })
            ?: emptyList()
        content.clearTags(itemId, TagSource.OCR_KEYWORD.ordinal)
        if (keywords.isNotEmpty()) {
            content.insertTags(keywords.map {
                ai.dusty.finderplus.db.entity.TagEntity(
                    item_id = itemId, source = TagSource.OCR_KEYWORD.ordinal, label = it, confidence = 1f,
                )
            })
        }

        val summary = MediaSummarizer.summarize(
            MediaEvidence(
                displayName = name,
                labels = tagsOf(TagSource.LABEL),
                objects = tagsOf(TagSource.OBJECT),
                // SUGGESTED is intentionally absent: an unconfirmed guess must not reach the profile,
                // which is both the search text and what gets copied to the clipboard.
                concepts = tagsOf(TagSource.CONCEPT),
                // USER and LEARNED are merged: both trace back to something the person actually said
                // this item is, one directly and one through a prototype they built.
                userLabels = tagsOf(TagSource.USER) + tagsOf(TagSource.LEARNED),
                ocrText = ocr,
                transcript = transcript,
                caption = caption,
                peopleNames = db.faceDao().personNamesFor(itemId)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                faceCount = db.faceDao().faceCount(itemId),
                place = place,
                album = bucket,
                durationMs = item.duration_ms,
                dateTakenMs = item.date_taken,
                width = item.width,
                height = item.height,
                textKeywords = keywords,
            ),
            commonLabels = stats?.let { snap -> MediaSummarizer.CommonLabels { snap.isCommonLabel(it) } }
                ?: MediaSummarizer.CommonLabels.NONE,
        )

        // Read AFTER the keyword write above, so the FTS row and profile include them. Reading it
        // earlier silently indexed the previous run's keywords.
        val tags = content.tagBlob(itemId)

        // 1. FTS row (weighted columns for ranked keyword search).
        val fts = db.ftsDao()
        fts.deleteRow(itemId)
        // The caption rides in the tags column: FTS4's schema is fixed, and caption words deserve
        // tag-level weight — they are the one sentence that says what the item IS.
        val tagsPlusKeyObjects = listOfNotNull(tags, summary.keyObjects.joinToString(" ").ifEmpty { null }, caption)
            .joinToString(" ")
        fts.insertRow(itemId, name, tagsPlusKeyObjects, ocr, transcript, place, bucket)

        // 2. Consolidated "AI revision" profile (long searchable text + clipboard content).
        val profile = ProfileBuilder.assemble(name, tags, ocr, transcript, place, bucket, summary.summary)
        db.mediaProfileDao().upsert(MediaProfileEntity(item_id = itemId, text = profile, updated_at = now()))

        // Stamp the artifact format so a later format change can find and re-derive stale profiles
        // without re-running any AI pass.
        db.mediaItemDao().setPipelineVersion(itemId, SEARCH_ARTIFACT_VERSION)
    }

    suspend fun projectState(itemId: Long) {
        val remaining = db.workUnitDao().remainingForItem(itemId)
        val failed = db.workUnitDao().failedForItem(itemId)
        val state = when {
            remaining == 0 && failed == 0 -> ItemState.DONE
            remaining == 0 && failed > 0 -> ItemState.FAILED
            else -> ItemState.PARTIAL
        }
        db.mediaItemDao().setState(itemId, state, now())
    }
}

/** Assembles the labeled, long searchable text profile. Sections are omitted when empty. */
internal object ProfileBuilder {
    fun assemble(
        name: String?, tags: String?, ocr: String?, transcript: String?, place: String?, bucket: String?,
        summary: String? = null,
    ): String = buildString {
        name?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        summary?.takeIf { it.isNotBlank() }?.let { appendLine("Summary: $it") }
        tags?.takeIf { it.isNotBlank() }?.let { appendLine("Tags: $it") }
        ocr?.takeIf { it.isNotBlank() }?.let { appendLine("Text: $it") }
        transcript?.takeIf { it.isNotBlank() }?.let { appendLine("Transcript: $it") }
        place?.takeIf { it.isNotBlank() }?.let { appendLine("Location: $it") }
        bucket?.takeIf { it.isNotBlank() }?.let { appendLine("Album: $it") }
    }.trim()
}
