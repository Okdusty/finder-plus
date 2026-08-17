package ai.dusty.finderplus.search

import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.SearchQuery

interface QueryParser {
    fun parse(raw: String): SearchQuery
}

/**
 * Extracts structured filters (type:, quoted phrases, year) and leaves the residual free text.
 * Full relative-date resolution ("last summer") is a hook left for the app layer where a clock is
 * available. See docs/design/04-SEARCH.md §2.
 */
class DefaultQueryParser : QueryParser {

    private val typeRegex = Regex("""type:(\w+)""", RegexOption.IGNORE_CASE)
    private val phraseRegex = Regex("\"([^\"]+)\"")

    override fun parse(raw: String): SearchQuery {
        var text = raw.trim()

        val kinds = mutableSetOf<MediaKind>()
        typeRegex.findAll(text).forEach {
            when (it.groupValues[1].lowercase()) {
                "video", "videos", "clip", "clips" -> kinds += MediaKind.VIDEO
                "photo", "photos", "image", "images", "pic", "pics" -> kinds += MediaKind.IMAGE
                "audio", "sound", "voice", "recording" -> kinds += MediaKind.AUDIO
            }
        }
        text = typeRegex.replace(text, "").trim()

        val phrases = phraseRegex.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        text = phraseRegex.replace(text, "").trim()

        return SearchQuery(
            raw = raw,
            kinds = kinds,
            after = null,
            before = null,
            place = null,
            phrases = phrases,
            text = text.replace(Regex("\\s+"), " ").trim(),
        )
    }
}

/** Build an FTS MATCH expression: prefix-expanded tokens (implicit AND) plus required quoted phrases. */
internal fun buildFtsQuery(q: SearchQuery): String {
    val tokens = ftsTokens(q)
    val phraseParts = q.phrases.map { "\"${it.replace("\"", "")}\"" }
    return (tokens + phraseParts).joinToString(" ")
}

/**
 * A relaxed variant where the tokens are OR-ed.
 *
 * FTS joins bare terms with AND, so a query of four words matches only items containing **all** of
 * them — and a multi-word natural query ("red car at the beach") then returns nothing at all, which
 * reads as a broken search rather than a strict one. Falling back to OR turns that into a ranked
 * partial match, which is what the fusion step is for.
 */
internal fun buildRelaxedFtsQuery(q: SearchQuery): String? {
    val tokens = ftsTokens(q)
    if (tokens.size < 2) return null
    return tokens.joinToString(" OR ")
}

private fun ftsTokens(q: SearchQuery): List<String> =
    q.text.split(Regex("\\W+"))
        .filter { it.length >= 2 && it.lowercase() !in FTS_STOP_WORDS }
        .map { "${it.lowercase()}*" }

/**
 * Dropped from keyword matching. They appear in nearly every generated profile sentence ("Taken in…",
 * "Shows…"), so as prefix terms they match almost everything while carrying no intent.
 */
private val FTS_STOP_WORDS = setOf(
    "the", "a", "an", "of", "in", "on", "at", "to", "for", "with", "and", "or", "is", "it", "my",
)
