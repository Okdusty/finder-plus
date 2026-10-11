package ai.dusty.finderplus.index

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which folders live in the vault, and whether new arrivals are hidden automatically.
 *
 * ### Why per-folder rules rather than one switch
 *
 * "Hide everything that isn't camera" is one person's answer, not a design. Someone else wants only
 * `Download` hidden; someone else wants WhatsApp kept visible because their gallery app is where
 * they revisit it. So the unit of decision is the **folder**, expressed as a MediaStore relative
 * path prefix, and the app ships with nothing hidden until the user says so.
 *
 * ### Longest-prefix wins
 *
 * Rules nest the way folders do: `HIDE Download/` with `KEEP Download/Manuals/` means exactly what
 * it reads like. Resolution takes the most specific matching rule, so a broad rule can always be
 * carved out without deleting it.
 *
 * ### The camera guard
 *
 * `DCIM/Camera` is protected: it can be hidden only by an explicit rule naming it, never swept up by
 * a broader one. Photos someone took themselves are the last thing that should vanish from their
 * gallery by accident.
 */
@Singleton
class VaultPolicy @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {

    enum class Decision { HIDE, KEEP }

    private val sp = context.getSharedPreferences("finder-vault", Context.MODE_PRIVATE)

    /** Hide newly-discovered media that matches a HIDE rule, without asking each time. */
    var auto: Boolean
        get() = sp.getBoolean("auto", false)
        set(value) = sp.edit().putBoolean("auto", value).apply()

    /** Stored as "HIDE:Download/" / "KEEP:Pictures/Manuals/". Empty by default: nothing is hidden. */
    private fun raw(): Set<String> = sp.getStringSet("rules", emptySet()) ?: emptySet()

    fun rules(): Map<String, Decision> = raw().mapNotNull { entry ->
        val i = entry.indexOf(':')
        if (i <= 0) return@mapNotNull null
        val decision = runCatching { Decision.valueOf(entry.substring(0, i)) }.getOrNull() ?: return@mapNotNull null
        normalize(entry.substring(i + 1)) to decision
    }.toMap()

    fun set(folder: String, decision: Decision) {
        val key = normalize(folder)
        val kept = raw().filterNot { it.substringAfter(':').let(::normalize) == key }
        sp.edit().putStringSet("rules", (kept + "${decision.name}:$key").toSet()).apply()
    }

    fun clear(folder: String) {
        val key = normalize(folder)
        sp.edit().putStringSet("rules", raw().filterNot { it.substringAfter(':').let(::normalize) == key }.toSet()).apply()
    }

    /**
     * What should happen to a file in [relPath]. Unmatched paths are [Decision.KEEP]: the vault only
     * ever swallows what someone pointed at.
     */
    fun decide(relPath: String): Decision {
        val path = normalize(relPath)
        val rules = rules()
        val match = rules.keys
            .filter { path == it || path.startsWith(it) }
            .maxByOrNull { it.length }
            ?: return Decision.KEEP

        // The camera guard: a broad rule never reaches DCIM/Camera; only a rule that names it does.
        if (path.startsWith(CAMERA) && !match.startsWith(CAMERA)) return Decision.KEEP
        return rules[match] ?: Decision.KEEP
    }

    fun hasHideRules(): Boolean = rules().any { it.value == Decision.HIDE }

    private fun normalize(p: String): String =
        p.trim().trim('/').lowercase().let { if (it.isEmpty()) "" else "$it/" }

    private companion object { const val CAMERA = "dcim/camera/" }
}
