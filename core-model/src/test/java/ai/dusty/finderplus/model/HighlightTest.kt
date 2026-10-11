package ai.dusty.finderplus.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighlightTest {

    private fun shown(s: String) = s.replace(Highlight.START, '<').replace(Highlight.END, '>')

    @Test fun wordsStartingWithAQueryWordAreMarked() {
        assertThat(shown(Highlight.mark("Two docs, one dog.", listOf("do")))).isEqualTo("Two <docs>, one <dog>.")
    }

    @Test fun matchingIgnoresCaseAndKeepsTheOriginalSpelling() {
        assertThat(shown(Highlight.mark("Dog", listOf("dog")))).isEqualTo("<Dog>")
    }

    @Test fun alreadyMarkedTextIsLeftAlone() {
        val marked = "${Highlight.START}a${Highlight.END} b"
        assertThat(Highlight.mark(marked, listOf("b"))).isEqualTo(marked)
    }

    @Test fun stripRemovesOnlyTheMarks() {
        assertThat(Highlight.strip(Highlight.mark("[a] b", listOf("a")))).isEqualTo("[a] b")
    }
}
