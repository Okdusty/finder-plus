package ai.dusty.finderplus.search

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architectural guard: **voting on media is only for users.**
 *
 * The vote table may be written solely by the search UI relaying a human gesture. If an indexing
 * pass, the judge, or any AI module ever gains a reference to the vote store, the ranking starts
 * training on machine opinions — the same self-training loop the tag layer explicitly forbids.
 * A source scan is deliberately blunt: it catches the mistake at the import, before it compiles
 * into behaviour.
 */
class VoteProvenanceTest {

    private val forbiddenModules = listOf("engine-index", "ai-vision", "ai-speech", "ai-text", "core-media")
    private val markers = listOf("VoteDao", "search_vote", "SearchVoteEntity")

    @Test fun machineModulesNeverTouchTheVoteStore() {
        val root = checkNotNull(
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() },
        ) { "could not locate repo root from ${System.getProperty("user.dir")}" }

        val offenders = ArrayList<String>()
        for (module in forbiddenModules) {
            val src = File(root, "$module/src/main")
            assertTrue("module layout changed — update this test: $src", src.exists())
            src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                markers.forEach { m -> if (text.contains(m)) offenders += "${f.relativeTo(root)}: $m" }
            }
        }
        assertTrue("machine code references the vote store:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}
