package ai.rightone.finderplus.index.work

/**
 * The sub-item resume cursor persisted in work_unit.checkpoint. Encoded as a compact pipe-delimited
 * string to avoid a JSON dependency. See docs/design/01-DB-ENGINE.md §2.2, §4.2.
 */
sealed interface Checkpoint {
    /** Resume ASR from [nextChunkStartMs]; [lang] is cached after first-chunk detection. */
    data class Transcribe(val nextChunkStartMs: Long, val lang: String?) : Checkpoint

    /** Resume a video keyframe scan at [nextFrameIndex] of [totalFrames]. */
    data class Keyframes(val nextFrameIndex: Int, val totalFrames: Int) : Checkpoint

    data object None : Checkpoint
}

object Checkpoints {
    fun encode(cp: Checkpoint): String? = when (cp) {
        is Checkpoint.Transcribe -> "T|${cp.nextChunkStartMs}|${cp.lang ?: ""}"
        is Checkpoint.Keyframes -> "K|${cp.nextFrameIndex}|${cp.totalFrames}"
        Checkpoint.None -> null
    }

    fun decode(s: String?): Checkpoint {
        if (s.isNullOrEmpty()) return Checkpoint.None
        val parts = s.split('|')
        return when (parts[0]) {
            "T" -> Checkpoint.Transcribe(
                nextChunkStartMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                lang = parts.getOrNull(2)?.ifEmpty { null },
            )
            "K" -> Checkpoint.Keyframes(
                nextFrameIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                totalFrames = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
            else -> Checkpoint.None
        }
    }
}
