package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.ItemState
import ai.dusty.finderplus.db.entity.MediaProfileEntity
import ai.dusty.finderplus.model.DocSource
import ai.dusty.finderplus.model.TagSource

/**
 * After a pass completes, refreshes the item's search artifacts (the FTS row and the consolidated
 * profile) and re-projects its index_state. The profile is the long searchable text that is also
 * copied to the clipboard on tap.
 * See docs/design/01-DB-ENGINE.md §2.5, docs/design/05-MEDIA-PROFILE.md.
 */
internal class ItemFinalizer(
    private val db: FinderDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Corpus statistics that decide which words and labels are distinctive. Passed in and reused
     * across a batch, since loading them per item would make a bulk rebuild quadratic.
     */
    private val terms: TermStats? = null,
) {

    companion object {
        /**
         * Version of the derived search artifacts (profile text and FTS columns). Bump whenever their
         * format changes, so stale profiles are re-derived without re-running any AI pass.
         */
        const val SEARCH_ARTIFACT_VERSION = 9
    }

    /** Rebuild the FTS row + the consolidated profile from all committed passes for [itemId]. */
    suspend fun rebuildSearch(itemId: Long) {
        val item = db.mediaItemDao().byId(itemId) ?: return
        val content = db.contentDao()

        // Keep the TRANSCRIPT document text in sync so snippets/search read a full string.
        content.document(itemId, DocSource.TRANSCRIPT.ordinal)?.let { doc ->
            content.setDocumentText(doc.id, content.transcriptBlob(itemId) ?: "", doc.lang)
        }

        // A video's OCR is the text on its keyframes, each line once however many frames it stays on.
        val frameText = KeyframeText.collapse(content.keyframeTexts(itemId))
        if (frameText != null) {
            content.document(itemId, DocSource.OCR.ordinal)?.let { doc -> content.setDocumentText(doc.id, frameText, doc.lang) }
        }

        val name = item.display_name
        val ocr = content.documentText(itemId, DocSource.OCR.ordinal)?.ifBlank { null } ?: frameText
        val transcript = content.transcriptBlob(itemId)
        val caption = content.documentText(itemId, DocSource.CAPTION.ordinal)
        val place = item.place
        val bucket = item.bucket_name

        // Fuse every signal into one description and ranked key objects, so the profile reads as a
        // description rather than a bag of duplicates.
        val allTags = content.tagsForItem(itemId)
        // Only sources the user searches, with their label rules applied: renamed, merged or hidden.
        val rules = ai.dusty.finderplus.settings.LabelRules.current()
        val searches = ai.dusty.finderplus.settings.LabelSettings::searches
        fun tagsOf(source: TagSource) = if (!searches(source)) emptyList()
            else allTags.filter { it.source == source.ordinal }
                .mapNotNull { ai.dusty.finderplus.settings.LabelRules.apply(it.label, rules) }.distinct()

        // Distinctive words from this item's text, chosen by how rare they are across the corpus.
        // Written as tags so they are searchable and shown as chips, and given to the summarizer so it
        // names what the item is about instead of quoting a prefix of the text.
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
                // USER and LEARNED are merged: both come from the user, directly or through a
                // prototype they taught.
                userLabels = tagsOf(TagSource.USER) + tagsOf(TagSource.LEARNED),
                ocrText = ocr,
                transcript = transcript,
                peopleNames = if (!searches(TagSource.FACE)) emptyList()
                    else db.faceDao().personNamesFor(itemId)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                faceCount = if (searches(TagSource.FACE)) db.faceDao().faceCount(itemId) else 0,
                place = place,
                album = bucket,
                durationMs = item.duration_ms,
                dateTakenMs = item.date_taken,
                width = item.width,
                height = item.height,
                textKeywords = if (searches(TagSource.OCR_KEYWORD)) keywords else emptyList(),
            ),
            commonLabels = stats?.let { snap -> MediaSummarizer.CommonLabels { snap.isCommonLabel(it) } }
                ?: MediaSummarizer.CommonLabels.NONE,
        )

        // Must be read after the keyword write above, or the FTS row and profile get stale keywords.
        // Unconfirmed guesses (suggested, held for review) are not searchable until confirmed.
        val held = setOf(TagSource.SUGGESTED.ordinal, TagSource.OBJECT_REVIEW.ordinal)
        val tags = content.tagsForItem(itemId)
            .filter { it.source !in held && TagSource.entries.getOrNull(it.source)?.let(searches) != false }
            .mapNotNull { ai.dusty.finderplus.settings.LabelRules.apply(it.label, rules) }
            .joinToString(" ").ifEmpty { null }

        // 1. FTS row (weighted columns for ranked keyword search).
        val fts = db.ftsDao()
        fts.deleteRow(itemId)
        val tagsPlusKeyObjects = listOfNotNull(tags, summary.keyObjects.joinToString(" ").ifEmpty { null })
            .joinToString(" ")
        fts.insertRow(itemId, name, tagsPlusKeyObjects, ocr, transcript, place, bucket)
        // The caption sentence also names the background, so it has a keyword table of its own that
        // search consults only for multi-word queries or when nothing else matches.
        caption?.takeIf { it.isNotBlank() }?.let { fts.insertCaption(itemId, it) }

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
