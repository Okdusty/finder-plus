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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Hybrid on-device search. Emits FTS results first (paint immediately), then re-emits fused results
 * once the semantic vector legs resolve — matching the as-you-type pop-up. See docs/design/04-SEARCH.md.
 */
interface SearchEngine {
    fun search(raw: String): Flow<List<SearchResult>>

    /**
     * "Showing results for …" — set when the last search corrected a typo, cleared otherwise. Read by
     * the UI after collecting; a flow field would force every consumer to zip two streams for what is
     * an annotation on the query, not a result.
     */
    val lastCorrection: String? get() = null
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
) : SearchEngine {

    @Volatile
    override var lastCorrection: String? = null
        private set

    override fun search(raw: String): Flow<List<SearchResult>> = flow {
        var q = parser.parse(raw)
        lastCorrection = null
        if (q.text.isBlank() && q.phrases.isEmpty()) {
            emit(emptyList()); return@flow
        }

        // Leg 1 — FTS keyword (fast): paint first. Strict AND first because when it matches it is the
        // most precise leg; relaxed OR only when that returns nothing, so a natural multi-word query
        // degrades to a partial match instead of an empty screen.
        var ftsHits = runCatching { ftsDao.search(buildFtsQuery(q), 200) }.getOrDefault(emptyList())
        if (ftsHits.isEmpty()) {
            buildRelaxedFtsQuery(q)?.let { relaxed ->
                ftsHits = runCatching { ftsDao.search(relaxed, 200) }.getOrDefault(emptyList())
            }
        }
        // Leg 1.5 — typo correction against the gallery's own vocabulary, only after both exact
        // forms found nothing. A query that matches something is never second-guessed; one that
        // matches nothing gets the web-engine treatment ("gızli" → "gizli") with the corrected
        // query re-run through the same strict-then-relaxed ladder. The semantic legs below use the
        // corrected text too — CLIP has never seen "gızli" either.
        if (ftsHits.isEmpty() && speller != null) {
            speller.correct(q.text)?.let { fix ->
                val cq = parser.parse(fix.text)
                var hits = runCatching { ftsDao.search(buildFtsQuery(cq), 200) }.getOrDefault(emptyList())
                if (hits.isEmpty()) {
                    buildRelaxedFtsQuery(cq)?.let { r -> hits = runCatching { ftsDao.search(r, 200) }.getOrDefault(emptyList()) }
                }
                if (hits.isNotEmpty()) {
                    q = cq
                    ftsHits = hits
                    lastCorrection = fix.text
                }
            }
        }
        // What picking history says about these terms — folded into both paints as a bounded boost.
        val votes: Map<Long, Float> = runCatching {
            voteDao?.votesFor(Votes.terms(q.text))?.associate { it.item_id to it.score }
        }.getOrNull().orEmpty()

        val ftsOrder = Votes.rerank(ftsHits.map { it.itemId }, votes)
        val snippetByItem = ftsHits.associate {
            it.itemId to (it.transcriptSnippet?.takeIf { s -> s.isNotBlank() } ?: it.ocrSnippet)
        }
        emit(materialize(ftsOrder, emptyMap(), snippetByItem, q))

        // Legs 2 & 3 — semantic vectors (stream in). Zero vectors mean the model isn't installed yet.
        val clipHits = runCatching {
            val v = clipText.encode(q.text)
            if (v.any { it != 0f }) vectorStore.search(EmbeddingKind.IMAGE_CLIP, Vecs.normalized(v), 100) else emptyList()
        }.getOrDefault(emptyList())
        val txtHits = runCatching {
            val v = textEmbedder.embed(q.text)
            if (v.any { it != 0f }) vectorStore.search(EmbeddingKind.TEXT_TRANSCRIPT, Vecs.normalized(v), 100) else emptyList()
        }.getOrDefault(emptyList())

        val fused = fuser.fuse(
            legs = listOf(ftsOrder, clipHits.map { it.itemId }.distinct(), txtHits.map { it.itemId }.distinct()),
            // Keyword hits are literal, so they lead. Visual similarity is weighted above transcript
            // similarity because the CLIP leg is the one that finds things no tag describes, whereas the
            // transcript leg largely re-finds what FTS already matched on the same words.
            weights = listOf(FTS_WEIGHT, CLIP_WEIGHT, TEXT_WEIGHT),
        )
        val maxScore = fused.maxOfOrNull { it.score } ?: 1f
        val confidenceById = fused.associate { it.itemId to (it.score / maxScore).coerceIn(0f, 1f) }
        emit(materialize(Votes.rerank(fused.map { it.itemId }, votes), confidenceById, snippetByItem, q))
    }

    private suspend fun materialize(
        order: List<Long>,
        confidenceById: Map<Long, Float>,
        snippetByItem: Map<Long, String?>,
        q: SearchQuery,
    ): List<SearchResult> {
        val out = ArrayList<SearchResult>(order.size)
        for (id in order.distinct().take(60)) {
            val entity = mediaItemDao.byId(id) ?: continue
            val item = entity.toMediaItem()
            if (q.kinds.isNotEmpty() && item.kind !in q.kinds) continue

            val hits = ArrayList<SearchHit>(1)
            val snippet = snippetByItem[id]
            val isAv = item.kind == MediaKind.VIDEO || item.kind == MediaKind.AUDIO
            if (isAv) {
                // Anchor A/V hits to a timestamp so the result can seek. Approximate via nearest segment.
                val seg = contentDao.segmentNear(id, aroundMs = 0)
                hits += SearchHit(field = "transcript", snippet = snippet ?: seg?.text, startMs = seg?.start_ms, endMs = seg?.end_ms)
            } else if (snippet != null) {
                hits += SearchHit(field = "ocr", snippet = snippet)
            }

            // Content to place on the clipboard on tap: transcript (A/V) or OCR (image), else the
            // consolidated AI-revision profile. See docs/design/05-MEDIA-PROFILE.md.
            // The profile is read unconditionally: besides being the copy fallback it is where the
            // row's display subtitle comes from, so the UI never has to query per item.
            val profileText = profileDao.text(id)
            val copyText = (if (isAv) contentDao.documentText(id, DocSource.TRANSCRIPT.ordinal)
                else contentDao.documentText(id, DocSource.OCR.ordinal))
                ?.takeIf { it.isNotBlank() }
                ?: profileText

            val confidence = confidenceById[id] ?: 0.5f
            out += SearchResult(
                item = item,
                score = confidence,
                confidence = confidence,
                hits = hits,
                thumbnailUri = item.uri,
                copyText = copyText,
                subtitle = subtitleFrom(profileText),
            )
        }
        return out
    }

    private companion object {
        const val FTS_WEIGHT = 1.6f
        const val CLIP_WEIGHT = 1.0f
        const val TEXT_WEIGHT = 0.7f

        const val SUMMARY_PREFIX = "Summary: "
        const val TAGS_PREFIX = "Tags: "

        /**
         * One humane display line from the profile already in hand: the summary's first sentence
         * ("Labelled birthday party"), else the first few tags. Cheap string slicing, no extra I/O.
         * Display-only — [SearchResult.copyText] is untouched by this.
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
