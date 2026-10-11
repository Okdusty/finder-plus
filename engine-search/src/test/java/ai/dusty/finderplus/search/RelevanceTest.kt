package ai.dusty.finderplus.search

import ai.dusty.finderplus.db.vector.ItemScore
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Test

class RelevanceTest {

    /** A matchinfo('pcnalx') blob for one phrase over one column. */
    private fun matchinfo(rows: Int, avgLen: Int, len: Int, tf: Int, docsWithTerm: Int): ByteArray {
        val v = intArrayOf(1, 1, rows, avgLen, len, tf, tf * 3, docsWithTerm)
        return ByteBuffer.allocate(v.size * 4).order(ByteOrder.nativeOrder()).also { b -> v.forEach(b::putInt) }.array()
    }

    @Test fun aLongerRowScoresLowerForTheSameTermCount() {
        val short = Relevance.bm25(matchinfo(rows = 1000, avgLen = 20, len = 10, tf = 1, docsWithTerm = 10))
        val long = Relevance.bm25(matchinfo(rows = 1000, avgLen = 20, len = 400, tf = 1, docsWithTerm = 10))
        assertThat(short).isGreaterThan(long)
    }

    @Test fun aRarerTermScoresHigher() {
        val rare = Relevance.bm25(matchinfo(rows = 1000, avgLen = 20, len = 20, tf = 1, docsWithTerm = 3))
        val common = Relevance.bm25(matchinfo(rows = 1000, avgLen = 20, len = 20, tf = 1, docsWithTerm = 600))
        assertThat(rare).isGreaterThan(common)
    }

    @Test fun repetitionSaturates() {
        val once = Relevance.bm25(matchinfo(1000, 20, 20, 1, 10))
        val ten = Relevance.bm25(matchinfo(1000, 20, 20, 10, 10))
        assertThat(ten).isGreaterThan(once)
        assertThat(ten).isLessThan(once * (Relevance.K1 + 1))
    }

    /** Unrelated items: single-vector photos and many-vector videos drawn from the same scores. */
    private fun unrelated(rnd: Random, photos: Int, videos: Int, frames: Int): List<ItemScore> {
        fun draw() = (0.20 + rnd.nextDouble(-1.0, 1.0) * 0.05).toFloat()
        return List(photos) { ItemScore(it.toLong(), draw(), 0, 0f, 1).let { s -> s.copy(mean = s.best) } } +
            List(videos) { i ->
                val f = List(frames) { draw() }
                ItemScore(10_000L + i, f.max(), 0, f.average().toFloat(), frames)
            }
    }

    @Test fun unrelatedVideosDoNotOutrankUnrelatedPhotosByHavingMoreFrames() {
        val rnd = Random(3)
        val items = unrelated(rnd, photos = 400, videos = 100, frames = 12)
        val all = items.flatMap { s -> List(s.count) { s.best } }
        val mean = all.average().toFloat()
        val sd = kotlin.math.sqrt(all.map { (it - mean) * (it - mean) }.average()).toFloat()
        // Nothing is relevant, so nothing should stand out.
        assertThat(Relevance.fairVectorRanking(items, mean, sd, limit = 100)).isEmpty()
    }

    @Test fun aGenuineMatchIsKeptWhetherPhotoOrVideo() {
        val rnd = Random(4)
        val items = unrelated(rnd, photos = 400, videos = 100, frames = 12) +
            ItemScore(1L shl 40, 0.40f, 0, 0.40f, 1) +                 // a photo that matches
            ItemScore((1L shl 40) + 1, 0.40f, 3, 0.22f, 12)            // a video with one matching moment
        val all = items.flatMap { s -> List(s.count) { s.mean } }
        val mean = all.average().toFloat()
        val sd = 0.029f
        val kept = Relevance.fairVectorRanking(items, mean, sd, limit = 100).map { it.itemId }
        assertThat(kept).containsExactly(1L shl 40, (1L shl 40) + 1)
    }
}
