package ai.rightone.finderplus.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the two matching stages of typo correction, which are pure functions.
 *
 * The property that matters: nothing here names a language. `gızli`→`gizli` and `carr`→`car` fall out
 * of the same two rules, because the dictionary is the gallery's own vocabulary and the rules are
 * diacritic folding plus one edit.
 */
class QuerySpellerTest {

    @Test fun foldingCollapsesTurkishDiacriticsOntoAsciiForms() {
        assertThat(QuerySpeller.fold("gızli")).isEqualTo("gizli".let { QuerySpeller.fold(it) })
        assertThat(QuerySpeller.fold("dövdüm")).isEqualTo("dovdum")
        assertThat(QuerySpeller.fold("akacağız")).isEqualTo("akacagiz")
        // The Turkish dotted capital İ must not fold to a dotless surprise.
        assertThat(QuerySpeller.fold("İstanbul")).isEqualTo("istanbul")
    }

    @Test fun oneEditCoversTheClassicSlips() {
        assertThat(QuerySpeller.withinOneEdit("carr", "car")).isTrue()    // doubled key
        assertThat(QuerySpeller.withinOneEdit("cat", "car")).isTrue()     // adjacent substitution
        assertThat(QuerySpeller.withinOneEdit("ca", "car")).isTrue()      // dropped letter
        assertThat(QuerySpeller.withinOneEdit("car", "car")).isTrue()
    }

    @Test fun twoEditsAreDeliberatelyRejected() {
        // Distance 2 manufactures collisions faster than it fixes mistakes on short gallery terms.
        assertThat(QuerySpeller.withinOneEdit("caat", "cr")).isFalse()
        assertThat(QuerySpeller.withinOneEdit("kedi", "kova")).isFalse()
        assertThat(QuerySpeller.withinOneEdit("ab", "ba")).isFalse()      // transposition = 2 edits here
    }
}
