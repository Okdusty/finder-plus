package ai.rightone.finderplus.model

/** Lifecycle of an index run, surfaced on the widget and the foreground notification. */
enum class RunStatus { IDLE, SCANNING, RUNNING, PAUSED, STOPPING, STOPPED, DONE, FAILED }

/** Single source of truth for indexing progress. Emitted by the orchestrator, observed by the UI. */
data class IndexProgress(
    val runId: Long,
    val status: RunStatus,
    val total: Int,
    val done: Int,
    val failed: Int,
    val currentPass: Pass?,
    val etaSeconds: Long?,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total

    companion object {
        val IDLE = IndexProgress(0L, RunStatus.IDLE, 0, 0, 0, null, null)
    }
}
