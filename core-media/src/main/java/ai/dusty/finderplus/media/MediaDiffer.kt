package ai.dusty.finderplus.media

/** The prior indexed signature of a file (from the DB), for diffing against a fresh [MediaDigest]. */
data class IndexedSignature(
    val id: Long,
    val dateModified: Long,
    val generation: Long,
    val sizeBytes: Long,
)

/** The outcome of an incremental scan: what to add, re-index, and purge. */
data class MediaDiff(
    val added: List<MediaDigest>,
    val changed: List<MediaDigest>,
    val removedIds: List<Long>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removedIds.isEmpty()
}

/**
 * Pure, unit-testable incremental diff. Compares the live MediaStore digest against the DB's known
 * signatures by `_ID` + `DATE_MODIFIED`/generation/size. See docs/design/01-DB-ENGINE.md §8.
 */
object MediaDiffer {

    fun diff(live: List<MediaDigest>, known: List<IndexedSignature>): MediaDiff {
        val knownById = known.associateBy { it.id }
        val liveIds = HashSet<Long>(live.size)

        val added = ArrayList<MediaDigest>()
        val changed = ArrayList<MediaDigest>()
        for (d in live) {
            liveIds += d.id
            val prev = knownById[d.id]
            when {
                prev == null -> added += d
                changedSince(prev, d) -> changed += d
                else -> { /* unchanged: skip */ }
            }
        }

        val removed = known.asSequence().map { it.id }.filter { it !in liveIds }.toList()
        return MediaDiff(added, changed, removed)
    }

    /**
     * A file counts as changed only on strong evidence. Generation is compared ONLY when both sides
     * have a real value: treating an unknown (0) stored generation as a mismatch made every scan
     * re-index the entire gallery and discard all completed AI work.
     */
    private fun changedSince(prev: IndexedSignature, now: MediaDigest): Boolean {
        if (prev.dateModified != now.dateModified) return true
        if (prev.sizeBytes != now.sizeBytes) return true
        val bothHaveGeneration = prev.generation != 0L && now.generation != 0L
        return bothHaveGeneration && prev.generation != now.generation
    }
}
