package ai.rightone.finderplus.search

import ai.rightone.finderplus.db.dao.TermDfDao

/**
 * Typo correction whose dictionary is the gallery itself.
 *
 * A web engine corrects against the whole language; the right reference here is narrower and better —
 * the `term_df` table, every word that actually appears in this gallery's OCR and transcripts, with
 * frequencies. That choice is what keeps correction **language-agnostic**: nothing here knows Turkish
 * or English, yet `gızli` finds `gizli` because the corpus contains it, and a word the corpus has
 * never seen is not offered no matter how English it looks.
 *
 * Two matching stages, both against the corpus vocabulary:
 *
 *  1. **Diacritic folding** — `ı→i, ş→s, ç→c, ğ→g, ö→o, ü→u`. The dominant real typo class on a
 *     Turkish/English keyboard is not a wrong key but a *missing accent*: typing `dovdum` for
 *     `dövdüm`, or an English keyboard having no `ı` at all. Folding makes those exact matches.
 *  2. **Edit distance 1 on the folded forms** — one insertion, deletion or substitution, covering
 *     the classic adjacent-key slip. Distance 2 is deliberately not offered: on a vocabulary of
 *     ~40k short terms it manufactures collisions faster than it fixes mistakes.
 *
 * Candidates are ranked by document frequency — when `cra` could be `car` (60 documents) or `cra`
 * (1 document), the common word wins — mirroring how the profile keywords are already chosen.
 *
 * Correction is only *offered* for tokens the corpus does not contain at all. A known word is never
 * "fixed", however rare: rare terms are the most precise queries there are.
 */
class QuerySpeller(private val termDfDao: TermDfDao) {

    /** A corrected query, with the substitutions that produced it. */
    data class Corrected(val text: String, val changes: Map<String, String>)

    private var vocab: Map<String, Int> = emptyMap()
    private var folded: Map<String, List<String>> = emptyMap()
    private var loadedAt = 0L

    /** Correct unknown tokens in [text], or null when every token is already a corpus word. */
    suspend fun correct(text: String): Corrected? {
        ensureVocab()
        if (vocab.isEmpty()) return null

        val changes = LinkedHashMap<String, String>()
        val out = text.split(Regex("\\s+")).map { word ->
            val token = word.lowercase()
            if (token.length < MIN_LEN || vocab.containsKey(token)) return@map word
            val fixed = bestCandidate(token) ?: return@map word
            changes[word] = fixed
            fixed
        }
        if (changes.isEmpty()) return null
        return Corrected(out.joinToString(" "), changes)
    }

    private fun bestCandidate(token: String): String? {
        val f = fold(token)
        // Stage 1: same folded form — a diacritic-only difference, the highest-confidence fix.
        folded[f]?.let { hits ->
            return hits.maxByOrNull { vocab[it] ?: 0 }
        }
        // Stage 2: one edit away in folded space, most frequent wins.
        var best: String? = null
        var bestDf = 0
        for ((vf, terms) in folded) {
            if (kotlin.math.abs(vf.length - f.length) > 1) continue
            if (!withinOneEdit(f, vf)) continue
            for (t in terms) {
                val df = vocab[t] ?: 0
                if (df > bestDf) { bestDf = df; best = t }
            }
        }
        // A one-document match is as likely to be OCR garbage as a word; require modest support.
        return if (bestDf >= MIN_CANDIDATE_DF) best else null
    }

    @Synchronized
    private fun invalidateIfStale() {
        if (System.currentTimeMillis() - loadedAt > CACHE_MS) vocab = emptyMap()
    }

    private suspend fun ensureVocab() {
        invalidateIfStale()
        if (vocab.isNotEmpty()) return
        val rows = runCatching { termDfDao.forScope(SCOPE_TEXT) }.getOrDefault(emptyList())
        val v = HashMap<String, Int>(rows.size)
        val fm = HashMap<String, MutableList<String>>(rows.size)
        for (r in rows) {
            v[r.term] = r.doc_count
            fm.getOrPut(fold(r.term)) { ArrayList(1) }.add(r.term)
        }
        synchronized(this) { vocab = v; folded = fm; loadedAt = System.currentTimeMillis() }
    }

    companion object {
        private const val SCOPE_TEXT = 0
        private const val MIN_LEN = 3
        private const val MIN_CANDIDATE_DF = 2
        private const val CACHE_MS = 5 * 60_000L

        private val FOLD = mapOf(
            'ı' to 'i', 'İ' to 'i', 'ş' to 's', 'ç' to 'c', 'ğ' to 'g', 'ö' to 'o', 'ü' to 'u',
            'â' to 'a', 'î' to 'i', 'û' to 'u',
        )

        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            // Per-char, FOLD first: "İ".lowercase() as a *string* yields i + a combining dot (two
            // characters), which would survive folding and silently never match anything. Mapping the
            // uppercase form directly, then Char.lowercaseChar() (which must return one char), avoids
            // the entire class of multi-char case mappings.
            for (c in s) sb.append(FOLD[c] ?: FOLD[c.lowercaseChar()] ?: c.lowercaseChar())
            return sb.toString()
        }

        /** Damerau-lite: true when [a] and [b] differ by at most one insert/delete/substitute. */
        fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            val (s, l) = if (a.length <= b.length) a to b else b to a
            if (l.length - s.length > 1) return false
            if (s.length == l.length) {
                var diff = 0
                for (i in s.indices) if (s[i] != l[i] && ++diff > 1) return false
                return true
            }
            var i = 0; var j = 0; var skipped = false
            while (i < s.length && j < l.length) {
                if (s[i] == l[j]) { i++; j++ }
                else if (!skipped) { skipped = true; j++ }
                else return false
            }
            return true
        }
    }
}
