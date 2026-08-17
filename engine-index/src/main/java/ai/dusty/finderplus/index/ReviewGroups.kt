package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.model.TagSource
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of evidence grouped these items together. */
enum class GroupKind {
    /** The classifier proposed the same label for all of them. */
    CONCEPT,

    /** The same face, per identity clustering. Answering names a person. */
    PERSON,

    /** Visually near-identical media — bursts, edits, re-saves of one moment. */
    SIMILAR,
}

/** One member of a review group, with everything the UI needs to draw it. */
data class GroupMember(
    val itemId: Long,
    val uri: String,
    val displayName: String,
    val score: Float,
    /** For [GroupKind.PERSON]: the face box to crop, in the stored decode's coordinates. */
    val box: IntArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is GroupMember && other.itemId == itemId
    override fun hashCode(): Int = itemId.hashCode()
}

/**
 * A single question covering many items: "are these all <label>?"
 *
 * @param members ordered most-confident first, so the ones most likely to be right are seen first and
 *   the user can stop as soon as the group stops making sense.
 */
data class ReviewGroup(
    val kind: GroupKind,
    val label: String,
    val members: List<GroupMember>,
    /** For [GroupKind.PERSON]: every face in the cluster, including any beyond [members]. */
    val faceIds: List<Long> = emptyList(),
    val cohesion: Float = 0f,
)

/**
 * Turns the parked suggestions and the similarity clusters into **batched** questions.
 *
 * The reason this exists rather than asking per item: one answer should be worth many. A classifier that
 * proposes `person cooking` on 40 photos is making one mistake or one correct call, not 40 independent
 * ones — the evidence is a shared region of embedding space. Confirming the group teaches the prototype
 * from 40 exemplars at the cost of one decision, and rejecting it removes 40 wrong tags the same way.
 * Asking item-by-item spends the user's attention at 1/40th the rate for the same information.
 *
 * It also changes what a label *means*. A tag the user agreed to is ground truth that can seed a
 * prototype; a tag the classifier asserted is a guess. Now that auto-apply requires 0.35 confidence,
 * most of what the classifier notices arrives here instead of being written silently — which is the
 * intended trade, but only pays off if answering is cheap.
 */
@Singleton
class ReviewGroups @Inject constructor(
    private val db: FinderDatabase,
    private val clusterer: SimilarityClusterer,
    private val queue: ReviewQueue,
) {

    /**
     * Build the current question set, most useful first.
     *
     * Concept groups come before person groups because they are cheaper to answer — recognizing whether
     * 20 thumbnails are all "food" takes a glance, while deciding whether two faces are the same person
     * takes study — and cheap answers keep the loop going.
     */
    suspend fun groups(maxGroups: Int = MAX_GROUPS, maxMembers: Int = MAX_MEMBERS): List<ReviewGroup> {
        val out = ArrayList<ReviewGroup>()
        out += conceptGroups(maxGroups, maxMembers)
        if (out.size < maxGroups) out += personGroups(maxGroups - out.size, maxMembers)
        return out
    }

    /** Parked [TagSource.SUGGESTED] tags, grouped by the label they propose. */
    private suspend fun conceptGroups(maxGroups: Int, maxMembers: Int): List<ReviewGroup> {
        val pending = db.contentDao().pendingSuggestions(limit = SUGGESTION_SCAN)
        if (pending.isEmpty()) return emptyList()

        return pending.groupBy { it.label }
            // Biggest groups first: they carry the most evidence per answer. A label proposed once is a
            // worse use of a question than one proposed thirty times, however confident either is.
            .entries.sortedByDescending { it.value.size }
            .take(maxGroups)
            .mapNotNull { (label, tags) ->
                val members = tags.sortedByDescending { it.confidence }
                    .take(maxMembers)
                    .mapNotNull { tag -> member(tag.item_id, tag.confidence) }
                if (members.isEmpty()) null
                else ReviewGroup(GroupKind.CONCEPT, label, members)
            }
    }

    /**
     * Identity clusters awaiting a name.
     *
     * Labelled by size rather than by a guess. The pipeline deliberately never proposes *who* someone is
     * — face recognition gives "these are the same person", and only the user supplies the name.
     */
    private suspend fun personGroups(maxGroups: Int, maxMembers: Int): List<ReviewGroup> {
        val clusters = runCatching { clusterer.clusterFaces() }.getOrDefault(emptyList())
        if (clusters.isEmpty()) return emptyList()

        // Clustering is recomputed from embeddings each time and carries no identity of its own, so
        // "already answered" has to be read off the faces: a member assigned to a *named* person means
        // this group has been dealt with and must not be asked again.
        val faces = db.faceDao().facesWithEmbedding().associateBy { it.id }
        val namedPeople = db.faceDao().allPeople().filter { !it.name.isNullOrBlank() }.map { it.id }.toSet()

        return clusters
            .filter { c -> c.members.none { faces[it]?.cluster_id in namedPeople } }
            .sortedByDescending { it.members.size }
            .take(maxGroups)
            .mapNotNull { cluster ->
                val members = cluster.members.take(maxMembers).mapNotNull { faceId ->
                    val face = faces[faceId] ?: return@mapNotNull null
                    member(face.item_id, cluster.cohesion)?.copy(
                        box = intArrayOf(face.box_left, face.box_top, face.box_right, face.box_bottom),
                    )
                }
                if (members.size < MIN_PERSON_MEMBERS) null
                else ReviewGroup(
                    kind = GroupKind.PERSON,
                    label = "${members.size} photos of the same person",
                    members = members,
                    faceIds = cluster.members,
                    cohesion = cluster.cohesion,
                )
            }
    }

    private suspend fun member(itemId: Long, score: Float): GroupMember? {
        val row = db.mediaItemDao().byId(itemId) ?: return null
        return GroupMember(itemId, row.content_uri, row.display_name ?: "", score)
    }

    // ------------------------------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------------------------------

    /**
     * A completed answer, carrying everything needed to take it back.
     *
     * The non-obvious part is [prototypeBefore]: accepting teaches the label's prototype (centroid math
     * that is not reversible from the result alone), so the row is snapshotted *before* the answer and
     * restored verbatim on undo. That makes undo exact rather than approximate — the model genuinely
     * un-learns the answer, instead of keeping a ghost exemplar from a tap the user says was a mistake.
     */
    data class Answered(
        val group: ReviewGroup,
        val label: String? = null,
        val accepted: List<Long> = emptyList(),
        val declined: List<Long> = emptyList(),
        val prototypeBefore: ai.dusty.finderplus.db.entity.LabelPrototypeEntity? = null,
        val prototypeExisted: Boolean = false,
        val personId: Long? = null,
    )

    /**
     * Apply the user's verdicts for one concept group.
     *
     * Both answers teach. Accepting makes the item a positive exemplar of the label; declining makes it a
     * negative one, which sharpens the prototype's boundary rather than merely deleting a tag — the
     * difference between the model learning "not this" and simply forgetting it was ever asked.
     */
    suspend fun answerConcepts(label: String, accepted: List<Long>, declined: List<Long>): Answered {
        val before = db.labelPrototypeDao().byLabel(label.trim().lowercase())
        for (id in accepted) queue.accept(id, label)
        for (id in declined) queue.decline(id, label)
        return Answered(
            group = ReviewGroup(GroupKind.CONCEPT, label, emptyList()),
            label = label, accepted = accepted, declined = declined,
            prototypeBefore = before, prototypeExisted = before != null,
        )
    }

    /**
     * Reverse [a] — tags, suggestions and the prototype all return to their pre-answer state.
     *
     * The re-inserted suggestions take their scores from the group that asked the question, which is
     * where they came from in the first place.
     */
    suspend fun undo(a: Answered, memberScores: Map<Long, Float>) {
        val label = a.label
        if (label != null) {
            for (id in a.accepted) {
                db.contentDao().deleteTagRow(id, ai.dusty.finderplus.model.TagSource.USER.ordinal, label)
            }
            // Both accepted and declined had their SUGGESTED row consumed; both get it back.
            val restore = (a.accepted + a.declined).map { id ->
                ai.dusty.finderplus.db.entity.TagEntity(
                    item_id = id,
                    source = ai.dusty.finderplus.model.TagSource.SUGGESTED.ordinal,
                    label = label,
                    confidence = memberScores[id] ?: 0.2f,
                )
            }
            if (restore.isNotEmpty()) db.contentDao().insertTags(restore)
            val key = label.trim().lowercase()
            if (a.prototypeExisted) {
                a.prototypeBefore?.let { db.labelPrototypeDao().upsert(it) }
            } else {
                db.labelPrototypeDao().delete(key)
            }
            for (id in a.accepted) runCatching { ItemFinalizer(db).rebuildSearch(id) }
        }
        a.personId?.let { pid ->
            db.faceDao().clearClusterAssignments(pid)
            db.faceDao().deletePerson(pid)
        }
    }

    /**
     * Name an identity cluster, creating the person row the cluster did not have.
     *
     * Clustering is derived, not stored — it is recomputed from the face embeddings every time. The name
     * is the first thing about a person that *must* persist, so answering is what turns a transient group
     * into a durable identity: one `person` row, and every member face pointed at it.
     *
     * [reject] records the answer without a name, which still matters: it stops the same group being
     * asked again, and an unnamed person row keeps the faces grouped so a name can be added later.
     */
    suspend fun nameCluster(faceIds: List<Long>, name: String, reject: Boolean = false): Answered? {
        if (faceIds.isEmpty()) return null
        val now = System.currentTimeMillis()
        val clean = name.trim()
        val personId = db.faceDao().insertPerson(
            ai.dusty.finderplus.db.entity.PersonEntity(
                name = if (reject || clean.isEmpty()) null else clean,
                cover_face_id = faceIds.first(),
                centroid = null,
                face_count = faceIds.size,
                updated_at = now,
            )
        )
        for (faceId in faceIds) db.faceDao().setCluster(faceId, personId)
        return Answered(group = ReviewGroup(GroupKind.PERSON, clean, emptyList()), personId = personId)
    }

    /**
     * A machine's answer to a review question — same learning, different provenance.
     *
     * The provenance rule is the whole point of this method existing separately from the human path:
     * [ReviewQueue.accept] promotes to [TagSource.USER], which is *ground truth someone vouched for*.
     * A judge model — however strong — vouches for nothing; its yes lands as [TagSource.VLM], visibly
     * machine-made, individually removable, and never able to masquerade as something the user said.
     * (The alternative was demonstrated by accident once: 3,782 tags claiming USER provenance would
     * have been indistinguishable from real supervision.)
     *
     * Teaching still happens — a stronger model's verdict is genuine new information for the
     * prototypes, not the circular self-training that applyLearned once risked, because the judge
     * reasons from pixels rather than from the very embeddings being taught.
     */
    suspend fun judgeAnswer(itemId: Long, label: String, accept: Boolean) {
        val content = db.contentDao()
        if (accept) {
            content.insertTags(
                listOf(
                    ai.dusty.finderplus.db.entity.TagEntity(
                        item_id = itemId,
                        source = ai.dusty.finderplus.model.TagSource.VLM.ordinal,
                        label = label,
                        confidence = 1f,
                    )
                )
            )
            queue.teach(itemId, label)
        } else {
            queue.declineQuietly(itemId, label)
        }
        content.dropSuggestion(itemId, label)
    }

    /**
     * The user says this label does not belong on this item — and the correction must not stop there.
     *
     * Removing one chip fixes one photo; the *mechanism* that put it there has usually stamped the same
     * mistake across dozens (measured: a 3-exemplar prototype spread `cem yılmaz` onto 73 items). So a
     * removal does three escalating things:
     *
     *  1. **This item**: every provenance of the label is deleted and the profile rebuilt now.
     *  2. **The prototype**: the item becomes a negative exemplar, moving the label's boundary away
     *     from everything that looks like it.
     *  3. **Everything similar**: items carrying the same label from machine sources get their CONCEPTS
     *     pass requeued, so the re-run — pure arithmetic — re-judges them against the corrected
     *     prototype. The ones that only matched because they resembled this mistake lose the label;
     *     the rest keep it on their own merits.
     *
     * And when the removal retracts the label's **last USER instance**, the label itself is judged a
     * mistake: its taught prototype is deleted and its machine applications purged outright, because a
     * prototype whose every human anchor has been withdrawn has nothing left to be right about.
     *
     * @return how many other items were queued for reconsideration.
     */
    suspend fun removeLabel(itemId: Long, label: String): Int {
        val key = label.trim().lowercase()
        val content = db.contentDao()

        content.deleteLabelFromItem(itemId, label)
        queue.decline(itemId, label)   // negative exemplar + clears any pending suggestion
        runCatching { ItemFinalizer(db).rebuildSearch(itemId) }

        val proto = db.labelPrototypeDao().byLabel(key)
        if (proto != null && proto.exemplar_count > 0 && content.userCountForLabel(label) == 0) {
            db.labelPrototypeDao().delete(key)
            val purged = content.purgeMachineLabel(label)
            android.util.Log.i(TAG, "label '$label' fully retracted: prototype deleted, $purged applications purged")
            return purged
        }

        val similar = content.itemsWithLabel(
            label,
            listOf(
                ai.dusty.finderplus.model.TagSource.LEARNED.ordinal,
                ai.dusty.finderplus.model.TagSource.CONCEPT.ordinal,
                ai.dusty.finderplus.model.TagSource.SUGGESTED.ordinal,
            ),
        ).filter { it != itemId }
        if (similar.isEmpty()) return 0
        val queued = db.workUnitDao().requeueForItems(
            ai.dusty.finderplus.model.Pass.CONCEPTS.ordinal, similar, System.currentTimeMillis(),
        )
        android.util.Log.i(TAG, "label '$label' removed from item $itemId; $queued similar items queued for reconsideration")
        return queued
    }

    /** How many questions are outstanding — drives the widget badge. */
    suspend fun pendingCount(): Int = db.contentDao().pendingSuggestionCount()

    /**
     * The user types a label straight onto an item — the shortest path from "this is wrong/missing"
     * to fixed. Full USER provenance and full teaching: a typed label is the strongest supervision
     * there is, someone bothered to write it.
     */
    suspend fun addManualLabel(itemId: Long, label: String) {
        val clean = label.trim().lowercase()
        if (clean.isEmpty()) return
        db.contentDao().insertTags(
            listOf(
                ai.dusty.finderplus.db.entity.TagEntity(
                    item_id = itemId,
                    source = ai.dusty.finderplus.model.TagSource.USER.ordinal,
                    label = clean,
                    confidence = 1f,
                )
            )
        )
        queue.teach(itemId, clean)
        runCatching { ItemFinalizer(db).rebuildSearch(itemId) }
    }

    /** What the judge has applied, grouped by label — the inspection window over assisted labelling. */
    suspend fun judgedLabels(limit: Int = 24) = db.contentDao().vlmLabelCounts(limit)

    /**
     * Take back everything the judge said about one label. Machine decisions revert without ceremony:
     * the VLM rows are purged and the affected items' search rebuilt. Prototypes are left alone — the
     * judge's teaching came from a stronger model reading pixels, and reverting the *labels* (what the
     * user sees and searches) does not require pretending the evidence was never seen.
     */
    suspend fun revertJudgedLabel(label: String): Int {
        val affected = db.contentDao().itemsWithLabel(
            label, listOf(ai.dusty.finderplus.model.TagSource.VLM.ordinal),
        )
        val purged = db.contentDao().purgeVlmLabel(label)
        for (id in affected) runCatching { ItemFinalizer(db).rebuildSearch(id) }
        android.util.Log.i(TAG, "judge label '$label' reverted: $purged rows across ${affected.size} items")
        return purged
    }

    private companion object {
        const val TAG = "finderReview"

        /** Enough to be worth opening the screen for, few enough to not feel endless. */
        const val MAX_GROUPS = 12

        /** A grid the user can judge at a glance. Beyond this it becomes work rather than a glance. */
        const val MAX_MEMBERS = 24

        /** Rows scanned to form groups. Grouping needs breadth, not the whole table. */
        const val SUGGESTION_SCAN = 600

        /** A "group" of one is just an item; naming a person needs at least a pair to be a claim. */
        const val MIN_PERSON_MEMBERS = 2
    }
}
