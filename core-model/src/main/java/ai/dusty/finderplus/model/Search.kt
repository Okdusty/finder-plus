package ai.dusty.finderplus.model

/** A parsed query: structured filters extracted by the parser plus the residual free [text]. */
data class SearchQuery(
    val raw: String,
    val kinds: Set<MediaKind> = emptySet(),
    val after: Long? = null,
    val before: Long? = null,
    val place: String? = null,
    val phrases: List<String> = emptyList(),
    val text: String = "",
)

/** One matched fragment inside a result: a snippet and, for A/V, the timestamp of the match. */
data class SearchHit(
    val field: String,
    val snippet: String?,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

data class SearchResult(
    val item: MediaItem,
    val score: Float,
    val confidence: Float,
    val hits: List<SearchHit>,
    val thumbnailUri: String,
    /** The extracted "content" for this result — copied to the clipboard on tap alongside the file URI.
     *  Transcript for A/V, OCR for images, else the consolidated AI-revision profile. */
    val copyText: String? = null,
    /** One humane display line — the profile summary's first sentence, else its top labels.
     *  Derived at materialization (the profile is already read there) so rows never query per-item.
     *  Display-only: never part of what a tap copies. */
    val subtitle: String? = null,
)

/**
 * The consolidated, long searchable text for one media item — the "AI revision": display name +
 * category + AI labels + OCR + transcript + place + album, assembled from every pass. This is the
 * navigable content profile (advanced on-device SEO) and the text copied to the clipboard.
 */
data class MediaProfile(
    val itemId: Long,
    val text: String,
    val updatedAt: Long,
)
