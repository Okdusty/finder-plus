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

    /**
     * Net vote per item for the given keys — the ranking-time read, one indexed query. The column is
     * named `term`, but holds the whole query's key; see `Votes.queryKey`.
     */
    @Query("SELECT item_id, SUM(score) AS score FROM search_vote WHERE term IN (:terms) GROUP BY item_id")
    suspend fun votesFor(terms: List<String>): List<ItemVote>

    /** The arrows pressed for one query, by item. */
    @Query("SELECT * FROM explicit_vote WHERE query = :query")
    suspend fun explicitFor(query: String): List<ai.dusty.finderplus.db.entity.ExplicitVoteEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun setExplicit(vote: ai.dusty.finderplus.db.entity.ExplicitVoteEntity)

    @Query("DELETE FROM explicit_vote WHERE query = :query AND item_id = :itemId")
    suspend fun clearExplicit(query: String, itemId: Long)

    @Query("SELECT * FROM explicit_vote ORDER BY updated_at")
    suspend fun allExplicit(): List<ai.dusty.finderplus.db.entity.ExplicitVoteEntity>

    @Query("SELECT COUNT(*) FROM explicit_vote")
    suspend fun explicitCount(): Int

    @Query("DELETE FROM explicit_vote")
    suspend fun clearAllExplicit()

    /** Forget all pick history. */
    @Query("DELETE FROM search_vote")
    suspend fun clearAllVotes()

    /** Every accumulated vote, for backups and training exports. */
    @Query("SELECT * FROM search_vote")
    suspend fun allVotes(): List<ai.dusty.finderplus.db.entity.SearchVoteEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putVotes(votes: List<ai.dusty.finderplus.db.entity.SearchVoteEntity>)
}

data class ItemVote(val item_id: Long, val score: Float)
