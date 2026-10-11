package ai.dusty.finderplus.index

/**
 * A video's on-screen text from its keyframes, each line once. Text that stays on screen across many
 * frames is read once per frame; counting every copy would weigh a video by how long text stayed up.
 */
internal object KeyframeText {
    fun collapse(frameTexts: List<String>): String? =
        frameTexts.asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .joinToString("\n")
            .ifBlank { null }
}
