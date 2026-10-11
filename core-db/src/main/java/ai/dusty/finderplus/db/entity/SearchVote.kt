package ai.dusty.finderplus.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One accumulated vote: how strongly picking history ties a query to [item_id]. [term] holds the whole
 * query's key (its words, folded and sorted); history written before votes were keyed by query holds
 * single words.
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

/**
 * A vote arrow pressed on a result for one query and not yet saved: it stays shown, can be taken back,
 * and moves the ranking only once the user saves their changes or the next index run applies them.
 * [query] is the query's vote terms, sorted and joined; [value] is 1 for up, −1 for down.
 */
@Entity(tableName = "explicit_vote", primaryKeys = ["query", "item_id"])
data class ExplicitVoteEntity(
    val query: String,
    val item_id: Long,
    val value: Int,
    val updated_at: Long,
)
