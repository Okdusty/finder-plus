package ai.rightone.finderplus.index

import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.db.entity.LabelPrototypeEntity
import ai.rightone.finderplus.db.vector.Vecs
import ai.rightone.finderplus.model.EmbeddingKind
import ai.rightone.finderplus.vision.ClipTextEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** A label the learner believes applies to an item, with the similarity that produced it. */
data class LabelSuggestion(
    val label: String,
    val score: Float,
    /** True when exemplars the user supplied drove this; false for a purely zero-shot match. */
    val taught: Boolean = true,
)

/**
 * Learns from the user's own labels — **without training any model**.
 *
 * The insight is the one face recognition already relies on: the backbone stays frozen and knowledge
 * lives in the embedding space. Naming a photo stores its frozen CLIP embedding as an exemplar; the
 * running mean of a label's exemplars is its prototype; another photo close to that prototype gets the
 * label suggested. That is nearest-class-mean classification — it generalizes from one example, costs a
 * dot product per label, and needs no gradients, no backprop and no fine-tuning of a multi-GB model
 * (which is not feasible on a phone anyway).
 *
 * Rejections are kept too: a "no" is pushed into a negative prototype, so the label learns its own
 * boundary instead of the feedback being thrown away.
 *
 * On top of that, each label carries a **text prior**: the CLIP text embedding of its own name. Because
 * the text and image towers share one space, "bicycle" is a usable classifier the instant it is named,
 * with zero examples — that is the backbone's world knowledge, not something learned here. The prior is
 * blended with the user's exemplars at weight `1/(1+n)`, so a label starts as the generic concept and
 * becomes *this user's* version of it as examples accumulate. It never stops being anchored, which is
 * what stops a single mislabelled photo from redefining a class.
 */
@Singleton
class LabelLearner @Inject constructor(
    private val db: FinderDatabase,
    private val clipText: ClipTextEncoder,
) {

    /**
     * Teach [labels] from item [itemId]'s image embedding.
     *
     * Returns false when the item has no usable embedding — which includes the degenerate all-zero
     * vector a stubbed encoder produces. That guard matters: a zero vector is "similar" to everything,
     * so learning from one would make every label match every photo.
     */
    suspend fun learn(itemId: Long, labels: List<String>): Boolean {
        val vec = pooledEmbeddingFor(itemId) ?: return false
        for (label in labels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
            val existing = db.labelPrototypeDao().byLabel(label)
            val merged = if (existing == null) {
                vec
            } else {
                val c = Vecs.fromBytes(existing.centroid)
                if (c.size != vec.size) vec
                else Vecs.normalized(FloatArray(vec.size) { i ->
                    (c[i] * existing.exemplar_count + vec[i]) / (existing.exemplar_count + 1)
                })
            }
            db.labelPrototypeDao().upsert(
                LabelPrototypeEntity(
                    id = existing?.id ?: 0,
                    label = label,
                    centroid = Vecs.toBytes(merged),
                    exemplar_count = (existing?.exemplar_count ?: 0) + 1,
                    negative_centroid = existing?.negative_centroid,
                    negative_count = existing?.negative_count ?: 0,
                    updated_at = System.currentTimeMillis(),
                    // Compute the prior once, on first sight of the label, and never overwrite it —
                    // it is the anchor the exemplars are allowed to drift away from, not a running value.
                    text_prior = existing?.text_prior ?: textPriorFor(label, vec.size)?.let { Vecs.toBytes(it) },
                    origin = LabelPrototypeEntity.ORIGIN_TAUGHT,
                )
            )
        }
        return true
    }

    /** Record that [label] does NOT apply to [itemId], sharpening the label's boundary. */
    suspend fun reject(itemId: Long, label: String): Boolean {
        val vec = pooledEmbeddingFor(itemId) ?: return false
        val key = label.trim().lowercase()
        val existing = db.labelPrototypeDao().byLabel(key) ?: return false
        val neg = existing.negative_centroid?.let { Vecs.fromBytes(it) }
        val merged = if (neg == null || neg.size != vec.size) vec else Vecs.normalized(
            FloatArray(vec.size) { i -> (neg[i] * existing.negative_count + vec[i]) / (existing.negative_count + 1) }
        )
        db.labelPrototypeDao().upsert(
            existing.copy(
                negative_centroid = Vecs.toBytes(merged),
                negative_count = existing.negative_count + 1,
                updated_at = System.currentTimeMillis(),
            )
        )
        return true
    }

    /**
     * Labels this item probably deserves, from two heads that are scored **separately** and only then
     * merged.
     *
     * They must stay separate because their similarities live on different scales, measured on this
     * device's own gallery: image-to-image cosine runs ~0.6-0.9, while text-to-image tops out around
     * 0.31 even for an unmistakable match ("a screenshot of a text conversation" scored 0.315 against
     * actual chat screenshots). One shared cutoff cannot serve both — set it for images and every
     * zero-shot label is silently discarded; set it for text and every image prototype fires on
     * everything. So each head is calibrated in its own units and both report a comparable confidence:
     *
     *  - **taught** labels (≥1 exemplar): cosine against the prototype, thresholded directly.
     *  - **seeded** labels (no exemplars): softmax over the whole vocabulary, which is how CLIP
     *    zero-shot classification is defined — what matters is that "cat" beats the other candidates,
     *    not that it clears some absolute number.
     */
    suspend fun suggest(itemId: Long, threshold: Float = DEFAULT_THRESHOLD, limit: Int = 5): List<LabelSuggestion> {
        val vecs = embeddingsFor(itemId)
        if (vecs.isEmpty()) return emptyList()
        val prototypes = db.labelPrototypeDao().all()
        val out = ArrayList<LabelSuggestion>()

        // A taught label asks "is my cat in here?", so a video answers yes if ANY frame does — the two
        // heads below aggregate differently on purpose, because they are answering different questions.
        for (p in prototypes) {
            if (p.exemplar_count <= 0) continue
            // The bar scales with evidence — see [taughtThreshold] for the incident that forced it.
            val bar = taughtThreshold(threshold, p.exemplar_count)
            var best = Float.NEGATIVE_INFINITY
            for (vec in vecs) {
                val c = effectiveVector(p, vec.size) ?: continue
                val pos = Vecs.dot(c, vec)
                if (pos < bar) continue
                if (pos <= negativeScore(p, vec)) continue
                if (pos > best) best = pos
            }
            if (best > Float.NEGATIVE_INFINITY) out += LabelSuggestion(p.label, best, taught = true)
        }

        // Zero-shot head. Scoring the softmax over the seeds only (not the taught labels) keeps the
        // distribution meaningful: mixing in prototypes on a different scale would let them dominate
        // the normalization and flatten every genuine zero-shot match to near zero.
        //
        // Unlike the taught head this averages over frames rather than taking the best, because it is
        // describing the item rather than detecting something in it. See ConceptClassifier.read.
        val seeds = prototypes.filter { it.exemplar_count <= 0 }
        if (seeds.isNotEmpty()) {
            val totals = FloatArray(seeds.size)
            for (vec in vecs) {
                val logits = FloatArray(seeds.size)
                for (i in seeds.indices) {
                    val prior = seeds[i].text_prior?.let { Vecs.fromBytes(it) }?.takeIf { it.size == vec.size }
                    logits[i] = if (prior == null) Float.NEGATIVE_INFINITY else LOGIT_SCALE * Vecs.dot(prior, vec)
                }
                val max = logits.max()
                if (max == Float.NEGATIVE_INFINITY) continue
                var sum = 0.0
                val exp = DoubleArray(seeds.size)
                for (i in logits.indices) {
                    exp[i] = if (logits[i] == Float.NEGATIVE_INFINITY) 0.0
                             else kotlin.math.exp((logits[i] - max).toDouble())
                    sum += exp[i]
                }
                if (sum <= 0.0) continue
                for (i in seeds.indices) {
                    if (negativeScore(seeds[i], vec) > 0.9f) continue
                    totals[i] += (exp[i] / sum).toFloat()
                }
            }
            for (i in seeds.indices) {
                val prob = totals[i] / vecs.size
                if (prob < ZERO_SHOT_MIN_PROB) continue
                out += LabelSuggestion(seeds[i].label, prob, taught = false)
            }
        }

        return out.sortedByDescending { it.score }.take(limit)
    }

    /** How close this item sits to what the label has been told it is *not*. */
    private fun negativeScore(p: LabelPrototypeEntity, vec: FloatArray): Float =
        p.negative_centroid
            ?.let { Vecs.fromBytes(it) }
            ?.takeIf { it.size == vec.size }
            ?.let { Vecs.dot(it, vec) } ?: -1f

    /**
     * Seed [labels] as zero-shot prototypes carrying only their text prior — no exemplars.
     *
     * This is the "already knows the world" layer: it makes the app able to say *bicycle*, *beach*,
     * *receipt* on a gallery it has never been taught anything about. A label the user later teaches
     * simply gains exemplars and flips to [LabelPrototypeEntity.ORIGIN_TAUGHT]; nothing is duplicated.
     *
     * @return how many prototypes were newly created.
     */
    suspend fun seedVocabulary(
        labels: List<String>,
        dim: Int,
        isStopRequested: suspend () -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        // Skip what is already seeded first: resuming then costs nothing, because the prototype table
        // is itself the checkpoint — there is no separate cursor that could disagree with it.
        val pending = labels
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filter { db.labelPrototypeDao().byLabel(it) == null }
        if (pending.isEmpty()) return 0

        var created = 0
        var done = 0
        for (chunk in pending.chunked(LABELS_PER_BATCH)) {
            if (isStopRequested()) break
            // One inference for the whole chunk. Encoding is dominated by per-call overhead, so
            // `chunk.size * PROMPTS.size` sequences cost barely more than one — the difference between
            // a vocabulary this size taking minutes and taking hours.
            val texts = chunk.flatMap { label -> PROMPTS.map { it.format(label) } }
            val encoded = clipText.encodeBatch(texts)
            if (encoded.size != texts.size) break

            for ((i, label) in chunk.withIndex()) {
                val prior = averagePrior(encoded, i * PROMPTS.size, PROMPTS.size, dim) ?: continue
                db.labelPrototypeDao().upsert(
                    LabelPrototypeEntity(
                        label = label,
                        // With no exemplars the prior *is* the prototype; the blend in
                        // [effectiveVector] keeps it that way until the first example arrives.
                        centroid = Vecs.toBytes(prior),
                        exemplar_count = 0,
                        negative_centroid = null,
                        negative_count = 0,
                        updated_at = System.currentTimeMillis(),
                        text_prior = Vecs.toBytes(prior),
                        origin = LabelPrototypeEntity.ORIGIN_SEED,
                    )
                )
                created++
            }
            done += chunk.size
            onProgress(done, pending.size)
        }
        return created
    }

    /** Mean of one label's prompt encodings, re-normalized. Null when the text tower produced zeros. */
    private fun averagePrior(all: List<FloatArray>, from: Int, count: Int, dim: Int): FloatArray? {
        val sum = FloatArray(dim)
        var used = 0
        for (i in from until from + count) {
            val v = all.getOrNull(i) ?: continue
            if (v.size != dim || v.all { it == 0f }) continue
            for (j in 0 until dim) sum[j] += v[j]
            used++
        }
        return if (used == 0) null else Vecs.normalized(sum)
    }

    /**
     * The vector a label is actually matched by: its text prior shrunk toward its exemplar mean.
     *
     * Weight `1/(1+n)` is deliberate. At n=0 the label is pure world knowledge and already usable; at
     * n=1 it is half the user's; by n=10 the user's examples dominate but the concept is still tethered,
     * so one bad exemplar cannot capture the class.
     */
    private fun effectiveVector(p: LabelPrototypeEntity, dim: Int): FloatArray? {
        val centroid = Vecs.fromBytes(p.centroid).takeIf { it.size == dim } ?: return null
        val prior = p.text_prior?.let { Vecs.fromBytes(it) }?.takeIf { it.size == dim }
            ?: return Vecs.normalized(centroid)
        if (p.exemplar_count <= 0) return Vecs.normalized(prior)
        // PRIOR_STRENGTH < 1 keeps the anchor deliberately weak once real examples exist: a text
        // vector only reaches ~0.3 cosine against any image, so giving it equal weight would drag the
        // prototype off the image manifold and depress every score below the threshold.
        val alpha = PRIOR_STRENGTH / (PRIOR_STRENGTH + p.exemplar_count)
        return Vecs.normalized(FloatArray(dim) { i -> alpha * prior[i] + (1f - alpha) * centroid[i] })
    }

    /**
     * Encode a label name through the CLIP text tower, averaged over several phrasings.
     *
     * Prompt ensembling is not decoration: CLIP's text tower was trained on captions, so a bare noun
     * sits in a different part of the space than a caption does. Averaging a few natural phrasings
     * measurably improves zero-shot accuracy over using the raw word. Null when no text tower is
     * installed — an all-zero vector would be "similar" to everything and must never be stored.
     */
    private suspend fun textPriorFor(label: String, dim: Int): FloatArray? {
        val sum = FloatArray(dim)
        var used = 0
        for (template in PROMPTS) {
            val v = clipText.encode(template.format(label))
            if (v.size != dim || v.all { it == 0f }) continue
            for (i in 0 until dim) sum[i] += v[i]
            used++
        }
        if (used == 0) return null
        return Vecs.normalized(sum)
    }

    /**
     * The item's CLIP image embeddings — one for a photo, one per keyframe for a video. Degenerate
     * all-zero vectors are dropped, since they are equally similar to everything.
     */
    /**
     * One vector standing for the whole item — the item's own embedding for a photo, the mean of its
     * keyframes for a video.
     *
     * Used when teaching or rejecting a label, which needs a single exemplar. Averaging is the honest
     * choice for a video: the user is saying something about the file, and nothing in the gesture says
     * which moment they meant.
     */
    private suspend fun pooledEmbeddingFor(itemId: Long): FloatArray? {
        val vecs = embeddingsFor(itemId)
        if (vecs.isEmpty()) return null
        if (vecs.size == 1) return vecs[0]
        val dim = vecs[0].size
        val sum = FloatArray(dim)
        var used = 0
        for (v in vecs) {
            if (v.size != dim) continue
            for (i in 0 until dim) sum[i] += v[i]
            used++
        }
        if (used == 0) return null
        return Vecs.normalized(sum)
    }

    private suspend fun embeddingsFor(itemId: Long): List<FloatArray> =
        db.embeddingDao().vectorsOfKindFiltered(EmbeddingKind.IMAGE_CLIP.ordinal, listOf(itemId))
            .mapNotNull { row ->
                val vec = Vecs.fromBytes(row.vec)
                if (vec.isEmpty() || vec.all { it == 0f }) null else Vecs.normalized(vec)
            }

    companion object {
        /**
         * Cosine floor for suggesting a learned label. Conservative on purpose: a wrong suggestion the
         * user must decline is far more annoying than a missed one, and precision is what makes the
         * feature feel like it learned rather than guessed.
         */
        const val DEFAULT_THRESHOLD = 0.78f

        /**
         * The cosine a taught label must clear, raised when its prototype rests on few exemplars.
         *
         * 0.78 was calibrated as "conservative" for a mature prototype, and for one it is. For a young
         * one it is an epidemic: a user taught `cem yılmaz` on **3** screenshots, and the 3-exemplar
         * centroid — which at that size means "images that look like these screenshots" — auto-applied
         * the name to **73** items at a mean cosine of 0.80. Nothing anomalous happened in the math;
         * screenshots simply sit near 0.8 cosine to *each other* baseline, so a centroid built from
         * three of them clears 0.78 against half the gallery. Two other taught labels spread the same
         * way (71 and 63 items) in one session.
         *
         * A prototype earns the calibrated threshold by accumulating evidence: below 4 exemplars it
         * must be nearly an exact match to fire at all, below 8 still well above baseline. This is the
         * same shrinkage instinct as MIN_CORPUS in TermStats — a statistic from three samples is not
         * yet the statistic it will become.
         */
        fun taughtThreshold(base: Float, exemplarCount: Int): Float = when {
            exemplarCount >= 8 -> base
            exemplarCount >= 4 -> maxOf(base, 0.85f)
            else -> maxOf(base, 0.90f)
        }

        /**
         * CLIP's trained inverse temperature (`exp(logit_scale)` ≈ 100). Using the model's own value
         * rather than an invented one is what makes the resulting softmax an actual probability
         * instead of an arbitrarily peaked ranking.
         */
        const val LOGIT_SCALE = 100f

        /**
         * A zero-shot label must win this share of the vocabulary's probability mass. Tuned for
         * precision: an unwanted tag the user has to remove costs more than a missed one, because the
         * whole point is that the tags can be trusted without review.
         */
        const val ZERO_SHOT_MIN_PROB = 0.12f

        /** Exemplar-equivalents of weight given to the text prior. See [effectiveVector]. */
        const val PRIOR_STRENGTH = 0.5f

        /**
         * Labels encoded per inference call. Times [PROMPTS].size sequences per batch — large enough
         * that per-call overhead stops dominating, small enough that stopping stays responsive and a
         * fixed-batch export's failure is cheap to detect.
         */
        const val LABELS_PER_BATCH = 16

        /**
         * A three-phrasing slice of OpenAI's published CLIP prompt ensemble.
         *
         * The full ensemble is 80 templates, but the text tower measures ~1 s per encode on this
         * device, so each extra template costs ~5 minutes across the vocabulary for a fraction of a
         * point of accuracy. Three covers the main variation (article, framing) at a cost that fits
         * in a single background pass.
         */
        private val PROMPTS = listOf(
            "a photo of a %s.",
            "a close-up photo of a %s.",
            "%s.",
        )
    }
}
