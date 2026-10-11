package ai.dusty.finderplus.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlorenceOutputTest {

    // Token ids below 1000 stand for <loc_N> with N = id; ids from 1000 up are text, decoded by name.
    private val names = mapOf(1001 to "a", 1002 to "b", 1003 to " c")
    private fun locOf(id: Int) = if (id < 1000) id else -1
    private fun text(ids: IntArray) = ids.joinToString("") { names.getValue(it) }

    @Test fun eachLabelTakesTheBoxesAfterIt() {
        val ids = intArrayOf(1001, 0, 0, 499, 499, 1002, 1003, 500, 500, 999, 999, 0, 500, 499, 999)
        val probs = FloatArray(ids.size) { 1f }.also { it[0] = 0.9f; it[5] = 0.5f; it[6] = 0.8f }
        val r = FlorenceOutput.regions(ids, probs, ::locOf, ::text, width = 1000, height = 500)
        assertThat(r.map { it.label }).containsExactly("a", "b c", "b c").inOrder()
        assertThat(r[0].confidence).isWithin(1e-6f).of(0.9f)
        assertThat(r[1].confidence).isWithin(1e-6f).of(0.4f)       // both of its text tokens
        assertThat(listOf(r[0].left, r[0].top, r[0].right, r[0].bottom)).containsExactly(0, 0, 499, 249).inOrder()
        assertThat(listOf(r[2].left, r[2].top, r[2].right, r[2].bottom)).containsExactly(0, 250, 499, 499).inOrder()
    }

    @Test fun incompleteOrEmptyBoxesAreDropped() {
        val ids = intArrayOf(1001, 10, 10, 10, 1002, 5, 5, 5, 5)   // a: 3 locs; b: zero-area box
        val r = FlorenceOutput.regions(ids, FloatArray(ids.size) { 1f }, ::locOf, ::text, 1000, 1000)
        assertThat(r).isEmpty()
    }

    @Test fun noRepeatBansTheTokenThatWouldCompleteASeenNgram() {
        assertThat(FlorenceOutput.bannedNext(listOf(1, 2, 3, 1, 2), 3)).containsExactly(3)
        assertThat(FlorenceOutput.bannedNext(listOf(1, 2, 3), 3)).isEmpty()
        assertThat(FlorenceOutput.bannedNext(listOf(7, 7), 2)).containsExactly(7)
        assertThat(FlorenceOutput.bannedNext(listOf(1, 2), 0)).isEmpty()
    }
}
