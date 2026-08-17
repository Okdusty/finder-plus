package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.model.EmbeddingKind
import javax.inject.Inject
import javax.inject.Singleton

/** A group of items the encoder considers the same kind of thing, awaiting one name from the user. */
data class Cluster(
    val members: List<Long>,
    /** The member closest to the group's centre — the one to show as its face. */
    val representative: Long,
    /** Mean pairwise similarity to the centroid. Tight groups are safer to name in bulk. */
    val cohesion: Float,
    /** Labels the pipeline already guessed for these items, most common first. */
    val existingLabels: List<String>,
)

/**
 * Groups visually similar items so the user can teach many at once.
 *
 * This is the payoff of keeping every embedding: naming one photo teaches a prototype from one example,
 * but naming a *cluster* of forty teaches it from forty — and confirming a group of near-identical
 * things is roughly the same amount of work as confirming one. It is the same mechanism face recognition
 * uses ("who is this?" once, then matched everywhere), generalized past faces to whatever the gallery
 * actually contains.
 *
 * Deliberately runs at the **end** of indexing rather than during it. Clusters computed from a partial
 * index are misleading: a group looks tight only because the items that would have joined it have not
 * been embedded yet, so the user would be asked to name the same concept repeatedly as it fragments.
 *
 * The algorithm is single-link agglomeration by cosine threshold — union-find over pairs above
 * [SIMILARITY]. Chosen over k-means because the number of groups is unknown and must not be guessed,
 * and over HDBSCAN because this has to run on a phone over thousands of 512-d vectors with no library.
 */
@Singleton
class SimilarityClusterer @Inject constructor(private val db: FinderDatabase) {

    /**
     * @param minSize groups smaller than this are dropped. A pair is not worth a question; the whole
     *   point is to amortize one answer over many items.
     */
    suspend fun cluster(
        maxItems: Int = 4000,
        minSize: Int = 4,
        isStopRequested: suspend () -> Boolean = { false },
    ): List<Cluster> {
        val rows = db.embeddingDao().vectorsOfKind(EmbeddingKind.IMAGE_CLIP.ordinal)
            .asSequence()
            .mapNotNull { row ->
                val v = Vecs.fromBytes(row.vec)
                // Zero vectors predate a real encoder and are "similar" to everything; one of them would
                // merge every cluster into a single useless blob.
                if (v.isEmpty() || v.all { it == 0f }) null else row.item_id to Vecs.normalized(v)
            }
            .take(maxItems)
            .toList()
        if (rows.size < minSize) return emptyList()

        val n = rows.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }

        // O(n^2/2) dot products. At n=4000 that is ~8M x 512 multiply-adds — seconds on a phone, and it
        // runs once at the end of a multi-hour index, so a smarter index structure would be premature.
        for (i in 0 until n) {
            if (i % 200 == 0 && isStopRequested()) return emptyList()
            val vi = rows[i].second
            for (j in i + 1 until n) {
                if (Vecs.dot(vi, rows[j].second) >= SIMILARITY) union(i, j)
            }
        }

        val groups = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(i)

        val out = ArrayList<Cluster>()
        for (idx in groups.values) {
            if (idx.size < minSize) continue
            val dim = rows[idx[0]].second.size
            val centroid = FloatArray(dim)
            for (i in idx) {
                val v = rows[i].second
                for (d in 0 until dim) centroid[d] += v[d]
            }
            val c = Vecs.normalized(centroid)

            var best = idx[0]
            var bestSim = -1f
            var total = 0f
            for (i in idx) {
                val sim = Vecs.dot(c, rows[i].second)
                total += sim
                if (sim > bestSim) { bestSim = sim; best = i }
            }

            val members = idx.map { rows[it].first }
            out += Cluster(
                members = members,
                representative = rows[best].first,
                cohesion = total / idx.size,
                existingLabels = commonLabels(members),
            )
        }
        // Biggest first: naming those buys the most per question.
        return out.sortedByDescending { it.members.size }
    }

    /**
     * Group face crops by identity — **only if the embedding space demonstrably separates identities.**
     *
     * The general encoder does not, and this is measured rather than assumed. Two faces cropped from the
     * *same photo* are almost always different people, which makes them free labelled negatives. On this
     * gallery the two most similar face crops in the entire set were both same-photo pairs (0.868, 0.859),
     * and at every threshold selective enough to be useful the majority of surviving pairs were same-photo:
     *
     * ```
     * threshold  pairs  same-photo (provably wrong)
     *   0.84       3          2
     *   0.86       1          1
     *   0.88       0          0
     * ```
     *
     * That is CLIP behaving exactly as trained — it aligns images with captions, so a face crop encodes
     * appearance: lighting, camera, clothing, scene. Two strangers photographed together share all of
     * those. Identity needs a model trained with margin losses to separate people *across* those
     * variations, which CLIP is not.
     *
     * So rather than ship a threshold that merges strangers, this checks separability first and returns
     * nothing when the check fails. Grouping different people and inviting the user to name them as one
     * would not merely be useless — it would poison the prototype with a false positive that then
     * propagates through every future match.
     *
     * Drop a MobileFaceNet-class embedder into `face.embedding` and this method starts working unchanged;
     * [separates] will pass and the clusters become real.
     */
    suspend fun clusterFaces(minSize: Int = 3): List<Cluster> {
        val faces = db.faceDao().facesWithEmbedding()
            .mapNotNull { f ->
                val v = f.embedding?.let { Vecs.fromBytes(it) } ?: return@mapNotNull null
                if (v.isEmpty() || v.all { it == 0f }) null else Triple(f.id, f.item_id, Vecs.normalized(v))
            }
        if (faces.size < minSize) return emptyList()
        if (!separates(faces)) {
            android.util.Log.i(
                TAG,
                "face embeddings do not separate identity at $FACE_SIMILARITY — refusing to cluster. " +
                    "Install a face-specific embedder to enable people grouping.",
            )
            return emptyList()
        }

        val n = faces.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }

        for (i in 0 until n) {
            val vi = faces[i].third
            for (j in i + 1 until n) {
                // Never merge two faces from one photo: they are the one pair we know to be different
                // people, so joining them is a guaranteed error regardless of similarity.
                if (faces[i].second == faces[j].second) continue
                if (Vecs.dot(vi, faces[j].third) >= FACE_SIMILARITY) union(i, j)
            }
        }

        val groups = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(i)

        val out = ArrayList<Cluster>()
        for (idx in groups.values) {
            if (idx.size < minSize) continue
            val dim = faces[idx[0]].third.size
            val centroid = FloatArray(dim)
            for (i in idx) {
                val v = faces[i].third
                for (d in 0 until dim) centroid[d] += v[d]
            }
            val c = Vecs.normalized(centroid)
            var best = idx[0]; var bestSim = -1f; var total = 0f
            for (i in idx) {
                val sim = Vecs.dot(c, faces[i].third)
                total += sim
                if (sim > bestSim) { bestSim = sim; best = i }
            }
            out += Cluster(
                // The *items* are what the user sees and what a name gets applied to.
                members = idx.map { faces[it].second }.distinct(),
                representative = faces[best].second,
                cohesion = total / idx.size,
                existingLabels = emptyList(),
            )
        }
        return out.sortedByDescending { it.members.size }
    }

    /**
     * Does this embedding space actually distinguish people?
     *
     * Uses same-photo face pairs as approximate known negatives — two faces in one frame are usually
     * different people — and requires that few of the pairs above the threshold are such pairs. If the
     * "most similar faces" are mostly strangers standing next to each other, the space is encoding the
     * scene rather than the person and any clustering built on it is noise.
     *
     * "Approximate" is deliberate: a collage, a screenshot of a conversation, or a photo of a photo can
     * legitimately contain one person twice, so a small false-pair rate is expected even from a good
     * embedder. Measured here: CLIP crops failed at 67% (2 of 3 surviving pairs), MobileFaceNet passes at
     * 8.7% — an order of magnitude apart, which is why a single loose bound separates them cleanly.
     *
     * A free, self-calibrating check: no labelled identity data needed, and it re-evaluates itself
     * automatically if the embedder is ever swapped.
     */
    private fun separates(faces: List<Triple<Long, Long, FloatArray>>): Boolean {
        var above = 0
        var samePhoto = 0
        for (i in faces.indices) {
            for (j in i + 1 until faces.size) {
                if (Vecs.dot(faces[i].third, faces[j].third) < FACE_SIMILARITY) continue
                above++
                if (faces[i].second == faces[j].second) samePhoto++
            }
        }
        // Too few pairs to judge: treat as "cannot verify", which is the same as "do not trust".
        if (above < MIN_PAIRS_TO_JUDGE) return false
        return samePhoto.toFloat() / above < MAX_FALSE_PAIR_RATIO
    }

    /**
     * Labels the pipeline already produced for these items, commonest first.
     *
     * Shown alongside the group so the question is "is this *dad's car*?" rather than "what is this?".
     * A cluster whose members already agree on a label is one the user can confirm at a glance.
     */
    private suspend fun commonLabels(members: List<Long>, limit: Int = 4): List<String> {
        val counts = HashMap<String, Int>()
        for (id in members.take(COMMON_LABEL_SAMPLE)) {
            for (t in db.contentDao().tagsForItem(id)) {
                // Only sources that describe content, and never SUGGESTED — the point is to summarize
                // what is believed, not to launder an unconfirmed guess into a prompt.
                if (t.source !in DESCRIPTIVE_SOURCES) continue
                counts[t.label] = (counts[t.label] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    private companion object {
        /**
         * Cosine floor for "the same thing".
         *
         * Image-to-image similarity in this space runs ~0.6-0.9 for related content, so 0.82 is
         * deliberately strict. Single-link clustering chains transitively — A~B and B~C merges A and C
         * even if A and C are unrelated — so a loose threshold does not produce loose clusters, it
         * produces one giant one. Strict-and-fragmented is recoverable; merged-into-mush is not.
         */
        const val SIMILARITY = 0.82f

        /**
         * Cosine floor for "the same person", against MobileFaceNet embeddings.
         *
         * Chosen from measured cluster outcomes on this gallery, not from intuition — two earlier guesses
         * were wrong by large margins. With a margin-trained embedder, two unrelated faces are close to
         * orthogonal (median pairwise cosine 0.016, versus 0.637 for CLIP crops), so the useful threshold
         * sits far lower than an appearance encoder would need:
         *
         * ```
         * thr   groups  largest   note
         * 0.40     8       52
         * 0.60     4       52     chosen
         * 0.70     3       48
         * 0.85     4        4     too strict, real people fragment
         * ```
         *
         * 0.60 is on the conservative side of the usable range because splitting is the safer failure —
         * the user can name two groups the same thing but cannot unmerge one. The largest group holds its
         * 52 members from 0.40 through 0.65, which is the signature of a genuine identity rather than
         * single-link chaining: chained groups fragment as the threshold rises. Verified: 52 faces across
         * 33 *distinct* photos at 0.818 cohesion.
         *
         * Whether the space is usable at all is still decided by [separates], not by this constant.
         */
        const val FACE_SIMILARITY = 0.60f

        /** Below this many above-threshold pairs there is no evidence either way, so do not cluster. */
        const val MIN_PAIRS_TO_JUDGE = 20

        /** Above this share of provably-wrong pairs, the space is not separating identity. */
        const val MAX_FALSE_PAIR_RATIO = 0.15f

        const val TAG = "finderCluster"

        /** Enough members to establish the common labels without querying hundreds of rows. */
        const val COMMON_LABEL_SAMPLE = 12

        /** LABEL, OBJECT, CATEGORY, USER, LEARNED, CONCEPT — everything except OCR noise and SUGGESTED. */
        val DESCRIPTIVE_SOURCES = setOf(0, 1, 3, 4, 6, 7)
    }
}
