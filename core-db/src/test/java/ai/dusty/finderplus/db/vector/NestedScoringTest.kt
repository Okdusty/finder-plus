package ai.dusty.finderplus.db.vector

import ai.dusty.finderplus.db.dao.EmbeddingDao
import ai.dusty.finderplus.db.dao.VectorRow
import ai.dusty.finderplus.db.entity.EmbeddingEntity
import ai.dusty.finderplus.model.EmbeddingKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NestedScoringTest {

    private class Rows(val rows: List<VectorRow>) : EmbeddingDao {
        override suspend fun insert(embeddings: List<EmbeddingEntity>) {}
        override suspend fun vectorsOfKind(kind: Int) = rows
        override suspend fun vectorsOfKindFiltered(kind: Int, itemIds: List<Long>) = rows.filter { it.item_id in itemIds }
        override suspend fun itemsMissingTimes(kind: Int, mediaKinds: List<Int>, minSourceRef: Int, limit: Int) = emptyList<Long>()
        override suspend fun sourceRefs(itemId: Long, kind: Int) = emptyList<Int>()
        override suspend fun setTime(itemId: Long, kind: Int, sourceRef: Int, tsMs: Long) {}
        override suspend fun clear(itemId: Long, kind: Int) {}
        override suspend fun countOfKind(kind: Int) = rows.size
    }

    private fun row(id: Long, vararg v: Float) = VectorRow(id, 0, Vecs.toBytes(Vecs.normalized(v)))

    @Test fun vectorsOfAnotherSizeAreSkippedUnlessNested() = runTest {
        val store = BruteForceVectorStore(Rows(listOf(row(1, 1f, 0f, 0f, 0f), row(2, 1f, 0f))))
        val query = Vecs.normalized(floatArrayOf(1f, 0f, 0f, 0f))
        assertThat(store.scoreItems(EmbeddingKind.TEXT, query).items.map { it.itemId }).containsExactly(1L)
        assertThat(store.scoreItems(EmbeddingKind.TEXT, query, nested = true).items.map { it.itemId }).containsExactly(1L, 2L)
    }

    @Test fun nestedVectorsCompareAtTheShorterLength() = runTest {
        // A longer stored vector is cut to the query's length, and a shorter one cuts the query to its.
        val store = BruteForceVectorStore(Rows(listOf(row(1, 0.6f, 0.8f, 5f, 5f), row(2, 0.6f, 0.8f))))
        val scores = store.scoreItems(EmbeddingKind.TEXT, Vecs.normalized(floatArrayOf(0.6f, 0.8f, 0f)), nested = true)
            .items.associate { it.itemId to it.best }
        assertThat(scores.getValue(2L)).isWithin(1e-5f).of(1f)
        // Item 1 cut to three components still leans on its large third one.
        assertThat(scores.getValue(1L)).isLessThan(scores.getValue(2L))
    }
}
