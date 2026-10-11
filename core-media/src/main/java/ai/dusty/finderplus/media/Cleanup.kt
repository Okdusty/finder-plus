package ai.dusty.finderplus.media

import ai.dusty.finderplus.model.MediaKind
import java.util.Random

/** One media file as the clean-up screen sees it: enough to group, rank and explain it. */
data class MediaFile(
    val id: Long,
    val uri: String,
    val kind: MediaKind,
    val name: String,
    val size: Long,
    /** MediaStore RELATIVE_PATH, e.g. "DCIM/Camera/" or "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/". */
    val relPath: String,
    val dateTakenMs: Long?,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
    val mime: String? = null,
) {
    /** Best guess at when the content came into being. */
    val bornMs: Long get() = dateTakenMs?.takeIf { it > 0 } ?: dateAddedMs
    val pixels: Long get() = width.toLong() * height
    val folder: String get() = relPath.trimEnd('/').substringAfterLast('/').ifEmpty { relPath.trimEnd('/').ifEmpty { "Storage" } }
}

/** A set of files that are the same media; [keep] is the one to hold on to, and why. */
data class DuplicateGroup(
    val files: List<MediaFile>,
    val keep: MediaFile,
    val reason: String,
) {
    val extras: List<MediaFile> get() = files.filter { it.id != keep.id }
    val reclaimable: Long get() = extras.sumOf { it.size }
}

/**
 * Exact duplicates: byte-for-byte identical files.
 *
 * Staged so little is read: files are grouped by size (free from MediaStore), only same-size files
 * get a partial fingerprint (first and last 64 KiB), and only files that still match get a full
 * SHA-256. Nothing is called a duplicate on less than a full-hash match, because the next step offered
 * is deletion.
 */
object DuplicateFinder {

    enum class Stage { PARTIAL, FULL }

    /**
     * @param fingerprint hash of [MediaFile] at [Stage], or null when the file cannot be read (it is
     *   then left out — an unreadable file is never proposed for deletion).
     * @param onProgress files fingerprinted so far, out of the candidates that need it.
     */
    suspend fun find(
        files: List<MediaFile>,
        fingerprint: suspend (MediaFile, Stage) -> String?,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DuplicateGroup> {
        val sameSize = files.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        var total = sameSize.sumOf { it.size }
        var done = 0

        // Phase 1: head + tail of every same-size file.
        val needFull = ArrayList<List<MediaFile>>()
        for (bySize in sameSize) {
            val byPartial = bySize.groupBy { f -> fingerprint(f, Stage.PARTIAL).also { done++; onProgress(done, total) } }
            for ((partial, candidates) in byPartial) if (partial != null && candidates.size > 1) needFull += candidates
        }

        // Phase 2: the whole file, only where the cheap check could not rule a match out.
        total += needFull.sumOf { it.size }
        val out = ArrayList<DuplicateGroup>()
        for (candidates in needFull) {
            val byFull = candidates.groupBy { f -> fingerprint(f, Stage.FULL).also { done++; onProgress(done, total) } }
            for ((full, same) in byFull) {
                if (full == null || same.size < 2) continue
                val (keep, reason) = chooseOriginal(same)
                out += DuplicateGroup(same.sortedBy { it.bornMs }, keep, reason)
            }
        }
        return out.sortedByDescending { it.reclaimable }
    }

    /**
     * Which copy is the original. A camera capture beats any copy of it; a file outside the folders
     * where copies accumulate (downloads, messengers) beats one inside; otherwise the oldest wins,
     * then the name without a " (1)" suffix.
     */
    fun chooseOriginal(files: List<MediaFile>): Pair<MediaFile, String> {
        files.filter { it.relPath.startsWith("DCIM/Camera", ignoreCase = true) }.minByOrNull { it.bornMs }
            ?.let { return it to "the camera original" }
        val originals = files.filterNot { isCopyFolder(it.relPath) }
        if (originals.isNotEmpty() && originals.size < files.size) {
            return originals.minBy { it.bornMs } to "not a downloaded or shared copy"
        }
        val oldest = files.minWith(compareBy<MediaFile> { it.bornMs }.thenBy { it.name.length })
        return oldest to "the oldest copy"
    }

    private val COPY_FOLDERS = listOf(
        "download", "whatsapp", "telegram", "messenger", "signal", "instagram", "snapchat", "/sent",
        "bluetooth", "nearby share", "quick share", "pictures/twitter", "facebook",
    )

    fun isCopyFolder(relPath: String): Boolean {
        val p = relPath.lowercase(java.util.Locale.ROOT)
        return COPY_FOLDERS.any { it in p }
    }
}

/**
 * Look-alikes: the same photo saved differently — a messenger's recompressed copy, a resized export,
 * a burst. Byte comparison cannot see these; the CLIP vectors the indexer already stored can.
 *
 * Comparing every pair is quadratic and too slow on a phone for a large library, so candidates come
 * from locality-sensitive hashing: [TABLES] tables of [BITS] random-hyperplane bits. Two vectors at
 * cosine 0.96 agree on a random hyperplane ~91% of the time, so with these sizes a true pair shares at
 * least one bucket ~98% of the time, while each bucket holds only a small slice of the library. Every
 * candidate pair is then confirmed with the exact cosine.
 *
 * Groups are offered for review only, never preselected, because "looks the same" is a judgement the
 * user makes, not a fact like an identical hash.
 */
object LookAlikeFinder {

    val THRESHOLD: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.SimilaritySettings.LOOK_ALIKE].toFloat()
    private const val BITS = 10
    private const val TABLES = 8

    /** Largest group kept; beyond this a chain has drifted from "the same photo" to "the same kind". */
    private const val MAX_GROUP = 12

    /**
     * @param vectors item id → L2-normalized embedding (all the same length).
     * @return groups of ≥ 2 ids, each member similar to at least one other at ≥ [threshold].
     */
    fun groups(vectors: List<Pair<Long, FloatArray>>, threshold: Float = THRESHOLD, seed: Long = 42): List<List<Long>> {
        val n = vectors.size
        if (n < 2) return emptyList()
        val dim = vectors[0].second.size
        val rnd = Random(seed)
        val planes = Array(TABLES * BITS) { FloatArray(dim) { rnd.nextGaussian().toFloat() } }

        val parent = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }

        val checked = HashSet<Long>()
        for (t in 0 until TABLES) {
            val buckets = HashMap<Int, MutableList<Int>>()
            for (i in 0 until n) {
                val v = vectors[i].second
                var code = 0
                for (b in 0 until BITS) if (dot(planes[t * BITS + b], v) >= 0f) code = code or (1 shl b)
                buckets.getOrPut(code) { ArrayList() } += i
            }
            for (bucket in buckets.values) {
                if (bucket.size < 2) continue
                for (x in bucket.indices) for (y in x + 1 until bucket.size) {
                    val a = bucket[x]; val b = bucket[y]
                    if (!checked.add(a.toLong() * n + b)) continue
                    if (dot(vectors[a].second, vectors[b].second) >= threshold) {
                        val ra = find(a); val rb = find(b)
                        if (ra != rb) parent[rb] = ra
                    }
                }
            }
        }
        return (0 until n).groupBy { find(it) }.values
            .filter { it.size in 2..MAX_GROUP }
            .map { g -> g.map { vectors[it].first } }
    }

    /** Which look-alike to keep: the most pixels, then the biggest file, then the camera original. */
    fun chooseBest(files: List<MediaFile>): Pair<MediaFile, String> {
        val best = files.maxWith(
            compareBy<MediaFile> { it.pixels }
                .thenBy { it.size }
                .thenBy { if (it.relPath.startsWith("DCIM/Camera", ignoreCase = true)) 1 else 0 }
                .thenByDescending { it.bornMs },
        )
        val reason = when {
            files.any { it.id != best.id && it.pixels < best.pixels } -> "the highest resolution"
            files.any { it.id != best.id && it.size < best.size } -> "the best quality"
            else -> "the original"
        }
        return best to reason
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
