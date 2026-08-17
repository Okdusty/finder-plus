package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.model.TagSource
import javax.inject.Inject
import javax.inject.Singleton

/** How sure the system is about a proposed label, which decides whether it asks or just applies. */
enum class Confidence {
    /** Apply silently — the user is not asked. */
    HIGH,

    /** Ask. This is the band worth a question, because the answer actually changes the model. */
    REVIEW,

    /** Ignore; too weak to be worth anyone's attention. */
    LOW,
}

/** One question for the supervisor: "is this item a <label>?" */
data class ReviewItem(
    val itemId: Long,
    val label: String,
    val score: Float,
    val confidence: Confidence,
    /** Distance from the decision boundary — smaller means the answer teaches more. */
    val informativeness: Float,
)

/**
 * The supervised-learning loop, human-in-the-loop.
 *
 * Two deliberate design choices:
 *
 * 1. **Confidence bands, not a single cutoff.** Above [HIGH_BAND] a learned label is applied silently;
 *    between [LabelLearner.DEFAULT_THRESHOLD] and [HIGH_BAND] it becomes a question; below, it is
 *    dropped. So the user is only interrupted where their answer is actually needed.
 *
 * 2. **Uncertainty sampling.** Questions are ranked by closeness to the decision boundary rather than
 *    by score. An item the system is already sure about teaches nothing; the ambiguous ones move the
 *    prototype most per answer. This is why a handful of answers can be worth hundreds of random ones.
 *
 * Answers feed straight back into [LabelLearner] — accepted items become positive exemplars, declined
 * ones negative — so precision improves with use without any model being retrained.
 */
@Singleton
class ReviewQueue @Inject constructor(
    private val db: FinderDatabase,
    private val learner: LabelLearner,
    private val labeler: MediaLabeler,
) {

    /**
     * Build the next batch of questions. Scans recently-indexed items that carry an embedding and have
     * no user label yet, scores them against every learned prototype, and returns the most informative.
     */
    suspend fun nextQuestions(limit: Int = 20, scanLimit: Int = 400): List<ReviewItem> {
        // Suggestions the concept pass already parked come first: they are the classifier's own
        // "plausible but not confident" band, already scored and already attached to an item, so they are
        // both cheaper and more relevant than re-scanning for prototype near-misses.
        val parked = db.contentDao().pendingSuggestions(limit).map {
            ReviewItem(
                itemId = it.item_id,
                label = it.label,
                score = it.confidence,
                confidence = Confidence.REVIEW,
                informativeness = -kotlin.math.abs(it.confidence - BOUNDARY),
            )
        }
        if (parked.size >= limit) return parked.sortedByDescending { it.informativeness }

        if (db.labelPrototypeDao().count() == 0) return parked // nothing learned yet
        val out = ArrayList<ReviewItem>()
        val candidates = db.mediaItemDao().itemsWithEmbeddingNoUserLabel(scanLimit)
        for (id in candidates) {
            for (s in learner.suggest(id, threshold = LabelLearner.DEFAULT_THRESHOLD, limit = 3)) {
                val band = bandFor(s.score)
                if (band == Confidence.LOW) continue
                out += ReviewItem(
                    itemId = id,
                    label = s.label,
                    score = s.score,
                    confidence = band,
                    informativeness = -kotlin.math.abs(s.score - BOUNDARY),
                )
            }
        }
        // Auto-apply the confident ones; only genuinely uncertain items become questions.
        val (auto, ask) = out.partition { it.confidence == Confidence.HIGH }
        for (a in auto) applyLearned(a.itemId, a.label)
        return (parked + ask).sortedByDescending { it.informativeness }.take(limit)
    }

    /** Supervisor says yes: the label becomes a real user label and a new positive exemplar. */
    suspend fun accept(itemId: Long, label: String) {
        val existing = labeler.userLabels(itemId)
        labeler.setLabels(itemId, existing + label)
        // The suggestion has been answered; leaving it queued would ask again forever.
        db.contentDao().dropSuggestion(itemId, label)
    }

    /** Supervisor says no: recorded as a negative exemplar so the same mistake is not repeated. */
    suspend fun decline(itemId: Long, label: String) {
        learner.reject(itemId, label)
        db.contentDao().dropSuggestion(itemId, label)
    }

    /** Positive exemplar only — no USER tag. The judge path, where provenance stays machine. */
    suspend fun teach(itemId: Long, label: String) {
        learner.learn(itemId, listOf(label))
    }

    /** Negative exemplar only — suggestion handling is the caller's. */
    suspend fun declineQuietly(itemId: Long, label: String) {
        learner.reject(itemId, label)
    }

    /** A high-confidence match is written with its own provenance, so it stays distinguishable. */
    private suspend fun applyLearned(itemId: Long, label: String) {
        runCatching {
            db.contentDao().insertTags(
                listOf(
                    ai.dusty.finderplus.db.entity.TagEntity(
                        item_id = itemId, source = TagSource.LEARNED.ordinal, label = label, confidence = 1f,
                    )
                )
            )
            ItemFinalizer(db).rebuildSearch(itemId)
        }
    }

    private fun bandFor(score: Float): Confidence = when {
        score >= HIGH_BAND -> Confidence.HIGH
        score >= LabelLearner.DEFAULT_THRESHOLD -> Confidence.REVIEW
        else -> Confidence.LOW
    }

    companion object {
        /** At or above this cosine the match is applied without asking. */
        const val HIGH_BAND = 0.90f

        /** Midpoint of the review band — the most informative place to ask. */
        const val BOUNDARY = (HIGH_BAND + LabelLearner.DEFAULT_THRESHOLD) / 2f
    }
}
