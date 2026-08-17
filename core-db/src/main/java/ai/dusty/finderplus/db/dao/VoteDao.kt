package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Implicit relevance feedback. See [ai.dusty.finderplus.db.entity.SearchVoteEntity].
 *
 * **Only the user votes.** The sole legitimate writer is the search UI relaying a human gesture
 * (copy / open / preview / passed-over). No indexing pass, no judge, no AI process may ever write
 * here — a model voting on rankings would be the ranking training itself on its own opinions, the
 * same forged-supervision failure the tag layer guards against with [TagSource.USER] vs VLM
 * provenance. Enforced by `VoteProvenanceTest`, which fails the build if any engine or AI module
 * so much as references this DAO.
 */
@Dao
interface VoteDao {

    /**
     * Accumulate one vote, clamped to [min], [max] — bounds are enforced at write time so the table
     * itself can never hold a runaway score, no matter how often something is tapped.
     */
    @Query(
        """
        INSERT INTO search_vote(term, item_id, score, updated_at)
        VALUES (:term, :itemId, MAX(:min, MIN(:max, :delta)), :now)
        ON CONFLICT(term, item_id)
        DO UPDATE SET score = MAX(:min, MIN(:max, score + :delta)), updated_at = :now
        """
    )
    suspend fun accumulate(term: String, itemId: Long, delta: Float, now: Long, min: Float, max: Float)

    /** Net vote per item across the query's terms — the ranking-time read, one indexed query. */
    @Query("SELECT item_id, SUM(score) AS score FROM search_vote WHERE term IN (:terms) GROUP BY item_id")
    suspend fun votesFor(terms: List<String>): List<ItemVote>
}

data class ItemVote(val item_id: Long, val score: Float)
