package ai.dusty.finderplus.search

import ai.dusty.finderplus.db.dao.ContentDao
import ai.dusty.finderplus.db.dao.FtsDao
import ai.dusty.finderplus.db.dao.MediaItemDao
import ai.dusty.finderplus.db.dao.MediaProfileDao
import ai.dusty.finderplus.db.toMediaItem
import ai.dusty.finderplus.model.DocSource
import ai.dusty.finderplus.db.vector.VectorStore
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.model.EmbeddingKind
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.SearchHit
import ai.dusty.finderplus.model.SearchQuery
import ai.dusty.finderplus.model.SearchResult
import ai.dusty.finderplus.vision.ClipTextEncoder
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.text.TextRole
import ai.dusty.finderplus.settings.CaptionUse
import ai.dusty.finderplus.settings.EmbeddingSettings
import ai.dusty.finderplus.settings.MeaningUse
import ai.dusty.finderplus.settings.PartialMatch
import ai.dusty.finderplus.settings.SearchSettings
import ai.dusty.finderplus.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Hybrid on-device search. Emits FTS results first, then re-emits fused results once the semantic
 * vector legs resolve. See docs/design/04-SEARCH.md.
 */
interface SearchEngine {
    fun search(raw: String): Flow<List<SearchResult>>

    /**
     * The corrected query when the last search applied a typo fix ("Showing results for …"), else
     * null. Read by the UI after collecting the flow.
     */
    val lastCorrection: String? get() = null

    /** Whether the last search found any keyword or caption match. Read after collecting the flow. */
    val lastHadKeywordMatch: Boolean get() = true

    /** Load the query encoders ahead of the first keystroke. Safe to call repeatedly; cheap once loaded. */
    suspend fun warmUp() {}
}

class DefaultSearchEngine(
    private val ftsDao: FtsDao,
    private val mediaItemDao: MediaItemDao,
    private val contentDao: ContentDao,
    private val profileDao: MediaProfileDao,
    private val vectorStore: VectorStore,
    private val clipText: ClipTextEncoder,
    private val textEmbedder: TextEmbedder,
    private val parser: QueryParser = DefaultQueryParser(),
    private val fuser: RankFuser = DefaultRankFuser(),
    private val speller: QuerySpeller? = null,
    private val voteDao: ai.dusty.finderplus.db.dao.VoteDao? = null,
    private val regionDao: ai.dusty.finderplus.db.dao.ObjectRegionDao? = null,
) : SearchEngine {

    @Volatile
    override var lastCorrection: String? = null
        private set

    @Volatile
    override var lastHadKeywordMatch: Boolean = true
        private set

    /**
     * Called when the pop-up opens: loads the text model and CLIP's query tower in the background so a
     * cold model load does not delay the first keystroke's results.
     */
    override suspend fun warmUp() {
        runCatching { textEmbedder.embed("photo", TextRole.QUERY) }
        runCatching { clipText.encode("photo") }
    }

    override fun search(raw: String): Flow<List<SearchResult>> = flow {
        var q = parser.parse(raw)
        lastCorrection = null
        if (q.text.isBlank() && q.phrases.isEmpty()) {
            emit(emptyList()); return@flow
        }

        // Leg 1: FTS keyword, emitted first. Strict AND is the most precise; relaxed OR runs only when
        // that finds nothing, so a multi-word query degrades to a partial match, not an empty screen.
        suspend fun ladder(
            sq: SearchQuery,
            relaxed: Boolean,
            find: suspend (String) -> List<ai.dusty.finderplus.db.dao.FtsMatch>,
        ): Pair<String, List<ai.dusty.finderplus.db.dao.FtsMatch>>? {
            for (expr in listOfNotNull(buildFtsQuery(sq), if (relaxed) buildRelaxedFtsQuery(sq) else null)) {
                val m = runCatching { find(expr) }.getOrDefault(emptyList())
                if (m.isNotEmpty()) return expr to m
            }
            return null
        }
        suspend fun keyword(sq: SearchQuery) = ladder(sq, relaxed = true) { ftsDao.matches(it) }
        val keywordsOn = Settings[SearchSettings.KEYWORDS]
        var keywordMatch = if (keywordsOn) keyword(q) else null
        // Typo correction against the corpus vocabulary, only when both FTS forms found nothing: a
        // query that matches something is never second-guessed. The corrected query re-runs the same
        // strict-then-relaxed ladder, and the semantic legs below use it too.
        if (keywordsOn && keywordMatch == null && speller != null && Settings[SearchSettings.TYPOS]) {
            speller.correct(q.text)?.let { fix ->
                val cq = parser.parse(fix.text)
                keyword(cq)?.let { m ->
                    q = cq
                    keywordMatch = m
                    lastCorrection = fix.text
                }
            }
        }
        // BM25 over every match, so relevance rather than recency decides which keyword hits lead.
        val ftsRanked = keywordMatch?.second.orEmpty()
            .sortedByDescending { Relevance.bm25(it.stats) }
            .take(Settings[EmbeddingSettings.KEYWORD_LIMIT].toInt())
            .map { it.itemId }
        val ftsHits = keywordMatch?.let { (expr, _) ->
            runCatching { ftsDao.snippets(expr, ftsRanked) }.getOrDefault(emptyList())
        }.orEmpty()
        // Picking history for these terms, applied to both emissions as a bounded boost.
        val votes: Map<Long, Float> = if (!Settings[SearchSettings.HISTORY]) emptyMap() else runCatching {
            // The query as the user typed it, even when a typo fix searched for another.
            voteDao?.votesFor(listOf(Votes.queryKey(raw)))?.associate { it.item_id to it.score }
        }.getOrNull().orEmpty()

        val ftsOrder = Votes.rerank(ftsRanked, votes)
        val snippetByItem = ftsHits.associate {
            it.itemId to (it.transcriptSnippet?.takeIf { s -> s.isNotBlank() } ?: it.ocrSnippet)
        }
        val keywordItems = ftsRanked.toSet()

        // Captions. A caption sentence also names the background, so by default it is searched only for
        // a query of several words, which it can match whole, or when nothing else matched; only in that
        // last case does a partial (OR) match count. BM25 orders the matches as for keywords.
        val searchCaptions = when (Settings[SearchSettings.CAPTIONS]) {
            CaptionUse.SENTENCES -> isMultiWord(q) || keywordMatch == null
            CaptionUse.ALWAYS -> true
            CaptionUse.NOTHING_ELSE -> keywordMatch == null
            CaptionUse.OFF -> false
        }
        val partialCaptions = when (Settings[SearchSettings.CAPTION_PARTIAL]) {
            PartialMatch.WHEN_NOTHING -> keywordMatch == null
            PartialMatch.ALWAYS -> true
            PartialMatch.NEVER -> false
        }
        val captionRanked = if (searchCaptions) {
            ladder(q, relaxed = partialCaptions) { ftsDao.captionMatches(it) }?.second.orEmpty()
                .sortedByDescending { Relevance.bm25(it.stats) }
                .take(Settings[EmbeddingSettings.KEYWORD_LIMIT].toInt())
                .map { it.itemId }
        } else emptyList()
        val captionItems = captionRanked.toSet()
        lastHadKeywordMatch = ftsRanked.isNotEmpty() || captionRanked.isNotEmpty()

        val literal = if (captionRanked.isEmpty()) ftsOrder
            else Votes.rerank(fuser.fuse(listOf(ftsRanked, captionRanked), listOf(weight(SearchSettings.KEYWORD_WEIGHT), weight(SearchSettings.CAPTION_WEIGHT))).map { it.itemId }, votes)
        emit(materialize(literal, emptyMap(), snippetByItem, q, keywordItems, captionItems))

        // Legs 2 and 3: semantic vectors. A zero vector means the model isn't installed yet.
        // CLIP is judged per item with a correction for items with many vectors, and only items that
        // stand out for this query are kept: see Relevance.fairVectorRanking.
        val fair = Settings[SearchSettings.FAIR_RANKING]
        val clipHits = if (!Settings[SearchSettings.VISUAL]) emptyList() else runCatching {
            val v = clipText.encode(q.text)
            if (v.any { it != 0f }) {
                val scores = vectorStore.scoreItems(EmbeddingKind.IMAGE_CLIP, Vecs.normalized(v))
                val floor = Settings.valueOrNull(EmbeddingSettings.VISUAL_FLOOR)?.toFloat()
                val items = if (floor == null) scores.items else scores.items.filter { it.best >= floor }
                val limit = Settings[EmbeddingSettings.VISUAL_LIMIT].toInt()
                if (fair) fairRanking(items, scores.mean, scores.sd, limit)
                else items.sortedByDescending { it.best }.take(limit)
            } else emptyList()
        }.getOrDefault(emptyList())
        // Leg 3: multilingual text embeddings over every item's text, so a query can match across
        // languages with no shared word. The model is task-steered: the query must use the QUERY role,
        // since documents were embedded with the document prompt.
        val meaning = when (Settings[SearchSettings.MEANING]) {
            MeaningUse.ALWAYS -> true
            MeaningUse.FEW_MATCHES -> (ftsRanked.size + captionRanked.size) < Settings[SearchSettings.FEW_MATCHES]
            MeaningUse.OFF -> false
        }
        val txtHits = if (!meaning) emptyList() else runCatching {
            val v = textEmbedder.embed(q.text, TextRole.QUERY)
            if (v.any { it != 0f }) {
                // Same fairness as CLIP: a long recording has a vector per transcript window, so its
                // best window is corrected for how many it has, and only items that stand out are kept.
                // Text vectors are Matryoshka-trained, so ones of another size still compare.
                val scores = vectorStore.scoreItems(EmbeddingKind.TEXT, Vecs.normalized(v), nested = true)
                when {
                    (scores.items.maxOfOrNull { it.best } ?: 0f) < Settings[SearchSettings.MEANING_FLOOR] -> emptyList()
                    fair -> fairRanking(scores.items, scores.mean, scores.sd, Settings[EmbeddingSettings.MEANING_LIMIT].toInt())
                    else -> scores.items.sortedByDescending { it.best }.take(Settings[EmbeddingSettings.MEANING_LIMIT].toInt())
                }
            } else emptyList()
        }.getOrDefault(emptyList())

        val fused = fuser.fuse(
            legs = listOf(ftsOrder, captionRanked, clipHits.map { it.itemId }.distinct(), txtHits.map { it.itemId }.distinct()),
            // By default keyword hits are literal, so they lead; captions, the text leg (what FTS cannot
            // spell) and CLIP (what no text describes) are weighted equally.
            weights = listOf(
                weight(SearchSettings.KEYWORD_WEIGHT), weight(SearchSettings.CAPTION_WEIGHT),
                weight(SearchSettings.VISUAL_WEIGHT), weight(SearchSettings.MEANING_WEIGHT),
            ),
        )
        val maxScore = fused.maxOfOrNull { it.score } ?: 1f
        val confidenceById = fused.associate { it.itemId to (it.score / maxScore).coerceIn(0f, 1f) }
        // Where in each matching video or recording the vector legs found the match; negative is unknown.
        val windowMoments = txtHits.mapNotNull { h -> h.bestTsMs?.takeIf { it >= 0 }?.let { h.itemId to it } }.toMap()
        val frameMoments = clipHits.mapNotNull { h -> h.bestTsMs?.takeIf { it >= 0 }?.let { h.itemId to it } }.toMap()
        emit(materialize(Votes.rerank(fused.map { it.itemId }, votes), confidenceById, snippetByItem, q,
            keywordItems, captionItems, windowMoments, frameMoments))
    }

    private suspend fun materialize(
        order: List<Long>,
        confidenceById: Map<Long, Float>,
        snippetByItem: Map<Long, String?>,
        q: SearchQuery,
        keywordItems: Set<Long>,
        captionItems: Set<Long>,
        windowMoments: Map<Long, Long> = emptyMap(),
        frameMoments: Map<Long, Long> = emptyMap(),
    ): List<SearchResult> {
        val words = q.text.lowercase(java.util.Locale.ROOT).split(WORD_BREAK).filter { it.length >= 2 }
        val shown = order.distinct().take(Settings[SearchSettings.MAX_RESULTS].toInt())
        // Where in each video a name the query matched was seen.
        val labelled = runCatching { regionDao?.momentsFor(shown.filter { it in keywordItems }) }.getOrNull().orEmpty()
            .mapNotNull { r -> r.ts_ms?.takeIf { it >= 0 && namesWord(r.label, words) }?.let { r.item_id to it } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, times) -> times.min() }
        val rules = ai.dusty.finderplus.settings.LabelRules.current()
        val out = ArrayList<SearchResult>(order.size)
        for (id in shown) {
            val entity = mediaItemDao.byId(id) ?: continue
            val item = entity.toMediaItem()
            if (q.kinds.isNotEmpty() && item.kind !in q.kinds) continue

            val hits = ArrayList<SearchHit>(1)
            val snippet = snippetByItem[id]
            val caption = contentDao.document(id, DocSource.CAPTION.ordinal)?.takeIf { it.text.isNotBlank() }
            val isAv = item.kind == MediaKind.VIDEO || item.kind == MediaKind.AUDIO
            if (isAv) {
                // Open at the matching moment: words said or shown on screen first, then the frame where
                // a matching name was seen or the matching caption describes, then the matching
                // transcript window, then the matching keyframe. No evidence means the start.
                val said = if (id in keywordItems) firstSegmentWith(id, words) else null
                val described = labelled[id] ?: caption?.ts_ms?.takeIf { id in captionItems && it >= 0 }
                val window = windowMoments[id]
                val moment = said?.start_ms ?: described ?: window ?: frameMoments[id]
                val text = said?.text ?: window?.let { contentDao.segmentNear(id, it)?.text }
                hits += SearchHit(
                    field = "transcript", snippet = snippet ?: text?.let { ai.dusty.finderplus.model.Highlight.mark(it, words) },
                    startMs = moment, endMs = said?.end_ms,
                )
            } else if (snippet != null) {
                hits += SearchHit(field = "ocr", snippet = snippet)
            }

            // Clipboard text on tap: transcript (A/V) or OCR (image), else the caption and the media
            // profile (docs/design/05-MEDIA-PROFILE.md). The caption is the row's subtitle, else the
            // profile supplies one.
            val profileText = profileDao.text(id)
            val copyText = (if (isAv) contentDao.documentText(id, DocSource.TRANSCRIPT.ordinal)
                else contentDao.documentText(id, DocSource.OCR.ordinal))
                ?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(caption?.text, profileText).joinToString("\n").ifBlank { null }

            val confidence = confidenceById[id] ?: 0.5f
            out += SearchResult(
                item = item,
                score = confidence,
                confidence = confidence,
                hits = hits,
                thumbnailUri = item.uri,
                copyText = copyText,
                subtitle = caption?.text?.trim()?.trimEnd('.') ?: subtitleFrom(profileText),
                keywords = keywordsFor(id, words, rules),
            )
        }
        return out
    }

    /**
     * A few of the item's own names, simplest first-glance description of it: the ones the query
     * matched first and marked, then the most confident, after the label rules and source settings.
     */
    private suspend fun keywordsFor(itemId: Long, words: List<String>, rules: Map<String, String?>): List<String> {
        val names = contentDao.tagsForItem(itemId)
            .filter { t ->
                val source = ai.dusty.finderplus.model.TagSource.entries.getOrNull(t.source)
                source in NAME_SOURCES && ai.dusty.finderplus.settings.LabelSettings.searches(source!!)
            }
            .sortedByDescending { it.confidence }
            .mapNotNull { ai.dusty.finderplus.settings.LabelRules.apply(it.label, rules) }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        val (matched, rest) = names.partition { namesWord(it, words) }
        return (matched.map { ai.dusty.finderplus.model.Highlight.mark(it, words) } + rest).take(KEYWORDS_SHOWN)
    }

    /** Whether a word of [label] starts with one of the query's [words], as a keyword prefix match does. */
    private fun namesWord(label: String, words: List<String>): Boolean =
        label.lowercase(java.util.Locale.ROOT).split(WORD_BREAK).any { t -> words.any { t.startsWith(it) } }

    /** The earliest segment (spoken, or text on a keyframe) containing one of [words]. */
    private suspend fun firstSegmentWith(itemId: Long, words: List<String>): ai.dusty.finderplus.db.entity.SegmentEntity? {
        if (words.isEmpty()) return null
        return contentDao.segmentsForItem(itemId).sortedBy { it.start_ms }.firstOrNull { seg ->
            val t = seg.text.lowercase(java.util.Locale.ROOT)
            words.any { it in t }
        }
    }

    private companion object {
        /** Splits query text into words in any script. */
        val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

        /** Names that say what an item shows: from the models, and the user's own. */
        val NAME_SOURCES = setOf(
            ai.dusty.finderplus.model.TagSource.OBJECT, ai.dusty.finderplus.model.TagSource.USER,
            ai.dusty.finderplus.model.TagSource.LEARNED, ai.dusty.finderplus.model.TagSource.CONCEPT,
        )

        const val KEYWORDS_SHOWN = 4



        fun weight(s: ai.dusty.finderplus.settings.Amount): Float = Settings[s].toFloat()

        fun fairRanking(items: List<ai.dusty.finderplus.db.vector.ItemScore>, mean: Float, sd: Float, limit: Int) =
            Relevance.fairVectorRanking(
                items, mean, sd, limit,
                standout = Settings[EmbeddingSettings.STANDOUT],
                liftScale = Settings[EmbeddingSettings.MANY_VECTOR_CORRECTION],
            )


        const val SUMMARY_PREFIX = "Summary: "
        const val TAGS_PREFIX = "Tags: "

        /**
         * A one-line subtitle from the profile: the summary's first sentence, else the first few tags.
         * Display only; [SearchResult.copyText] is unaffected.
         */
        fun subtitleFrom(profile: String?): String? {
            if (profile.isNullOrBlank()) return null
            var tagsLine: String? = null
            for (line in profile.lineSequence()) {
                if (line.startsWith(SUMMARY_PREFIX)) {
                    val first = line.removePrefix(SUMMARY_PREFIX).substringBefore(". ").trim().trimEnd('.')
                    if (first.isNotEmpty()) return first
                }
                if (tagsLine == null && line.startsWith(TAGS_PREFIX)) {
                    tagsLine = line.removePrefix(TAGS_PREFIX)
                }
            }
            return tagsLine?.split(' ')?.filter { it.isNotBlank() }?.take(4)
                ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }
}
