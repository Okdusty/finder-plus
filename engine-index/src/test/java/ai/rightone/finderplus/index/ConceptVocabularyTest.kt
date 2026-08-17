package ai.rightone.finderplus.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the vocabulary's core principle: **labels name content, never medium.** Gates may be phrased
 * as mediums ("a screenshot of a screen") because they only route; concepts become user-facing,
 * searchable tags, and a gallery filed under "screenshot of X / low quality compressed image" is
 * categorized rather than understood.
 *
 * The vocabulary is now data ([ConceptVocabulary.parse] over `assets/vocab/concepts.txt`), so these
 * assertions validate the *shipped file* rather than a compiled-in list.
 */
class ConceptVocabularyTest {

    private val vocab = ConceptVocabulary.parse(readVocabAsset())

    private val formPrefixes = listOf("screenshot of", "photo of a ", "photograph", "a photo", "picture of")

    @Test fun conceptsNeverDescribeTheMedium() {
        val offenders = vocab.domains.flatMap { it.concepts }
            .filter { c -> formPrefixes.any { c.lowercase().startsWith(it) } || c.lowercase().endsWith("photograph") }
        assertTrue("form-labels crept back in: $offenders", offenders.isEmpty())
    }

    @Test fun contextConceptsSurviveTheReshape() {
        val all = vocab.all
        // The content behind the retired form-labels must remain reachable under content names.
        for (c in listOf("video call", "meme", "chat conversation", "selfie", "child", "family")) {
            assertTrue("missing content concept: $c", all.contains(c))
        }
    }

    @Test fun assetParsesIntoDomainsWithExactlyOneEntityBranch() {
        assertTrue("no domains parsed from the shipped asset", vocab.domains.size >= 10)
        val entityDomains = vocab.domains.filter { it.entity }
        assertEquals("exactly one entity domain expected", 1, entityDomains.size)
        // Every gate must be non-empty (stage-1 routing depends on it) and every domain must have concepts.
        assertTrue("a domain has a blank gate", vocab.domains.all { it.gate.isNotBlank() })
        assertTrue("a domain has no concepts", vocab.domains.all { it.concepts.isNotEmpty() })
        // The entity flag must reach the derived lookup used for proposal-only banding.
        assertTrue("entity concepts not exposed", vocab.entityConcepts.isNotEmpty())
        assertTrue("entity lookup is case-insensitive", vocab.isEntity(entityDomains.first().concepts.first()))
    }

    private companion object {
        /** Read the shipped asset; unit-test working dir is the module, but tolerate the repo root too. */
        fun readVocabAsset(): String {
            for (p in listOf(
                "src/main/assets/vocab/concepts.txt",
                "engine-index/src/main/assets/vocab/concepts.txt",
            )) {
                val f = File(p)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            error("concepts.txt not found from ${File(".").absolutePath}")
        }
    }
}
