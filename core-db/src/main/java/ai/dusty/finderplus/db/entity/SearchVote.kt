package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One accumulated vote: how strongly picking history ties [term] to [item_id].
 *
 * Written implicitly — copying a result is the strongest possible "this was the right answer for
 * that query", opening is weaker, and results passed over above the chosen one shade negative.
 * There is deliberately no visible button and no per-event log: the score is the whole memory,
 * bounded on both sides so no item can buy permanent immunity from relevance.
 */
@Entity(
    tableName = "search_vote",
    primaryKeys = ["term", "item_id"],
    indices = [Index(value = ["term"])],
)
data class SearchVoteEntity(
    val term: String,
    val item_id: Long,
    val score: Float,
    val updated_at: Long,
)
