package ai.dusty.finderplus.index

import android.graphics.Bitmap
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.LabelQuestionEntity
import ai.dusty.finderplus.db.entity.TagEntity
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.model.EmbeddingKind
import ai.dusty.finderplus.model.RequiredModel
import ai.dusty.finderplus.model.Tag
import ai.dusty.finderplus.model.TagSource
import ai.dusty.finderplus.vision.ClipTextEncoder
import ai.dusty.finderplus.vision.DetectedRegion
import ai.dusty.finderplus.vision.DetectionFilter
import ai.dusty.finderplus.vision.ImageAnalyzer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One CLIP vector per item: the photo's own, or the mean of a video's keyframes. */
internal object ClipVectors {
    /** Stays under SQLite's bound-parameter limit. */
    private const val SQL_CHUNK = 500

    suspend fun pooled(db: FinderDatabase, itemIds: Collection<Long>): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>()
        for (chunk in itemIds.distinct().chunked(SQL_CHUNK)) {
            val rows = db.embeddingDao().vectorsOfKindFiltered(EmbeddingKind.IMAGE_CLIP.ordinal, chunk)
            for ((id, rs) in rows.groupBy { it.item_id }) {
                val vs = rs.map { Vecs.fromBytes(it.vec) }.filter { v -> v.isNotEmpty() && v.any { it != 0f } }
                if (vs.isEmpty() || vs.any { it.size != vs[0].size }) continue
                out[id] = mean(vs)
            }
        }
        return out
    }

    fun mean(vs: List<FloatArray>): FloatArray {
        val sum = FloatArray(vs[0].size)
        for (v in vs) for (i in sum.indices) sum[i] += v[i]
        return Vecs.normalized(sum)
    }

    /** The model of the user's answers on one label, from its most recent answers that have vectors. */
    fun answerModel(answers: List<LabelQuestionEntity>, vectors: Map<Long, FloatArray>): AnswerModel? {
        val recent = answers.filter { it.verdict != null }.sortedByDescending { it.updated_at }.take(AnswerModel.MAX_ANSWERS)
        return AnswerModel.fit(
            recent.filter { it.verdict == LabelQuestionEntity.YES }.mapNotNull { vectors[it.item_id] },
            recent.filter { it.verdict == LabelQuestionEntity.NO }.mapNotNull { vectors[it.item_id] },
        )
    }
}

/**
 * Index-time half of the object vote queue: decides, per detected label, whether it is asserted, held
 * out of search until the user answers, or asserted and asked about. The reasons are [Doubt]s.
 *
 * A label the user already answered is never re-decided: "no" suppresses it, "yes" is carried by the
 * user's own tag.
 */
class DetectionReview(
    private val db: FinderDatabase,
    private val clipImage: ImageAnalyzer,
    private val clipText: ClipTextEncoder,
    private val coordinator: ModelCoordinator,
) {
    data class Outcome(
        val tags: List<Tag>,
        val questions: List<LabelQuestionEntity>,
        /** CLIP vector of each label's region: its box crop, or the whole image for a whole-frame box. */
        val regions: Map<String, FloatArray>,
    )

    /** CLIP text priors by object name; names are added as the describe model first uses them. */
    private val priors = HashMap<String, FloatArray>()
    private val priorsLock = Mutex()

    /** Answer models by label, with the answers they were fitted on; refitted when those change. */
    private val models = HashMap<String, Pair<List<LabelQuestionEntity>, AnswerModel?>>()
    private val modelsLock = Mutex()

    private suspend fun answerModel(label: String, answers: List<LabelQuestionEntity>): AnswerModel? = modelsLock.withLock {
        models[label]?.takeIf { it.first == answers }?.let { return@withLock it.second }
        val regions = db.objectRegionDao().forItems(label, answers.map { it.item_id })
            .associate { it.item_id to Vecs.fromBytes(it.vec) }
        val model = ClipVectors.answerModel(answers, regions)
        models[label] = answers to model
        model
    }

    suspend fun review(itemId: Long, bitmap: Bitmap, regions: List<DetectedRegion>): Outcome {
        val w = bitmap.width; val h = bitmap.height
        // One decision per label, on its strongest instance; an ordinary box backs a label over a whole-frame one.
        val best = regions.groupBy { it.label }.values.map { rs ->
            rs.filterNot { DetectionFilter.isWholeFrame(it, w, h) }.maxByOrNull { it.confidence } ?: rs.maxBy { it.confidence }
        }
        if (best.isEmpty()) return Outcome(emptyList(), emptyList(), emptyMap())

        val dao = db.labelQuestionDao()
        val answeredHere = dao.forItem(itemId).filter { it.verdict != null }.map { it.label }.toSet()
        val history = dao.answeredFor(best.map { it.label }).groupBy { it.label }
        val vocabulary = (db.contentDao().objectLabels() + best.map { it.label }).distinct()
        val itemVector = ClipVectors.pooled(db, listOf(itemId))[itemId]
        val now = System.currentTimeMillis()
        val tags = ArrayList<Tag>()
        val questions = ArrayList<LabelQuestionEntity>()
        val regions = HashMap<String, FloatArray>()

        for (r in best) {
            if (r.label in answeredHere) continue
            val doubts = LinkedHashSet<Doubt>()
            var alternative: String? = null
            val wholeFrame = DetectionFilter.isWholeFrame(r, w, h)
            if (wholeFrame) doubts += Doubt.WHOLE_FRAME
            val answers = history[r.label].orEmpty().map { LabelDoubt.Answer(it.confidence, it.verdict == LabelQuestionEntity.YES) }
            if (r.confidence < LabelDoubt.learnedCeiling(answers)) doubts += Doubt.LOW_CONFIDENCE
            val region = if (wholeFrame) itemVector else regionVector(bitmap, r)
            region?.let { regions[r.label] = it }
            if (!wholeFrame && region != null) {
                clipReading(region, r.label, vocabulary)?.let { (rank, top) ->
                    if (rank >= LabelDoubt.DISAGREE_RANK) { doubts += Doubt.DISAGREE; alternative = top }
                }
            }
            // The user's own answers on this label outweigh the generic checks, in both directions.
            val p = region?.let { v -> answerModel(r.label, history[r.label].orEmpty())?.probability(v) }
            if (p != null && p < 0.5f) doubts += Doubt.ANSWERS
            if (p != null && p >= AnswerModel.CONFIDENT_YES) { doubts.clear(); alternative = null }
            val held = doubts.any { it in Doubt.CONTRADICTING }
            tags += Tag(itemId, if (held) TagSource.OBJECT_REVIEW else TagSource.OBJECT, r.label, r.confidence)
            if (doubts.isNotEmpty()) {
                questions += LabelQuestionEntity(itemId, r.label, r.confidence, Doubt.mask(doubts), alternative, null, now)
            }
        }
        return Outcome(tags, questions, regions)
    }

    /** CLIP vector of a crop of [r]'s box, or null when CLIP is not installed or the box is too small to read. */
    private suspend fun regionVector(bitmap: Bitmap, r: DetectedRegion): FloatArray? {
        val rect = LabelDoubt.cropRect(r.left, r.top, r.right, r.bottom, bitmap.width, bitmap.height) ?: return null
        val crop = Bitmap.createBitmap(bitmap, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        val v = try {
            coordinator.withModel(RequiredModel.CLIP_IMG) { clipImage.embed(crop) }
        } finally {
            // createBitmap may hand back the source itself, which belongs to the shared decode cache.
            if (crop !== bitmap) crop.recycle()
        }
        return if (v.isEmpty() || v.all { it == 0f }) null else Vecs.normalized(v)
    }

    /**
     * How CLIP ranks [label] among every object name in [vocabulary] for a region vector, and which
     * name CLIP itself puts first. Null when the text tower is missing.
     */
    private suspend fun clipReading(region: FloatArray, label: String, vocabulary: List<String>): Pair<Int, String>? {
        if (vocabulary.size < 2) return null
        val names = priorsFor(vocabulary) ?: return null
        val labels = names.keys.toList()
        val scores = FloatArray(labels.size) { i -> names.getValue(labels[i]).takeIf { it.size == region.size }?.let { Vecs.dot(region, it) } ?: Float.NEGATIVE_INFINITY }
        val index = labels.indexOf(label).takeIf { it >= 0 } ?: return null
        val top = scores.indices.maxBy { scores[it] }
        return LabelDoubt.rank(scores, index) to labels[top]
    }

    /** Text priors for [names], encoding the ones not seen before with the same prompts as learned labels. */
    private suspend fun priorsFor(names: List<String>): Map<String, FloatArray>? = priorsLock.withLock {
        val missing = names.filter { it !in priors }
        if (missing.isNotEmpty()) {
            val prompts = LabelLearner.PROMPTS
            for (chunk in missing.chunked(LabelLearner.LABELS_PER_BATCH)) {
                val texts = chunk.flatMap { c -> prompts.map { it.format(c) } }
                val encoded = clipText.encodeBatch(texts)
                // An absent text tower encodes to zeros; storing those would make every name look alike.
                if (encoded.size != texts.size || encoded.any { v -> v.all { it == 0f } }) return@withLock null
                chunk.forEachIndexed { i, name ->
                    val sum = FloatArray(encoded[i * prompts.size].size)
                    for (j in prompts.indices) encoded[i * prompts.size + j].forEachIndexed { k, x -> sum[k] += x }
                    priors[name] = Vecs.normalized(sum)
                }
            }
        }
        names.associateWith { priors.getValue(it) }
    }
}

/**
 * Review-time half of the object vote queue: measures [Doubt.OUTLIER] across the index, turns pending
 * questions into review groups, and applies the user's answers.
 *
 * Answers are kept in `label_question` rather than only in tags, because a re-index rewrites tags.
 * "Yes" becomes a USER tag; "no" removes the label and keeps it from coming back. Neither teaches the
 * concept prototypes: the answers already tune the detector's labels through the learned confidence cut
 * and the rejected-photos comparison, and a prototype taught from a detector label spreads it by
 * similarity to photos the detector never flagged.
 */
/**
 * Which questions the user wants to see. Low confidence alone is off by default: a label the model was
 * merely unsure of is often right, so asking about every one spends attention without evidence of a
 * mistake. Those questions are still recorded, so turning this on shows them without re-indexing.
 */
@Singleton
class QuestionPrefs @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context) {
    var askLowConfidence: Boolean
        get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.ASK_UNCERTAIN]
        set(value) { ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.ASK_UNCERTAIN] = value }

    init {
        // The switch now lives in the settings; carry over what was chosen before it did.
        val old = context.getSharedPreferences("finder-questions", android.content.Context.MODE_PRIVATE)
        if (old.contains("ask_low_confidence")) {
            askLowConfidence = old.getBoolean("ask_low_confidence", false)
            old.edit().remove("ask_low_confidence").apply()
        }
    }

    val shownReasons: Int get() = Doubt.shown(askLowConfidence)
}

@Singleton
class ObjectQuestions @Inject constructor(
    private val db: FinderDatabase,
    private val prefs: QuestionPrefs,
) {

    /** What an answer changed, so it can be undone exactly. */
    data class Snapshot(
        val label: String,
        val itemIds: List<Long>,
        val questions: List<LabelQuestionEntity>,
        val tags: List<TagEntity>,
    )

    suspend fun pendingCount(): Int = db.labelQuestionDao().pendingCount(prefs.shownReasons)

    /**
     * Re-measure the review-time doubts for every detector label in the index, after the index or the
     * user's answers changed:
     *  - [Doubt.OUTLIER]: the photo's CLIP vector is unusually far from the other photos with the label;
     *  - [Doubt.ANSWERS]: the model of the user's answers on the label, comparing detected regions,
     *    predicts a no.
     * When that model is confident of a yes, the question is dropped altogether. Each label is then held
     * out of search or released to match the reasons it is left with.
     *
     * @return how many items changed.
     */
    suspend fun refreshDoubts(): Int {
        val dao = db.labelQuestionDao()
        val content = db.contentDao()
        val tags = content.objectTags()
        val answered = dao.answered()
        val answeredKeys = answered.map { it.item_id to it.label }.toSet()
        val pending = dao.pending().associateBy { it.item_id to it.label }
        val vectors = ClipVectors.pooled(db, tags.map { it.item_id } + answered.map { it.item_id })

        val outliers = HashSet<Pair<Long, String>>()
        val answersNo = HashSet<Pair<Long, String>>()
        val answersYes = HashSet<Pair<Long, String>>()
        val answeredByLabel = answered.groupBy { it.label }
        for ((label, rows) in tags.groupBy { it.label }) {
            val candidates = rows.map { it.item_id }.distinct().filter { it in vectors && (it to label) !in answeredKeys }
            val regions = db.objectRegionDao().forLabel(label).associate { it.item_id to Vecs.fromBytes(it.vec) }
            val confirmed = answeredByLabel[label].orEmpty()
                .filter { it.verdict == LabelQuestionEntity.YES }.map { it.item_id }.filter { it in vectors }
            val population = candidates + confirmed
            if (population.size >= 2) {
                val centroid = ClipVectors.mean(population.map { vectors.getValue(it) })
                val sims = FloatArray(population.size) { Vecs.dot(vectors.getValue(population[it]), centroid) }
                for (i in LabelDoubt.lowOutliers(sims)) if (i < candidates.size) outliers += candidates[i] to label
            }
            val model = ClipVectors.answerModel(answeredByLabel[label].orEmpty(), regions) ?: continue
            for (id in candidates) {
                val p = model.probability(regions[id] ?: continue)
                if (p < 0.5f) answersNo += id to label else if (p >= AnswerModel.CONFIDENT_YES) answersYes += id to label
            }
        }

        val now = System.currentTimeMillis()
        val changed = HashSet<Long>()
        val reviewTime = Doubt.OUTLIER.bit or Doubt.ANSWERS.bit
        for (tag in tags) {
            val key = tag.item_id to tag.label
            if (key in answeredKeys) continue
            val q = pending[key]
            val before = q?.reasons ?: 0
            var after = before and reviewTime.inv()
            if (key in outliers) after = after or Doubt.OUTLIER.bit
            if (key in answersNo) after = after or Doubt.ANSWERS.bit
            if (key in answersYes) after = 0
            if (after != before) when {
                after == 0 -> dao.delete(key.first, key.second)
                q != null -> dao.upsert(listOf(q.copy(reasons = after, updated_at = now)))
                else -> dao.upsert(listOf(LabelQuestionEntity(key.first, key.second, tag.confidence, after, null, null, now)))
            }
            val target = if (Doubt.of(after).any { it in Doubt.CONTRADICTING }) TagSource.OBJECT_REVIEW else TagSource.OBJECT
            if (tag.source != target.ordinal) {
                content.moveTag(key.first, key.second, tag.source, target.ordinal)
                changed += key.first
            }
        }
        for (id in changed) runCatching { ItemFinalizer(db).rebuildSearch(id) }
        return changed.size
    }

    /**
     * Pending questions as one group per label. Questions with more evidence against the label come
     * first, within a group and across groups; among equals, the less confident detection first.
     */
    suspend fun groups(maxGroups: Int, maxMembers: Int): List<ReviewGroup> {
        val order = compareByDescending<LabelQuestionEntity> { q -> Doubt.of(q.reasons).count { it in Doubt.CONTRADICTING } }
            .thenByDescending { Doubt.of(it.reasons).size }
            .thenBy { it.confidence }
        val shown = prefs.shownReasons
        val rules = ai.dusty.finderplus.settings.LabelRules.current()
        return db.labelQuestionDao().pending()
            // A label the user hid is not worth asking about.
            .filter { it.reasons and shown != 0 && ai.dusty.finderplus.settings.LabelRules.apply(it.label, rules) != null }
            .groupBy { it.label }
            .values.map { it.sortedWith(order) }
            .sortedWith { a, b -> order.compare(a.first(), b.first()) }
            .take(maxGroups)
            .mapNotNull { qs ->
                val shown = qs.take(maxMembers)
                val members = shown.mapNotNull { q ->
                    val row = db.mediaItemDao().byId(q.item_id) ?: return@mapNotNull null
                    val video = row.kind == ai.dusty.finderplus.model.MediaKind.VIDEO.ordinal
                    GroupMember(
                        q.item_id, row.content_uri, row.display_name ?: "", q.confidence, video = video,
                        atMs = if (video) db.objectRegionDao().timeOf(q.item_id, q.label) else null,
                    )
                }
                if (members.isEmpty()) return@mapNotNull null
                ReviewGroup(
                    kind = GroupKind.OBJECT,
                    label = qs.first().label,
                    members = members,
                    reasons = shown.flatMap { Doubt.of(it.reasons) }.groupingBy { it }.eachCount(),
                    alternatives = shown.mapNotNull { it.alternative }.groupingBy { it }.eachCount()
                        .entries.sortedByDescending { it.value }.map { it.key },
                )
            }
    }

    /** Apply the user's answers for one label. Returns what is needed to [undo] them. */
    suspend fun answer(label: String, accepted: List<Long>, declined: List<Long>): Snapshot {
        val ids = (accepted + declined).distinct()
        val dao = db.labelQuestionDao()
        val content = db.contentDao()
        val detectorSources = listOf(TagSource.OBJECT.ordinal, TagSource.OBJECT_REVIEW.ordinal)
        val snapshot = Snapshot(
            label = label,
            itemIds = ids,
            questions = dao.forItems(ids, label),
            tags = content.tagRows(ids, label, detectorSources + TagSource.USER.ordinal),
        )
        val now = System.currentTimeMillis()
        val confidence = snapshot.tags.groupBy { it.item_id }.mapValues { (_, t) -> t.maxOf { it.confidence } }
        val asked = snapshot.questions.associateBy { it.item_id }
        for (id in ids) {
            val verdict = if (id in accepted) LabelQuestionEntity.YES else LabelQuestionEntity.NO
            dao.upsert(listOf(asked[id]?.copy(verdict = verdict, updated_at = now)
                ?: LabelQuestionEntity(id, label, confidence[id] ?: 0f, 0, null, verdict, now)))
            content.deleteTagRows(id, label, detectorSources)
            if (verdict == LabelQuestionEntity.YES) {
                content.insertTags(listOf(TagEntity(item_id = id, source = TagSource.USER.ordinal, label = label, confidence = 1f)))
            }
            runCatching { ItemFinalizer(db).rebuildSearch(id) }
        }
        return snapshot
    }

    /** What [rank] changed, for [undoRank]. */
    data class RankSnapshot(val itemId: Long, val answers: List<Snapshot>, val before: List<ai.dusty.finderplus.db.entity.NameRankingEntity>)

    /**
     * Photos with open questions, one group each, with every name the models gave the photo or
     * considered for it: open questions, names already given, and what CLIP read instead. Photos with
     * the most evidence against their names come first.
     */
    suspend fun photoGroups(maxGroups: Int): List<ReviewGroup> {
        val shown = prefs.shownReasons
        val rules = ai.dusty.finderplus.settings.LabelRules.current()
        fun visible(label: String) = ai.dusty.finderplus.settings.LabelRules.apply(label, rules) != null
        val byItem = db.labelQuestionDao().pending()
            .filter { it.reasons and shown != 0 && visible(it.label) }
            .groupBy { it.item_id }
        val order = compareByDescending<List<LabelQuestionEntity>> { qs ->
            qs.sumOf { q -> Doubt.of(q.reasons).count { it in Doubt.CONTRADICTING } }
        }.thenBy { qs -> qs.minOf { it.confidence } }
        return byItem.values.sortedWith(order).take(maxGroups).mapNotNull { qs ->
            val itemId = qs.first().item_id
            val row = db.mediaItemDao().byId(itemId) ?: return@mapNotNull null
            val answered = db.labelQuestionDao().forItem(itemId).filter { it.verdict != null }.map { it.label }.toSet()
            val given = db.contentDao().tagsForItem(itemId).filter { it.source == TagSource.OBJECT.ordinal }.map { it.label }
            val candidates = (qs.map { it.label } + given + qs.mapNotNull { it.alternative })
                .distinct().filter { it !in answered && visible(it) }
            if (candidates.isEmpty()) return@mapNotNull null
            val video = row.kind == ai.dusty.finderplus.model.MediaKind.VIDEO.ordinal
            ReviewGroup(
                kind = GroupKind.RANK,
                label = row.display_name ?: "",
                members = listOf(
                    GroupMember(
                        itemId, row.content_uri, row.display_name ?: "", qs.maxOf { it.confidence }, video = video,
                        atMs = if (video) db.objectRegionDao().timeOf(itemId, qs.first().label) else null,
                    )
                ),
                reasons = qs.flatMap { Doubt.of(it.reasons) }.groupingBy { it }.eachCount(),
                candidates = candidates,
            )
        }
    }

    /**
     * The user's choice of names for one photo, [ranked] best first: each chosen name is a yes, each
     * other candidate a no, and the order is kept as group-relative rewards.
     */
    suspend fun rank(itemId: Long, candidates: List<String>, ranked: List<String>): RankSnapshot {
        val before = db.nameRankingDao().forItem(itemId)
        val answers = candidates.map { c ->
            if (c in ranked) answer(c, listOf(itemId), emptyList()) else answer(c, emptyList(), listOf(itemId))
        }
        val now = System.currentTimeMillis()
        db.nameRankingDao().replaceForItem(itemId, GroupRewards.of(candidates, ranked).map {
            ai.dusty.finderplus.db.entity.NameRankingEntity(itemId, it.label, it.rank, it.reward, it.advantage, candidates.size, now)
        })
        return RankSnapshot(itemId, answers, before)
    }

    suspend fun undoRank(s: RankSnapshot) {
        s.answers.asReversed().forEach { undo(it) }
        db.nameRankingDao().replaceForItem(s.itemId, s.before)
    }

    /** Put tags and questions back exactly as they were before [s] was answered. */
    suspend fun undo(s: Snapshot) {
        val dao = db.labelQuestionDao()
        val content = db.contentDao()
        for (id in s.itemIds) {
            content.deleteTagRows(id, s.label, listOf(TagSource.OBJECT.ordinal, TagSource.OBJECT_REVIEW.ordinal, TagSource.USER.ordinal))
            dao.delete(id, s.label)
        }
        if (s.tags.isNotEmpty()) content.insertTags(s.tags.map { it.copy(id = 0) })
        if (s.questions.isNotEmpty()) dao.upsert(s.questions)
        for (id in s.itemIds) runCatching { ItemFinalizer(db).rebuildSearch(id) }
    }

    /** Whether [label] on [itemId] is an open object question, as opposed to a concept suggestion. */
    suspend fun isAsked(itemId: Long, label: String): Boolean =
        db.labelQuestionDao().forItem(itemId).any { it.label == label && it.verdict == null }

    /**
     * The user removed a detector label by hand: record it as a "no", so a re-index cannot bring it back.
     * Must be called before the tag is deleted, while its confidence is still readable.
     */
    suspend fun recordRemoval(itemId: Long, label: String) {
        val detected = db.contentDao()
            .tagRows(listOf(itemId), label, listOf(TagSource.OBJECT.ordinal, TagSource.OBJECT_REVIEW.ordinal))
            .maxOfOrNull { it.confidence } ?: return
        val dao = db.labelQuestionDao()
        val now = System.currentTimeMillis()
        val existing = dao.forItems(listOf(itemId), label).firstOrNull()
        dao.upsert(listOf(existing?.copy(verdict = LabelQuestionEntity.NO, updated_at = now)
            ?: LabelQuestionEntity(itemId, label, detected, 0, null, LabelQuestionEntity.NO, now)))
    }

}
