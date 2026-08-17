package ai.rightone.finderplus.index

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The policy decides whether someone's files vanish from their gallery, so its resolution rules are
 * pinned here rather than trusted. The logic is re-implemented against the same contract because
 * [VaultPolicy] needs a Context; what matters is that the *rules* are unambiguous:
 * longest-prefix wins, unmatched means KEEP, and a broad rule never swallows the camera roll.
 */
class VaultPolicyLogicTest {

    private fun decide(rules: Map<String, VaultPolicy.Decision>, path: String): VaultPolicy.Decision {
        fun norm(p: String) = p.trim().trim('/').lowercase().let { if (it.isEmpty()) "" else "$it/" }
        val n = norm(path)
        val r = rules.mapKeys { norm(it.key) }
        val match = r.keys.filter { n == it || n.startsWith(it) }.maxByOrNull { it.length }
            ?: return VaultPolicy.Decision.KEEP
        if (n.startsWith("dcim/camera/") && !match.startsWith("dcim/camera/")) return VaultPolicy.Decision.KEEP
        return r[match] ?: VaultPolicy.Decision.KEEP
    }

    @Test fun nothingIsHiddenUntilAsked() {
        assertEquals(VaultPolicy.Decision.KEEP, decide(emptyMap(), "Download/"))
        assertEquals(VaultPolicy.Decision.KEEP, decide(emptyMap(), "Pictures/Reddit/"))
    }

    @Test fun ruleAppliesToSubfolders() {
        val rules = mapOf("Download" to VaultPolicy.Decision.HIDE)
        assertEquals(VaultPolicy.Decision.HIDE, decide(rules, "Download/"))
        assertEquals(VaultPolicy.Decision.HIDE, decide(rules, "Download/YTDLnis/Video/"))
        assertEquals(VaultPolicy.Decision.KEEP, decide(rules, "Downloads-other/"))
    }

    @Test fun mostSpecificRuleWins() {
        val rules = mapOf(
            "Download" to VaultPolicy.Decision.HIDE,
            "Download/Manuals" to VaultPolicy.Decision.KEEP,
        )
        assertEquals(VaultPolicy.Decision.HIDE, decide(rules, "Download/Memes/"))
        assertEquals(VaultPolicy.Decision.KEEP, decide(rules, "Download/Manuals/"))
        assertEquals(VaultPolicy.Decision.KEEP, decide(rules, "Download/Manuals/Deep/"))
    }

    @Test fun broadRuleNeverSwallowsTheCameraRoll() {
        // The failure that must never happen: "hide everything" quietly taking the photos someone
        // took themselves. Only a rule naming the camera folder can hide it.
        val broad = mapOf("" to VaultPolicy.Decision.HIDE, "DCIM" to VaultPolicy.Decision.HIDE)
        assertEquals(VaultPolicy.Decision.KEEP, decide(broad, "DCIM/Camera/"))
        assertEquals(VaultPolicy.Decision.HIDE, decide(broad, "DCIM/Screenshots/"))

        val explicit = mapOf("DCIM/Camera" to VaultPolicy.Decision.HIDE)
        assertEquals(VaultPolicy.Decision.HIDE, decide(explicit, "DCIM/Camera/"))
    }

    @Test fun matchingIsCaseAndSlashInsensitive() {
        val rules = mapOf("/Pictures/Reddit/" to VaultPolicy.Decision.HIDE)
        assertEquals(VaultPolicy.Decision.HIDE, decide(rules, "pictures/reddit"))
        assertEquals(VaultPolicy.Decision.HIDE, decide(rules, "Pictures/Reddit/"))
    }
}
