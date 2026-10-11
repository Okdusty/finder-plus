package ai.dusty.finderplus.text

import java.io.File
import java.io.Reader

/**
 * Hugging Face `tokenizer.json` BPE in pure Kotlin: the SentencePiece-style files Gemma ships (byte
 * fallback) and the GPT-2-style byte-level files BART-family models ship.
 *
 * A model needs the exact token ids it was trained on: a tokenizer that is merely close gives outputs
 * that are merely close, with no error. So this is a direct port of `tokenizers`' BPE
 * (`Word::merge_all`) with the same min-rank-then-leftmost merge order and stale-entry check, checked
 * token-for-token against the Python reference.
 *
 * Only the pieces these families use are implemented (Replace/Prepend normalizers, a literal Split or a
 * ByteLevel pre-tokenizer, Template or Roberta post-processing). Anything else throws at load time
 * rather than being tokenized approximately.
 */
class HfBpeTokenizer private constructor(
    private val vocab: HashMap<String, Int>,
    private val merges: MergeMap,
    private val added: Map<String, Int>,
    private val normalizers: List<Normalizer>,
    private val split: SplitRule?,
    private val prefix: IntArray,
    private val suffix: IntArray,
    private val byteIds: IntArray,
    private val unkId: Int,
    private val fuseUnk: Boolean,
    private val ignoreMerges: Boolean,
    /** Null unless the pre-tokenizer is ByteLevel; then whether a space is prepended to the text. */
    private val byteLevelPrefixSpace: Boolean?,
    /** Added tokens that absorb whitespace on their left / right, as `lstrip` / `rstrip` declare. */
    private val lstrip: Set<Int>,
    private val rstrip: Set<Int>,
) {

    val vocabSize: Int get() = vocab.size

    /** Written to a temp file and renamed, so a half-written cache is never read. */
    internal fun saveCompact(cache: File, sourceLength: Long, sourceModified: Long) {
        val tmp = File(cache.parentFile, cache.name + ".tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(CACHE_MAGIC); o.writeLong(sourceLength); o.writeLong(sourceModified)
            o.writeInt(normalizers.size)
            for (n in normalizers) when (n) {
                is Normalizer.Replace -> { o.writeByte(0); o.writeUTF(n.pattern); o.writeUTF(n.content) }
                is Normalizer.Prepend -> { o.writeByte(1); o.writeUTF(n.prefix) }
            }
            o.writeBoolean(split != null)
            split?.let { o.writeUTF(it.pattern); o.writeUTF(it.behavior) }
            o.writeInt(prefix.size); prefix.forEach(o::writeInt)
            o.writeInt(suffix.size); suffix.forEach(o::writeInt)
            byteIds.forEach(o::writeInt)
            o.writeInt(unkId); o.writeBoolean(fuseUnk); o.writeBoolean(ignoreMerges)
            o.writeByte(when (byteLevelPrefixSpace) { null -> 0; false -> 1; true -> 2 })
            o.writeInt(lstrip.size); lstrip.forEach(o::writeInt)
            o.writeInt(rstrip.size); rstrip.forEach(o::writeInt)
            o.writeInt(added.size); for ((k, v) in added) { o.writeUTF(k); o.writeInt(v) }
            o.writeInt(vocab.size); for ((k, v) in vocab) { o.writeUTF(k); o.writeInt(v) }
            o.writeInt(merges.countEntries())
            merges.forEach { a, b, r, out -> o.writeInt(a); o.writeInt(b); o.writeInt(r); o.writeInt(out) }
            o.writeInt(CACHE_MAGIC)
        }
        if (!tmp.renameTo(cache)) { tmp.delete(); throw java.io.IOException("could not save tokenizer cache") }
    }

    /** Added (special) tokens bucketed by first char, longest first, for leftmost-longest matching. */
    private val addedByFirst: Map<Char, List<Pair<String, Int>>> = added.entries
        .groupBy({ it.key[0] }, { it.key to it.value })
        .mapValues { (_, v) -> v.sortedByDescending { it.first.length } }

    /**
     * Token ids for [text], wrapped in the post-processor template (`<bos> … <eos>` for Gemma) and
     * truncated so the whole sequence fits [maxLength].
     */
    fun encode(text: String, maxLength: Int = Int.MAX_VALUE): IntArray {
        val body = IntList(text.length / 3 + 8)
        for ((segment, addedId) in splitAdded(text)) {
            if (addedId >= 0) { body.add(addedId); continue }
            var s = segment
            for (n in normalizers) s = n.apply(s)
            val pieces = when {
                byteLevelPrefixSpace != null -> ByteLevel.split(if (byteLevelPrefixSpace && !s.startsWith(' ')) " $s" else s)
                split != null -> split.split(s)
                else -> listOf(s)
            }
            for (piece in pieces) if (piece.isNotEmpty()) bpe(piece, body)
        }
        val room = (maxLength - prefix.size - suffix.size).coerceAtLeast(0)
        val n = minOf(body.size, room)
        val out = IntArray(prefix.size + n + suffix.size)
        prefix.copyInto(out, 0)
        System.arraycopy(body.data, 0, out, prefix.size, n)
        suffix.copyInto(out, prefix.size + n)
        return out
    }

    /**
     * Splits out special tokens verbatim, leftmost-longest, as `AddedVocabulary` does. A token marked
     * `lstrip`/`rstrip` also takes the whitespace beside it.
     */
    private fun splitAdded(text: String): List<Pair<String, Int>> {
        if (addedByFirst.isEmpty()) return listOf(text to -1)
        val out = ArrayList<Pair<String, Int>>(2)
        var start = 0
        var i = 0
        while (i < text.length) {
            val match = addedByFirst[text[i]]?.firstOrNull { text.startsWith(it.first, i) }
            if (match == null) { i++; continue }
            var end = i
            if (match.second in lstrip) while (end > start && text[end - 1].isWhitespace()) end--
            if (end > start) out += text.substring(start, end) to -1
            out += match.first to match.second
            i += match.first.length
            if (match.second in rstrip) while (i < text.length && text[i].isWhitespace()) i++
            start = i
        }
        if (start < text.length) out += text.substring(start) to -1
        return out
    }

    private val tokenById: Array<String?> by lazy {
        val max = maxOf(vocab.values.maxOrNull() ?: 0, added.values.maxOrNull() ?: 0)
        arrayOfNulls<String>(max + 1).also { a -> vocab.forEach { (k, v) -> a[v] = k }; added.forEach { (k, v) -> a[v] = k } }
    }

    private val addedIds: Set<Int> by lazy { added.values.toHashSet() }

    /** The vocabulary entry or added token for [id], as written in `tokenizer.json`. */
    fun tokenOf(id: Int): String? = tokenById.getOrNull(id)

    /** Whether [id] is an added (special) token rather than part of the learned vocabulary. */
    fun isAdded(id: Int): Boolean = id in addedIds

    /**
     * Text for [ids], as `tokenizers`' ByteLevel decoder produces it. Byte-level vocabularies only: the
     * other family's decoder is not needed by any model here.
     */
    fun decode(ids: IntArray, skipSpecial: Boolean = false): String {
        requireNotNull(byteLevelPrefixSpace) { "decode is only implemented for byte-level tokenizers" }
        val bytes = java.io.ByteArrayOutputStream(ids.size * 4)
        for (id in ids) {
            if (skipSpecial && id in addedIds) continue
            val token = tokenById.getOrNull(id) ?: continue
            val mapped = ByteLevel.toBytes(token)
            bytes.write(mapped ?: token.toByteArray(Charsets.UTF_8))
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** One pre-token through byte-fallback symbolization and rank-ordered merging. */
    private fun bpe(word: String, out: IntList) {
        if (ignoreMerges) vocab[word]?.let { out.add(it); return }

        // ---- symbolize: one symbol per code point, bytes for anything outside the vocabulary ----
        val ids = IntList(word.length + 4)
        if (byteLevelPrefixSpace != null) {
            // Every byte has a symbol of its own in a byte-level vocabulary, so nothing falls back.
            for (c in ByteLevel.toSymbols(word)) ids.add(vocab[c.toString()] ?: unkId)
            return merge(ids, out)
        }
        var pendingUnk = false
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val len = Character.charCount(cp)
            val id = vocab[word.substring(i, i + len)]
            if (id != null) {
                if (pendingUnk) { ids.add(unkId); pendingUnk = false }
                ids.add(id)
            } else {
                val bytes = word.substring(i, i + len).toByteArray(Charsets.UTF_8)
                val fallback = bytes.map { byteIds[it.toInt() and 0xFF] }
                if (fallback.all { it >= 0 }) {
                    // Deliberately does not flush a pending <unk> first — neither does the reference.
                    fallback.forEach(ids::add)
                } else if (unkId >= 0) {
                    if (pendingUnk && !fuseUnk) ids.add(unkId)
                    pendingUnk = true
                }
            }
            i += len
        }
        if (pendingUnk) ids.add(unkId)
        merge(ids, out)
    }

    private fun merge(ids: IntList, out: IntList) {
        val n = ids.size
        if (n == 0) return
        if (n == 1) { out.add(ids.data[0]); return }

        // ---- merge: a min-heap on (rank, position), re-validated on pop ----
        val sym = ids.data.copyOf(n)
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it == n - 1) -1 else it + 1 }
        val alive = BooleanArray(n) { true }
        val heap = MergeHeap(n)
        for (p in 0 until n - 1) {
            val slot = merges.find(sym[p], sym[p + 1])
            if (slot >= 0) heap.push(merges.rank(slot), p, merges.out(slot))
        }
        while (heap.size > 0) {
            val pos = heap.topPos()
            val newId = heap.topOut()
            heap.pop()
            if (!alive[pos]) continue
            val right = next[pos]
            if (right < 0) continue
            // An entry whose pair has since changed is stale; tokenizers checks the merged id.
            val slot = merges.find(sym[pos], sym[right])
            if (slot < 0 || merges.out(slot) != newId) continue

            sym[pos] = newId
            alive[right] = false
            val after = next[right]
            next[pos] = after
            if (after >= 0) prev[after] = pos

            val before = prev[pos]
            if (before >= 0) {
                val s = merges.find(sym[before], sym[pos])
                if (s >= 0) heap.push(merges.rank(s), before, merges.out(s))
            }
            if (after >= 0) {
                val s = merges.find(sym[pos], sym[after])
                if (s >= 0) heap.push(merges.rank(s), pos, merges.out(s))
            }
        }
        var p = 0
        while (p >= 0) { if (alive[p]) out.add(sym[p]); p = next[p] }
    }

    // ---------------------------------------------------------------------------------------------

    /** Normalizers as data (not lambdas) so the compact cache can store them. */
    internal sealed class Normalizer {
        abstract fun apply(s: String): String
        class Replace(val pattern: String, val content: String) : Normalizer() {
            override fun apply(s: String) = s.replace(pattern, content)
        }
        class Prepend(val prefix: String) : Normalizer() {
            override fun apply(s: String) = if (s.isEmpty()) s else prefix + s
        }
    }

    /** GPT-2's byte-level pre-tokenization: regex split, then every UTF-8 byte as one printable symbol. */
    internal object ByteLevel {
        /**
         * Unicode White_Space, spelled out: it is what Oniguruma's \s matches in `tokenizers`, and
         * Android's regex engine rejects the flag that would make Java's \s mean the same.
         */
        private const val WS = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

        /** The pattern `tokenizers` uses for byte-level pre-tokenization. */
        private val PATTERN = java.util.regex.Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^$WS\\p{L}\\p{N}]+|[$WS]+(?![^$WS])|[$WS]+",
        )

        /** Printable bytes stand for themselves; the rest are shifted past 255, as in GPT-2. */
        private val byteToChar = CharArray(256).also { t ->
            var shifted = 0
            for (b in 0 until 256) {
                val printable = b in 33..126 || b in 161..172 || b in 174..255
                t[b] = if (printable) b.toChar() else (256 + shifted++).toChar()
            }
        }
        private val charToByte = HashMap<Char, Byte>(512).also { m -> byteToChar.forEachIndexed { b, c -> m[c] = b.toByte() } }

        fun split(s: String): List<String> {
            val m = PATTERN.matcher(s)
            val out = ArrayList<String>()
            while (m.find()) out += m.group()
            return out
        }

        fun toSymbols(piece: String): CharArray {
            val bytes = piece.toByteArray(Charsets.UTF_8)
            return CharArray(bytes.size) { byteToChar[bytes[it].toInt() and 0xFF] }
        }

        /** The bytes a token stands for, or null when it has a character outside the byte alphabet. */
        fun toBytes(token: String): ByteArray? {
            val out = ByteArray(token.length)
            for (i in token.indices) out[i] = charToByte[token[i]] ?: return null
            return out
        }
    }

    /** A literal-pattern `Split` pre-tokenizer. */
    internal class SplitRule(val pattern: String, val behavior: String) {
        fun split(s: String): List<String> {
            if (pattern.isEmpty() || !s.contains(pattern)) return listOf(s)
            val out = ArrayList<String>()
            var start = 0
            var at = s.indexOf(pattern)
            while (at >= 0) {
                when (behavior) {
                    "MergedWithPrevious" -> { out += s.substring(start, at + pattern.length); start = at + pattern.length }
                    "Isolated" -> { out += s.substring(start, at); out += pattern; start = at + pattern.length }
                    "Removed" -> { out += s.substring(start, at); start = at + pattern.length }
                }
                at = s.indexOf(pattern, start)
            }
            out += s.substring(start)
            return out.filter { it.isNotEmpty() }
        }
    }

    internal class IntList(capacity: Int) {
        var data = IntArray(maxOf(capacity, 4)); var size = 0
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    }

    /** Open-addressed (a, b) → (rank, merged id). 515k merges in ~12 MB instead of ~60 MB boxed. */
    internal class MergeMap(expected: Int) {
        private val mask: Int
        private val keys: LongArray
        private val ranks: IntArray
        private val outs: IntArray
        init {
            var cap = 16
            while (cap < expected * 2) cap = cap shl 1
            mask = cap - 1
            keys = LongArray(cap) { EMPTY }
            ranks = IntArray(cap)
            outs = IntArray(cap)
        }
        private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        private fun slotOf(k: Long): Int {
            var h = k * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h.toInt() and mask
        }
        /** Later duplicates overwrite earlier ones, matching the reference's HashMap collect. */
        fun put(a: Int, b: Int, rank: Int, out: Int) {
            val k = key(a, b)
            var i = slotOf(k)
            while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) and mask
            keys[i] = k; ranks[i] = rank; outs[i] = out
        }
        fun find(a: Int, b: Int): Int {
            val k = key(a, b)
            var i = slotOf(k)
            while (true) {
                val cur = keys[i]
                if (cur == EMPTY) return -1
                if (cur == k) return i
                i = (i + 1) and mask
            }
        }
        fun rank(slot: Int) = ranks[slot]
        fun out(slot: Int) = outs[slot]

        /** Occupied entries as (a, b, rank, out), for the compact cache. */
        fun forEach(action: (a: Int, b: Int, rank: Int, out: Int) -> Unit) {
            for (i in keys.indices) {
                val k = keys[i]
                if (k != EMPTY) action((k ushr 32).toInt(), k.toInt(), ranks[i], outs[i])
            }
        }
        fun countEntries(): Int { var n = 0; forEach { _, _, _, _ -> n++ }; return n }
        private companion object { const val EMPTY = -1L }
    }

    /** Binary min-heap ordered by (rank, pos) carrying the merged id, without boxing. */
    private class MergeHeap(capacity: Int) {
        private var rank = IntArray(maxOf(capacity, 4))
        private var pos = IntArray(rank.size)
        private var out = IntArray(rank.size)
        var size = 0
            private set

        fun topPos() = pos[0]
        fun topOut() = out[0]

        private fun less(i: Int, j: Int) = rank[i] < rank[j] || (rank[i] == rank[j] && pos[i] < pos[j])
        private fun swap(i: Int, j: Int) {
            var t = rank[i]; rank[i] = rank[j]; rank[j] = t
            t = pos[i]; pos[i] = pos[j]; pos[j] = t
            t = out[i]; out[i] = out[j]; out[j] = t
        }

        fun push(r: Int, p: Int, o: Int) {
            if (size == rank.size) {
                rank = rank.copyOf(size * 2); pos = pos.copyOf(size * 2); out = out.copyOf(size * 2)
            }
            var i = size++
            rank[i] = r; pos[i] = p; out[i] = o
            while (i > 0) {
                val parent = (i - 1) / 2
                if (!less(i, parent)) break
                swap(i, parent); i = parent
            }
        }

        fun pop() {
            size--
            if (size == 0) return
            rank[0] = rank[size]; pos[0] = pos[size]; out[0] = out[size]
            var i = 0
            while (true) {
                val l = 2 * i + 1
                if (l >= size) break
                val r = l + 1
                val c = if (r < size && less(r, l)) r else l
                if (!less(c, i)) break
                swap(c, i); i = c
            }
        }
    }

    companion object {

        /**
         * Load [file], through a compact binary copy beside it when one matches.
         *
         * Parsing the 32 MB JSON takes seconds and would delay the first search after a cold start.
         * The cache holds the same vocabulary and merges in a form that loads much faster, keyed to the
         * JSON's size and timestamp so a reinstalled tokenizer is re-parsed.
         */
        fun load(file: File, cache: File? = File(file.path + CACHE_SUFFIX)): HfBpeTokenizer {
            if (cache != null) {
                runCatching { loadCompact(cache, file.length(), file.lastModified()) }.getOrNull()?.let { return it }
            }
            val t = file.bufferedReader(Charsets.UTF_8, 1 shl 16).use(::load)
            if (cache != null) runCatching { t.saveCompact(cache, file.length(), file.lastModified()) }.onFailure { cache.delete() }
            return t
        }

        const val CACHE_SUFFIX = ".fbin"
        private const val CACHE_MAGIC = 0x46425032 // "FBP2"

        internal fun loadCompact(cache: File, sourceLength: Long, sourceModified: Long): HfBpeTokenizer? {
            if (!cache.isFile) return null
            java.io.DataInputStream(java.io.BufferedInputStream(cache.inputStream(), 1 shl 16)).use { i ->
                if (i.readInt() != CACHE_MAGIC || i.readLong() != sourceLength || i.readLong() != sourceModified) return null
                val normalizers = List(i.readInt()) {
                    when (i.readByte().toInt()) {
                        0 -> Normalizer.Replace(i.readUTF(), i.readUTF())
                        else -> Normalizer.Prepend(i.readUTF())
                    }
                }
                val split = if (i.readBoolean()) SplitRule(i.readUTF(), i.readUTF()) else null
                val prefix = IntArray(i.readInt()) { i.readInt() }
                val suffix = IntArray(i.readInt()) { i.readInt() }
                val byteIds = IntArray(256) { i.readInt() }
                val unkId = i.readInt()
                val fuseUnk = i.readBoolean()
                val ignoreMerges = i.readBoolean()
                val byteLevel = when (i.readByte().toInt()) { 0 -> null; 1 -> false; else -> true }
                val lstrip = HashSet<Int>().also { s -> repeat(i.readInt()) { s += i.readInt() } }
                val rstrip = HashSet<Int>().also { s -> repeat(i.readInt()) { s += i.readInt() } }
                val added = LinkedHashMap<String, Int>()
                repeat(i.readInt()) { added[i.readUTF()] = i.readInt() }
                val vocabSize = i.readInt()
                val vocab = HashMap<String, Int>(vocabSize * 2)
                repeat(vocabSize) { vocab[i.readUTF()] = i.readInt() }
                val n = i.readInt()
                val merges = MergeMap(maxOf(n, 16))
                repeat(n) { merges.put(i.readInt(), i.readInt(), i.readInt(), i.readInt()) }
                if (i.readInt() != CACHE_MAGIC) return null // truncated write
                return HfBpeTokenizer(vocab, merges, added, normalizers, split, prefix, suffix, byteIds, unkId, fuseUnk, ignoreMerges, byteLevel, lstrip, rstrip)
            }
        }

        fun load(reader: Reader): HfBpeTokenizer {
            val p = JsonPull(reader)
            val added = LinkedHashMap<String, Int>()
            var normalizers: List<Normalizer> = emptyList()
            var split: SplitRule? = null
            var byteLevel: Boolean? = null
            var anyNormalizedAdded = false
            val lstrip = HashSet<Int>()
            val rstrip = HashSet<Int>()
            var template: List<String>? = null
            var vocab: HashMap<String, Int>? = null
            var rawMerges: ArrayList<Pair<String, String>>? = null
            var merges: MergeMap? = null
            var unkToken: String? = null
            var byteFallback = false
            var fuseUnk = false
            var ignoreMerges = false

            p.beginObject()
            while (p.hasNext()) {
                when (p.nextName()) {
                    "added_tokens" -> {
                        p.beginArray()
                        while (p.hasNext()) {
                            var id = -1; var content = ""; var special = false; var normalized = false
                            var stripLeft = false; var stripRight = false
                            p.beginObject()
                            while (p.hasNext()) when (p.nextName()) {
                                "id" -> id = p.nextInt()
                                "content" -> content = p.nextString()
                                "special" -> special = p.nextBoolean()
                                "normalized" -> normalized = p.nextBoolean()
                                "lstrip" -> stripLeft = p.nextBoolean()
                                "rstrip" -> stripRight = p.nextBoolean()
                                "single_word" -> require(!p.nextBoolean()) { "single-word added tokens are not supported" }
                                else -> p.skipValue()
                            }
                            p.endObject()
                            require(special) { "only special added tokens are supported" }
                            anyNormalizedAdded = anyNormalizedAdded || normalized
                            if (stripLeft) lstrip += id
                            if (stripRight) rstrip += id
                            added[content] = id
                        }
                        p.endArray()
                    }
                    "normalizer" -> normalizers = readNormalizer(p)
                    "pre_tokenizer" -> when (val pre = readPreTokenizer(p)) {
                        is SplitRule -> split = pre
                        is Boolean -> byteLevel = pre
                    }
                    "post_processor" -> template = readTemplate(p)
                    "model" -> {
                        p.beginObject()
                        while (p.hasNext()) when (p.nextName()) {
                            "type" -> require(p.nextString() == "BPE") { "only BPE models are supported" }
                            "unk_token" -> unkToken = if (p.skipIfNull()) null else p.nextString()
                            "byte_fallback" -> byteFallback = p.nextBoolean()
                            "fuse_unk" -> fuseUnk = p.nextBoolean()
                            "ignore_merges" -> ignoreMerges = p.nextBoolean()
                            "dropout" -> require(p.skipIfNull()) { "BPE dropout is not supported" }
                            "continuing_subword_prefix", "end_of_word_suffix" ->
                                require(p.skipIfNull() || p.nextString().isEmpty()) { "subword affixes are not supported" }
                            "vocab" -> {
                                val v = HashMap<String, Int>(1 shl 19)
                                p.beginObject()
                                while (p.hasNext()) { val k = p.nextName(); v[k] = p.nextInt() }
                                p.endObject()
                                vocab = v
                            }
                            "merges" -> {
                                // Resolved straight to ids when the vocabulary came first (as in every
                                // file `tokenizers` writes), so the strings never pile up.
                                val v = vocab
                                val mm = if (v != null) MergeMap(600_000) else null
                                val raw = if (v == null) ArrayList<Pair<String, String>>() else null
                                var rank = 0
                                p.beginArray()
                                while (p.hasNext()) {
                                    val (a, b) = readMerge(p)
                                    if (mm != null) addMerge(mm, v!!, a, b, rank) else raw!!.add(a to b)
                                    rank++
                                }
                                p.endArray()
                                merges = mm; rawMerges = raw
                            }
                            else -> p.skipValue()
                        }
                        p.endObject()
                    }
                    else -> p.skipValue()
                }
            }

            // Matching a "normalized" added token against normalized text only differs when there is a normalizer.
            require(!anyNormalizedAdded || normalizers.isEmpty()) { "normalized added tokens with a normalizer are not supported" }
            val v = requireNotNull(vocab) { "tokenizer has no vocabulary" }
            val mm = merges ?: MergeMap(maxOf(rawMerges?.size ?: 0, 16)).also { m ->
                rawMerges?.forEachIndexed { r, (a, b) -> addMerge(m, v, a, b, r) }
            }
            for ((content, id) in added) v.putIfAbsent(content, id)

            val byteIds = IntArray(256) { b ->
                if (!byteFallback) -1 else v["<0x%02X>".format(b)] ?: -1
            }
            val unk = unkToken?.let { v[it] } ?: -1

            // Template: special tokens before the sequence are the prefix, after it the suffix.
            val tpl = template ?: listOf("\$A")
            val seqAt = tpl.indexOf("\$A")
            fun ids(names: List<String>) = names.map { requireNotNull(v[it]) { "unknown template token $it" } }.toIntArray()

            return HfBpeTokenizer(
                vocab = v, merges = mm, added = added, normalizers = normalizers, split = split,
                prefix = ids(tpl.subList(0, seqAt)), suffix = ids(tpl.subList(seqAt + 1, tpl.size)),
                byteIds = byteIds, unkId = unk, fuseUnk = fuseUnk, ignoreMerges = ignoreMerges,
                byteLevelPrefixSpace = byteLevel, lstrip = lstrip, rstrip = rstrip,
            )
        }

        private fun addMerge(mm: MergeMap, v: Map<String, Int>, a: String, b: String, rank: Int) {
            val ia = v[a] ?: return
            val ib = v[b] ?: return
            val out = v[a + b] ?: return
            mm.put(ia, ib, rank, out)
        }

        /** Merges are `["a","b"]` in current files and `"a b"` in older ones. */
        private fun readMerge(p: JsonPull): Pair<String, String> =
            if (p.peek() == JsonPull.Token.BEGIN_ARRAY) {
                p.beginArray(); val a = p.nextString(); val b = p.nextString(); p.endArray(); a to b
            } else {
                val s = p.nextString()
                val sp = s.indexOf(' ')
                require(sp > 0) { "malformed merge '$s'" }
                s.substring(0, sp) to s.substring(sp + 1)
            }

        private fun readNormalizer(p: JsonPull): List<Normalizer> {
            if (p.skipIfNull()) return emptyList()
            val fields = readFlatObject(p)
            return when (val type = fields["type"]) {
                "Replace" -> {
                    val pattern = requireNotNull(fields["pattern.String"]) { "only literal Replace is supported" }
                    val content = fields["content"] ?: ""
                    listOf(Normalizer.Replace(pattern, content))
                }
                "Prepend" -> {
                    val pre = fields["prepend"] ?: ""
                    listOf(Normalizer.Prepend(pre))
                }
                "Sequence" -> fields.nested.flatten()
                else -> throw IllegalArgumentException("unsupported normalizer $type")
            }
        }

        /** A [SplitRule], or for ByteLevel whether it prepends a space. */
        private fun readPreTokenizer(p: JsonPull): Any? {
            if (p.skipIfNull()) return null
            val f = readFlatObject(p)
            if (f["type"] == "ByteLevel") {
                require(f["use_regex"] != "false") { "ByteLevel without its regex is not supported" }
                return f["add_prefix_space"] == "true"
            }
            require(f["type"] == "Split") { "unsupported pre-tokenizer ${f["type"]}" }
            require(f["invert"] != "true") { "inverted Split is not supported" }
            val pattern = requireNotNull(f["pattern.String"]) { "only literal Split patterns are supported" }
            val behavior = f["behavior"] ?: "Removed"
            require(behavior in setOf("MergedWithPrevious", "Isolated", "Removed")) { "unsupported Split behavior $behavior" }
            return SplitRule(pattern, behavior)
        }

        /** `TemplateProcessing.single`, or Roberta's `cls $A sep`, as a flat list of token names and `$A`. */
        private fun readTemplate(p: JsonPull): List<String>? {
            if (p.skipIfNull()) return null
            var type: String? = null
            var single: List<String>? = null
            var cls: String? = null
            var sep: String? = null
            p.beginObject()
            while (p.hasNext()) when (p.nextName()) {
                "type" -> type = p.nextString()
                "cls" -> { p.beginArray(); cls = p.nextString(); p.skipValue(); p.endArray() }
                "sep" -> { p.beginArray(); sep = p.nextString(); p.skipValue(); p.endArray() }
                "single" -> {
                    val out = ArrayList<String>()
                    p.beginArray()
                    while (p.hasNext()) {
                        p.beginObject()
                        while (p.hasNext()) {
                            val kind = p.nextName()
                            p.beginObject()
                            var id = ""
                            while (p.hasNext()) if (p.nextName() == "id") id = p.nextString() else p.skipValue()
                            p.endObject()
                            out += if (kind == "Sequence") "\$A" else id
                        }
                        p.endObject()
                    }
                    p.endArray()
                    single = out
                }
                else -> p.skipValue()
            }
            p.endObject()
            if (type == "RobertaProcessing") return listOf(requireNotNull(cls), "\$A", requireNotNull(sep))
            require(type == "TemplateProcessing") { "unsupported post-processor $type" }
            return single
        }

        /**
         * A small object read into dotted keys (`pattern.String`). Arrays of objects (a Sequence's
         * children) are parsed recursively as normalizers into [FlatObject.nested].
         */
        private class FlatObject : HashMap<String, String>() { val nested = ArrayList<List<Normalizer>>() }

        private fun readFlatObject(p: JsonPull, prefix: String = "", into: FlatObject = FlatObject()): FlatObject {
            p.beginObject()
            while (p.hasNext()) {
                val name = p.nextName()
                when (p.peek()) {
                    JsonPull.Token.BEGIN_OBJECT -> readFlatObject(p, "$prefix$name.", into)
                    JsonPull.Token.BEGIN_ARRAY -> {
                        p.beginArray()
                        while (p.hasNext()) into.nested += readNormalizer(p)
                        p.endArray()
                    }
                    JsonPull.Token.STRING -> into["$prefix$name"] = p.nextString()
                    JsonPull.Token.BOOLEAN -> into["$prefix$name"] = p.nextBoolean().toString()
                    JsonPull.Token.NULL -> p.nextNull()
                    else -> into["$prefix$name"] = p.nextDouble().toString()
                }
            }
            p.endObject()
            return into
        }
    }
}
