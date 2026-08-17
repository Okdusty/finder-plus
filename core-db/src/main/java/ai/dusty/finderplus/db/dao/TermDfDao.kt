package ai.rightone.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.rightone.finderplus.db.entity.TermDfEntity

/**
 * Corpus term counts.
 *
 * The per-scope corpus size lives in the same table under an empty term. Keeping the denominator beside
 * the numerators means a partially-rebuilt table can never produce a ratio computed against the wrong
 * total — the failure mode of storing it separately.
 */
@Dao
interface TermDfDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<TermDfEntity>)

    @Query("SELECT * FROM term_df WHERE scope = :scope AND term != ''")
    suspend fun forScope(scope: Int): List<TermDfEntity>

    @Query("SELECT doc_count FROM term_df WHERE scope = :scope AND term = ''")
    suspend fun corpusSizeOrNull(scope: Int): Int?

    suspend fun corpusSize(scope: Int): Int = corpusSizeOrNull(scope) ?: 0

    @Query("DELETE FROM term_df")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM term_df WHERE term != ''")
    suspend fun termCount(): Int

    suspend fun setCorpusSize(scope: Int, size: Int) =
        insert(listOf(TermDfEntity(term = "", scope = scope, doc_count = size)))
}
