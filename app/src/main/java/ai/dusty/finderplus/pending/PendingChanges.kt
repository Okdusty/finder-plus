package ai.dusty.finderplus.pending

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.ExplicitVoteEntity
import ai.dusty.finderplus.db.entity.PendingLabelEntity
import ai.dusty.finderplus.index.ReviewGroups
import ai.dusty.finderplus.search.Votes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Vote arrows and label edits the user made, held until they save them or the next index run applies
 * them. While held they are shown as made, and nothing about ranking or labels has changed; once applied
 * they are part of the index and nothing is shown for them any more. Copying, opening and previewing
 * still count as picks at once, since they are not edits the user can see or take back.
 */
@Singleton
class PendingChanges @Inject constructor(
    private val db: FinderDatabase,
    private val reviewGroups: ReviewGroups,
) {
    data class Summary(val votes: Int, val labels: Int) {
        val any: Boolean get() = votes + labels > 0
    }

    private val _summary = MutableStateFlow(Summary(0, 0))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private val lock = Mutex()

    suspend fun refresh() {
        _summary.value = Summary(db.voteDao().explicitCount(), db.pendingLabelDao().count())
    }

    /** Which query a vote belongs to: the query as typed, as [Votes.queryKey] reads it. */
    fun voteKey(query: String): String = Votes.queryKey(query)

    suspend fun votesFor(query: String): Map<Long, Int> {
        val key = voteKey(query)
        if (key.isEmpty()) return emptyMap()
        return db.voteDao().explicitFor(key).associate { it.item_id to it.value }
    }

    /** Hold a vote for [itemId] on [query]: 1 up, −1 down, 0 none. */
    suspend fun vote(query: String, itemId: Long, value: Int) {
        val key = voteKey(query)
        if (key.isEmpty()) return
        if (value == 0) db.voteDao().clearExplicit(key, itemId)
        else db.voteDao().setExplicit(ExplicitVoteEntity(key, itemId, value, System.currentTimeMillis()))
        refresh()
    }

    /** Held label edits for one item, label to action. */
    suspend fun labelsFor(itemId: Long): Map<String, Int> =
        db.pendingLabelDao().forItem(itemId).associate { it.label to it.action }

    /** Hold a label edit; [action] null takes a held one back. */
    suspend fun editLabel(itemId: Long, label: String, action: Int?) {
        val name = label.trim()
        if (name.isEmpty()) return
        if (action == null) db.pendingLabelDao().clear(itemId, name)
        else db.pendingLabelDao().set(PendingLabelEntity(itemId, name, action, System.currentTimeMillis()))
        refresh()
    }

    /** Drop everything held. */
    suspend fun discard() = lock.withLock {
        db.voteDao().clearAllExplicit()
        db.pendingLabelDao().clearAll()
        refresh()
    }

    /**
     * Apply everything held: votes move the ranking for their own query, label edits are made as if
     * made directly. Returns how many changes were applied, and how many similar items were queued to
     * be checked again because a label was removed.
     */
    suspend fun apply(): Pair<Int, Int> = lock.withLock {
        val now = System.currentTimeMillis()
        val votes = db.voteDao().allExplicit()
        for (v in votes) {
            val reward = when (v.value) { 1 -> Votes.UP; -1 -> Votes.DOWN; else -> 0f }
            if (reward == 0f) continue
            runCatching { db.voteDao().accumulate(v.query, v.item_id, reward, now, Votes.MIN_SCORE, Votes.MAX_SCORE) }
        }
        db.voteDao().clearAllExplicit()

        var requeued = 0
        val labels = db.pendingLabelDao().all()
        for (e in labels) runCatching {
            when (e.action) {
                PendingLabelEntity.ADD -> reviewGroups.addManualLabel(e.item_id, e.label)
                PendingLabelEntity.REMOVE -> requeued += reviewGroups.removeLabel(e.item_id, e.label)
                PendingLabelEntity.ACCEPT -> reviewGroups.answerOne(e.item_id, e.label, accept = true)
                PendingLabelEntity.DECLINE -> reviewGroups.answerOne(e.item_id, e.label, accept = false)
            }
        }
        db.pendingLabelDao().clearAll()
        refresh()
        (votes.size + labels.size) to requeued
    }
}
