package ai.rightone.finderplus.index

import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.db.entity.LabelPrototypeEntity
import ai.rightone.finderplus.db.vector.Vecs
import ai.rightone.finderplus.model.EmbeddingKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-stage open-vocabulary recognition over the frozen backbone.
 *
 * Stage 1 scores [ConceptVocabulary.GATES] to decide which domains the image belongs to; stage 2
 * expands only those domains. This is what makes general-purpose breadth work on a phone: a flat
 * softmax over every concept would spread probability so thin that nothing clears a useful threshold,
 * while a gated softmax stays sharp inside each branch no matter how many branches exist.
 *
 * The user's own labels are not a competing vocabulary — they **outrank** it. A concept the user has
 * demonstrated is judged on their examples, and when it fires it displaces the generic label for the
 * same idea. That is the intended relationship: the shipped vocabulary is a starting point that the
 * gallery progressively overwrites.
 */
@Singleton
class ConceptClassifier @Inject constructor(
    private val db: FinderDatabase,
    private val learner: LabelLearner,
    private val vocab: ConceptVocabulary,
) {

    /** One recognized concept, with where it came from. */
    data class Concept(
        val label: String,
        val score: Float,
        val domain: String,
        val taught: Boolean,
        /** A named-entity guess (person/landmark/brand): proposal-only, never auto-applied. */
        val isEntity: Boolean = false,
    )

    /**
     * Everything the model believes about one item: its concepts plus the single coarse domain that
     * best describes it.
     *
     * @param domain name of the winning stage-1 domain, or null when no domain held enough of the
     *   probability mass to be worth asserting.
     */
    data class Reading(
        val concepts: List<Concept>,
        val domain: String?,
        val domainConfidence: Float,
    )

    /** Concepts only — the long-standing entry point. See [read] for the domain as well. */
    suspend fun classify(itemId: Long, limit: Int = 8): List<Concept> = read(itemId, limit).concepts

    /**
     * Everything the model believes about this item, personal labels first.
     *
     * ### Items with more than one vector (video)
     *
     * A video stores one embedding per keyframe, so "what is this item" is a question about a *set* of
     * vectors. The answer is the **mean of the per-frame posteriors** — P(concept | video) =
     * mean_i P(concept | frame_i) — which is a proper mixture over frames and needs no threshold of its
     * own: a concept present throughout keeps its full score, while one that appears in a single frame is
     * divided by the frame count and falls out on its own.
     *
     * The alternative that was measured and rejected is taking the **union of each frame's winners**,
     * which is what this pipeline used to do for video labels. On 502 indexed videos, 52% of the
     * concepts it produced appeared in a tenth or less of the video's frames, and the failure is visible
     * in single results: one clip was labelled `cow, horse, shark, dog, turtle, screenshot of a video
     * game` because six different frames each won something different. Nothing about that is a weakness
     * of the encoder — a union of per-frame classifications is simply not a statement about the video,
     * and swapping in a stronger backbone would reproduce it exactly.
     *
     * Mean-of-posteriors is also *exactly* today's behaviour for a single-vector item, because the mean
     * of one value is that value. Images are unaffected, bit for bit.
     *
     * @param limit maximum concepts returned across all domains.
     */
    suspend fun read(itemId: Long, limit: Int = 8): Reading {
        val vecs = imageVectors(itemId)
        if (vecs.isEmpty()) return EMPTY
        val prototypes = db.labelPrototypeDao().all()
        if (prototypes.isEmpty()) return EMPTY
        val byLabel = prototypes.associateBy { it.label }

        val out = ArrayList<Concept>()

        // --- Personal head. Judged on exemplars, so it uses the image-to-image scale. ---
        val taughtLabels = HashSet<String>()
        for (s in learner.suggest(itemId, limit = limit)) {
            if (!s.taught) continue
            out += Concept(s.label, s.score, domain = "personal", taught = true)
            taughtLabels += s.label
        }

        // Accumulated joint score per label, and the mean gate posterior, both over all frames.
        val joint = LinkedHashMap<String, Float>()
        val domainOfLabel = HashMap<String, String>()
        val gateTotals = FloatArray(vocab.gates.size)
        val gatePrototypes = vocab.gates.map { byLabel[it.lowercase()] }

        for (vec in vecs) {
            // --- Stage 1: which domains is this frame even in? ---
            val gateScores = softmaxOver(gatePrototypes, vec)
            for (i in gateTotals.indices) gateTotals[i] += gateScores[i]
            val chosen = vocab.gates.indices
                .sortedByDescending { gateScores[it] }
                .take(ConceptVocabulary.GATES_TO_EXPAND)
                .filter { gateScores[it] >= MIN_GATE_PROB }

            // --- Stage 2: expand only those domains. ---
            for (gateIndex in chosen) {
                val domain = vocab.domainOf(vocab.gates[gateIndex]) ?: continue
                val isEntity = domain.entity
                val floor = if (isEntity) ConceptVocabulary.ENTITY_MIN_PROB else LabelLearner.ZERO_SHOT_MIN_PROB

                val gateProb = gateScores[gateIndex]
                val labels = domain.concepts.map { it.lowercase() }
                val domainPrototypes = labels.map { byLabel[it] }
                val scores = softmaxOver(domainPrototypes, vec)
                // Raw cosine, kept alongside the softmax because the two answer different questions and
                // only one of them can say "none of these".
                val cosines = FloatArray(labels.size) { i ->
                    domainPrototypes[i]?.text_prior
                        ?.let { Vecs.fromBytes(it) }
                        ?.takeIf { it.size == vec.size }
                        ?.let { Vecs.dot(it, vec) } ?: -1f
                }
                for (i in labels.indices) {
                    // Entities need an ABSOLUTE similarity floor, not just a relative one.
                    //
                    // A softmax is forced to sum to 1, so it always crowns a winner — even over a set
                    // where nothing fits. On a street photo with no landmark in it, "the Great Wall of
                    // China" won the entity branch at 0.386 purely by being the least-bad of ~100
                    // options. Relative confidence cannot express "none of these"; raw cosine can,
                    // because a genuine text-image match measures ~0.30+ on this gallery while a
                    // non-match sits near ~0.20.
                    if (isEntity && cosines[i] < ENTITY_MIN_COSINE) continue
                    // Admission uses the *conditional* probability — "given this is a vehicle photo, is
                    // it a car?" — so each domain keeps its own sharp floor regardless of how likely the
                    // domain was.
                    if (scores[i] < floor) continue
                    // A concept the user has taught wins on their evidence; the generic copy is dropped
                    // rather than shown twice with two different confidences.
                    if (labels[i] in taughtLabels) continue
                    // Ranking uses the *joint* probability P(domain) × P(concept | domain). Without the
                    // gate factor, scores from different domains are not comparable at all: a weak
                    // concept inside a barely-plausible domain can outrank a strong one inside the
                    // obvious domain, which is exactly what put "running race" (0.21) above "car" (0.19)
                    // on a photo of a bus.
                    joint[labels[i]] = (joint[labels[i]] ?: 0f) + gateProb * scores[i]
                    domainOfLabel[labels[i]] = domain.name
                }
            }
        }

        // Divide by the frame count, turning accumulated evidence into a posterior on the same scale the
        // auto-apply / review bands were calibrated against.
        val frames = vecs.size.toFloat()
        for ((label, total) in joint) {
            out += Concept(
                label, total / frames, domainOfLabel[label] ?: "",
                taught = false, isEntity = label in vocab.entityConcepts,
            )
        }

        val bestGate = gateTotals.indices.maxByOrNull { gateTotals[it] }
        val domainConfidence = bestGate?.let { gateTotals[it] / frames } ?: 0f
        val domain = bestGate
            ?.takeIf { domainConfidence >= MIN_CATEGORY_PROB }
            ?.let { vocab.domainOf(vocab.gates[it])?.name }

        return Reading(
            concepts = out.sortedWith(compareByDescending<Concept> { it.taught }.thenByDescending { it.score })
                .take(limit),
            domain = domain,
            domainConfidence = domainConfidence,
        )
    }

    /**
     * Softmax of `logit_scale * cos(prior, image)` over the supplied prototypes.
     *
     * Missing prototypes score zero rather than being dropped, so indices stay aligned with the caller's
     * label list — a seeded vocabulary is often incomplete while [VocabularySeedWorker] is still running.
     */
    private fun softmaxOver(prototypes: List<LabelPrototypeEntity?>, vec: FloatArray): FloatArray {
        val n = prototypes.size
        val logits = FloatArray(n) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            val prior = prototypes[i]?.text_prior
                ?.let { Vecs.fromBytes(it) }
                ?.takeIf { it.size == vec.size } ?: continue
            logits[i] = LabelLearner.LOGIT_SCALE * Vecs.dot(prior, vec)
        }
        val max = logits.max()
        if (max == Float.NEGATIVE_INFINITY) return FloatArray(n)
        var sum = 0.0
        val exp = DoubleArray(n)
        for (i in 0 until n) {
            exp[i] = if (logits[i] == Float.NEGATIVE_INFINITY) 0.0
                     else kotlin.math.exp((logits[i] - max).toDouble())
            sum += exp[i]
        }
        return FloatArray(n) { if (sum <= 0.0) 0f else (exp[it] / sum).toFloat() }
    }

    /**
     * Every stored image vector for the item — one for a photo, one per keyframe for a video.
     *
     * This used to take `.firstOrNull()`, which for a video meant one arbitrary keyframe spoke for the
     * whole file: whichever row the query happened to return first decided what a three-minute clip was
     * "about". Degenerate all-zero vectors are dropped rather than averaged in, since a zero vector is
     * equally similar to every concept and would drag the mean toward nothing.
     */
    private suspend fun imageVectors(itemId: Long): List<FloatArray> =
        db.embeddingDao()
            .vectorsOfKindFiltered(EmbeddingKind.IMAGE_CLIP.ordinal, listOf(itemId))
            .mapNotNull { row ->
                val v = Vecs.fromBytes(row.vec)
                if (v.isEmpty() || v.all { it == 0f }) null else Vecs.normalized(v)
            }

    // ------------------------------------------------------------------------------------------
    // Gallery-adaptive tuning
    // ------------------------------------------------------------------------------------------

    data class TuningReport(
        val sampled: Int,
        val kept: List<Pair<String, Int>>,
        val pruned: List<String>,
    )

    /**
     * Reshape the vocabulary around what this gallery actually contains.
     *
     * A shipped concept list is a guess about someone's life. Half of it is dead weight for any given
     * person — and dead weight is not free, because every unused concept still takes softmax mass from
     * the concepts that matter, making real matches score lower than they should. Pruning what never
     * fires therefore *increases* confidence on what does.
     *
     * Only seeded concepts are eligible. Anything the user taught is untouchable regardless of how
     * rarely it appears: a label used once, on purpose, is the most valuable label in the database.
     *
     * @param sampleSize how many items to measure against; the full gallery is unnecessary.
     * @param minHits a seeded concept must win on at least this many sampled items to survive.
     */
    suspend fun tuneToGallery(
        sampleSize: Int = 400,
        minHits: Int = 1,
        isStopRequested: suspend () -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TuningReport {
        val items = db.mediaItemDao().itemsWithEmbeddingNoUserLabel(sampleSize)
        val hits = HashMap<String, Int>()
        var sampled = 0
        for ((i, id) in items.withIndex()) {
            if (isStopRequested()) break
            if (i % 25 == 0) onProgress(i, items.size)
            for (c in classify(id, limit = 12)) {
                if (c.taught) continue
                hits[c.label] = (hits[c.label] ?: 0) + 1
            }
            sampled++
        }
        if (sampled == 0) return TuningReport(0, emptyList(), emptyList())

        // Gates are structural, not content — they must never be pruned or stage 1 stops working.
        val protected = vocab.gates.map { it.lowercase() }.toSet()
        val pruned = ArrayList<String>()
        for (p in db.labelPrototypeDao().all()) {
            if (p.origin != LabelPrototypeEntity.ORIGIN_SEED) continue
            if (p.exemplar_count > 0) continue
            if (p.label in protected) continue
            if ((hits[p.label] ?: 0) >= minHits) continue
            db.labelPrototypeDao().delete(p.label)
            pruned += p.label
        }
        return TuningReport(
            sampled = sampled,
            kept = hits.entries.sortedByDescending { it.value }.map { it.key to it.value },
            pruned = pruned,
        )
    }

    companion object {
        private val EMPTY = Reading(emptyList(), null, 0f)

        /** A domain must look at least this plausible before its concepts are worth scoring. */
        const val MIN_GATE_PROB = 0.08f

        /**
         * Share of the frame-averaged gate mass the winning domain must hold before it is asserted as
         * the item's coarse category.
         *
         * Much higher than [MIN_GATE_PROB] because this is a claim, not a shortlist. There are 13
         * domains, so chance is 0.077 — this floor asks the winner to hold roughly six times that, i.e.
         * to be what the item is *mostly* about across its whole length.
         *
         * Measured over 502 indexed videos: the frame-averaged top gate has median 0.458 (p25 0.352,
         * p90 0.727), and the floor admits 263 of them. Validation is external rather than circular —
         * the highest-scoring categories match what the filenames independently say, e.g.
         * `Screen_Recording_20250906_014656_Google.mp4` → `screen` at 0.96 — while everything the floor
         * rejects sits at 0.18-0.22, barely above chance and genuinely ambiguous.
         */
        const val MIN_CATEGORY_PROB = 0.45f

        /**
         * Raw cosine an entity must reach before it may be named at all.
         *
         * Measured on this gallery: an unmistakable text-image match reaches ~0.29-0.32, an unrelated
         * pair sits near 0.20. 0.28 sits just under the match band, which is where it belongs — the
         * cost of missing a landmark is a missing tag, the cost of inventing one is a label the user
         * would rightly call broken.
         */
        const val ENTITY_MIN_COSINE = 0.28f
    }
}
