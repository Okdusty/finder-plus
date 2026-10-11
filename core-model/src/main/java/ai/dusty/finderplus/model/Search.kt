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
    /** A few of the item's names, those the query matched first and marked with [Highlight]. Display-only. */
    val keywords: List<String> = emptyList(),
)

/**
 * Marks the parts of display text that a query matched, so a screen can colour them. The marks are
 * private-use characters, which no indexed text contains, so text with brackets of its own is safe.
 */
object Highlight {
    const val START = '\uE000'
    const val END = '\uE001'

    fun strip(s: String): String = s.filterNot { it == START || it == END }

    /**
     * [text] with each word that starts with one of [words] marked, as a keyword search matches by
     * prefix. Text already marked is returned as it is.
     */
    fun mark(text: String, words: List<String>): String {
        if (words.isEmpty() || START in text) return text
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetterOrDigit()) { out.append(text[i]); i++; continue }
            var j = i
            while (j < text.length && text[j].isLetterOrDigit()) j++
            val word = text.substring(i, j)
            val lower = word.lowercase()
            if (words.any { lower.startsWith(it) }) out.append(START).append(word).append(END) else out.append(word)
            i = j
        }
        return out.toString()
    }
}

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
