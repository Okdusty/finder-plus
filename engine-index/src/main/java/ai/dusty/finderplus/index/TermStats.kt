package ai.dusty.finderplus.index

import androidx.room.withTransaction
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.TermDfEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/** Which vocabulary a document-frequency count belongs to. Persisted — do not renumber. */
object TermScope {
    /** Words tokenized out of OCR and transcript text. */
    const val TEXT = 0

    /** Whole tag labels, counted as single terms. */
    const val LABEL = 1
}

/**
 * Corpus statistics that decide which terms are worth keeping — **without knowing any language**.
 *
 * ### Why frequency rather than a word list
 *
 * The pipeline used to filter with hard-coded sets: a `STOP_LABELS` list of English label names, and
 * nothing at all for OCR tokens. Both were wrong in the same way. A list only covers the languages
 * someone thought to write down, and galleries often mix languages, even inside a single screenshot.
 *
 * Document frequency needs no such knowledge, because a term that appears everywhere cannot distinguish
 * anything — that is what makes it useless, and it is measurable. In a mixed-language corpus the
 * function words of every language rise to the top by DF together, so one threshold handles all of
 * them without anyone configuring a list.
 *
 * ### Ceiling, then ranking
 *
 * Two mechanisms, because they answer different questions. [DF_CEILING] discards terms that are
 * *structurally* uninformative. TF-IDF then ranks what remains, so a term that survives the ceiling but
 * is still common ranks below a rare one in the same document. Ranking alone would be too permissive on
 * short OCR text where a stopword may be all there is; a ceiling alone would keep the long tail unordered.
 */
@Singleton
class TermStats @Inject constructor(private val db: FinderDatabase) {

    /** Immutable snapshot of the corpus, cheap to pass around during an artifact rebuild. */
    class Snapshot(
        private val textDf: Map<String, Int>,
        private val labelDf: Map<String, Int>,
        val documents: Int,
        val labelledItems: Int,
    ) {
        /**
         * Whether a term is common enough to be worthless as a search term.
         *
         * Below [MIN_CORPUS] the answer is always false. A fraction computed from a handful of documents
         * is noise — with three documents, every term in one of them sits at 33% — and suppressing terms
         * on that basis would strip a new install's index of exactly the words it does have.
         */
        fun isCommonText(term: String): Boolean =
            documents >= MIN_CORPUS && (textDf[term] ?: 0).toFloat() / documents > DF_CEILING

        fun isCommonLabel(label: String): Boolean =
            labelledItems >= MIN_CORPUS && (labelDf[label.lowercase()] ?: 0).toFloat() / labelledItems > LABEL_DF_CEILING

        /** Inverse document frequency; higher means rarer, hence more discriminating. */
        fun idf(term: String): Double {
            val df = (textDf[term] ?: 0).coerceAtLeast(1)
            return ln((documents.coerceAtLeast(1) + 1.0) / df)
        }

        /**
         * The most distinctive terms in [text], best first.
         *
         * Scored `(1 + ln tf) * idf` — sublinear in term frequency, because a word repeated twenty times
         * in one screenshot is not twenty times more relevant, and linear weighting lets a single
         * repeated word crowd out everything else.
         */
        fun keywordsFor(text: String?, limit: Int = KEYWORDS_PER_ITEM): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            val tf = HashMap<String, Int>()
            for (t in tokenize(text)) tf[t] = (tf[t] ?: 0) + 1
            if (tf.isEmpty()) return emptyList()
            return tf.entries
                .filterNot { isCommonText(it.key) }
                .filterNot { isTooRare(it.key) }
                .map { (term, count) -> term to (1.0 + ln(count.toDouble())) * idf(term) }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .take(limit)
                .map { it.first }
        }

        /**
         * Whether a term occurs in too few documents to be trusted — the floor opposite [isCommonText]'s
         * ceiling.
         *
         * A term absent from the table is **kept**, not dropped. During indexing new OCR documents appear
         * after the last count, so "not counted yet" is not evidence of rarity; the next scan recounts and
         * settles it. Failing open here matters because failing closed would silently strip every keyword
         * from whatever was indexed most recently.
         */
        fun isTooRare(term: String): Boolean {
            if (MIN_DOC_FREQUENCY <= 1) return false
            val df = textDf[term] ?: return false
            return df < MIN_DOC_FREQUENCY
        }
    }

    @Volatile private var cache: Snapshot? = null

    /**
     * The current counts, loaded once and reused.
     *
     * Cached because a bulk profile rebuild calls this once per item, and reloading the whole table per
     * item would make that pass quadratic. [rebuild] invalidates it.
     */
    suspend fun cached(): Snapshot = cache ?: snapshot().also { cache = it }

    /** Load the current counts. */
    suspend fun snapshot(): Snapshot {
        val dao = db.termDfDao()
        return Snapshot(
            textDf = dao.forScope(TermScope.TEXT).associate { it.term to it.doc_count },
            labelDf = dao.forScope(TermScope.LABEL).associate { it.term to it.doc_count },
            documents = dao.corpusSize(TermScope.TEXT),
            labelledItems = dao.corpusSize(TermScope.LABEL),
        )
    }

    /**
     * Recount the corpus from committed rows.
     *
     * Pure arithmetic over text already stored, so this is affordable to redo on every scan rather than
     * maintained incrementally — and redoing it is what keeps the counts honest as the gallery grows. An
     * incremental counter would drift the moment an item were deleted or re-OCR'd.
     *
     * @return how many distinct terms were recorded.
     */
    suspend fun rebuild(): Int {
        val dao = db.termDfDao()

        val textDf = HashMap<String, Int>()
        var documents = 0
        for (text in db.contentDao().allDocumentTexts()) {
            val terms = tokenize(text).toHashSet()
            if (terms.isEmpty()) continue
            documents++
            // Document frequency, not term frequency: each term counts once per document however often
            // it occurs, which is what makes the ratio comparable across documents of different lengths.
            for (t in terms) textDf[t] = (textDf[t] ?: 0) + 1
        }

        val labelDf = HashMap<String, Int>()
        val itemsWithLabels = HashSet<Long>()
        for (row in db.contentDao().allLabelPairs()) {
            itemsWithLabels += row.item_id
            labelDf[row.label.lowercase()] = (labelDf[row.label.lowercase()] ?: 0) + 1
        }

        db.withTransaction {
            dao.clear()
            dao.insert(textDf.map { TermDfEntity(term = it.key, scope = TermScope.TEXT, doc_count = it.value) })
            dao.insert(labelDf.map { TermDfEntity(term = it.key, scope = TermScope.LABEL, doc_count = it.value) })
            dao.setCorpusSize(TermScope.TEXT, documents)
            dao.setCorpusSize(TermScope.LABEL, itemsWithLabels.size)
        }
        cache = null
        return textDf.size + labelDf.size
    }

    companion object {
        /**
         * Share of documents above which a text term is discarded.
         *
         * Chosen from the document-frequency distribution rather than convention. Terms in 2–5% of
         * documents are typically function words and interface chrome in every language present, while
         * the 1–2% band still carries real content such as names and ordinary nouns. The boundary
         * between those two bands is where the ceiling belongs; anywhere lower discards meaning.
         */
        const val DF_CEILING = 0.02f

        /**
         * Same idea for whole labels, set higher because the label vocabulary is deliberately small.
         *
         * A concept the gallery genuinely contains often — `screenshot` on a phone full of screenshots —
         * is legitimately on a large share of items, so the ceiling only has to catch labels approaching
         * *universal*, which describe the medium rather than the content.
         */
        const val LABEL_DF_CEILING = 0.35f

        /** Documents required before any frequency ratio is trusted. */
        const val MIN_CORPUS = 50

        /**
         * Documents a term must appear in to become a keyword. **1 disables the floor.**
         *
         * The idea was to strip OCR garbage: a misread occurs once, a real word recurs. In practice that
         * reasoning does not hold, so the floor ships disabled rather than enabled-and-harmful. Raising it
         * to 2 drops a large share of keyword tags and leaves many items with no keyword at all, and what
         * it removes is mostly *content*, while plenty of garbage survives anyway.
         *
         * The floor is close to orthogonal to the thing it was meant to catch. OCR misreads the *same*
         * recurring interface the same way, so garbage repeats across documents, while a genuine word
         * often appears in exactly one photo — and a term unique to one item is the most precise search
         * term there is, not the least.
         *
         * Set to 2 to enable; nothing else has to change. The cost is smaller than the dropped-tag count
         * suggests, because a dropped keyword is still findable — the full recognized text remains indexed
         * in its own FTS column. The floor only governs promotion to a tag, chip and summary term.
         *
         * Targeting garbage properly needs a signal that measures *implausibility* rather than rarity —
         * character-trigram probability learned from the corpus itself would stay language-agnostic and
         * would separate a misread from a rare real word, which document frequency provably cannot.
         */
        const val MIN_DOC_FREQUENCY = 1

        /** Keywords kept per item. Enough to characterize it, few enough to stay a summary. */
        const val KEYWORDS_PER_ITEM = 10

        /**
         * Word characters only, at least two long, lowercased.
         *
         * `\p{L}` rather than `\w`: `\w` is ASCII-oriented in effect and would split Turkish `ı ş ğ ü ö ç`
         * mid-word, turning `için` into `i` + `in`. Digits are excluded because bare numbers out of
         * context — timestamps, prices, message counts — match everything and mean nothing.
         */
        private val TOKEN = Regex("""\p{L}{2,}""")

        fun tokenize(text: String): List<String> =
            TOKEN.findAll(text).map { it.value.lowercase() }.toList()
    }
}
