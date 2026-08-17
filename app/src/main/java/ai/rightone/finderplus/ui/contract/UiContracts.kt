package ai.rightone.finderplus.ui.contract

import ai.rightone.finderplus.model.IndexProgress
import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.ModelRole
import ai.rightone.finderplus.model.SearchResult
import ai.rightone.finderplus.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI hand-off boundary. This is where "stop at UI design" lands: the widget, pop-up, and settings
 * bind to these UiState/ViewModel contracts, drawn against docs/ui/WIREFRAMES.md. The rendering layer
 * (Glance + Compose) is intentionally NOT implemented in this phase. See docs/ui/UI-CONTRACTS.md.
 */

// ---------------- Widget ----------------

sealed interface WidgetState {
    data class Idle(
        val indexedCount: Int, val videoCount: Int, val audioCount: Int,
        val lastUpdatedMs: Long?, val recent: List<String>,
    ) : WidgetState
    data class Indexing(val progress: IndexProgress) : WidgetState
    data class Paused(val progress: IndexProgress, val reason: PauseReason) : WidgetState
    data object NeedsPermission : WidgetState
    data class PartialAccess(val visibleCount: Int) : WidgetState
}

enum class PauseReason { THERMAL, LOW_BATTERY, NOT_CHARGING, MEMORY }

sealed interface WidgetAction {
    data object OpenSearch : WidgetAction
    data object Update : WidgetAction
    data object Stop : WidgetAction
    data object FixPermission : WidgetAction
    data class OpenRecent(val query: String) : WidgetAction
}

// ---------------- Search pop-up ----------------

sealed interface SearchUiState {
    data class Empty(val suggestions: List<String>, val recent: List<String>) : SearchUiState
    data class Loading(val partial: List<ResultGroup>) : SearchUiState
    data class Results(val groups: List<ResultGroup>, val streaming: Boolean) : SearchUiState
    data class NoResults(val query: String, val pendingItems: Int) : SearchUiState
    data class NeedsIndex(val pendingItems: Int) : SearchUiState
}

data class ResultGroup(val kind: MediaKind, val count: Int, val results: List<SearchResult>)

enum class ResultAction { OPEN_IN_GALLERY, COPY_TEXT, COPY_FILE, SHARE, SHOW_TAGS, OPEN_LOCATION }

/**
 * One media item blown up for inspection.
 *
 * The list row is deliberately small — it exists to be scanned. This is the other half: enough of the
 * item to confirm it is the right one *before* copying, which is the whole reason a preview earns its
 * place in an app whose primary gesture is "tap to copy".
 */
data class PreviewUi(
    val result: SearchResult,
    /** What the pipeline concluded this item is, highest-trust source first. */
    val tags: List<String> = emptyList(),
    /** Transcript / OCR / profile text, whichever this item has. */
    val body: String? = null,
    /** Labels proposed but not confirmed — reviewable right here, label to confidence. */
    val suggestions: List<Pair<String, Float>> = emptyList(),
)

sealed interface SearchEffect {
    /** Default tap action: copy the file URI + its extracted content to the clipboard in one clip. */
    data class CopyToClipboard(val result: SearchResult) : SearchEffect
    data class Toast(val message: String) : SearchEffect
    data class OpenInGallery(val uri: String, val seekMs: Long?, val displayName: String? = null, val mime: String? = null) : SearchEffect
    data class Share(val uri: String) : SearchEffect
    data class ShowTags(val tags: List<Tag>) : SearchEffect
}

interface SearchViewModel {
    val state: StateFlow<SearchUiState>
    val effects: Flow<SearchEffect>
    fun onQueryChanged(text: String)
    /** Tap → CopyToClipboard by default (configurable via [IndexPrefs.tapAction]). */
    fun onResultTap(result: SearchResult)
    fun onResultLongPress(result: SearchResult, action: ResultAction)
    fun onDismiss()
}

/** What a single tap on a result does. Copy is the default per the refined concept. */
enum class TapAction { COPY_TO_CLIPBOARD, OPEN_IN_GALLERY }

// ---------------- Settings / onboarding ----------------

data class SettingsUiState(
    val index: IndexStats,
    val models: List<ModelRow>,
    val prefs: IndexPrefs,
    val permission: PermissionState,
    val footprintBytes: Long,
)

data class IndexStats(
    val total: Int, val images: Int, val videos: Int, val audio: Int,
    val failed: Int, val lastUpdatedMs: Long?,
)

data class ModelRow(
    val id: String, val role: ModelRole, val label: String, val sizeBytes: Long,
    val installed: Boolean, val downloadFraction: Float?, val verifying: Boolean,
)

data class IndexPrefs(
    val transcribeOnlyWhileCharging: Boolean,
    val keyframesPerVideo: Int,
    val downloadOverCellular: Boolean,
    val encryptAtRest: Boolean,
    val tapAction: TapAction = TapAction.COPY_TO_CLIPBOARD,
)

enum class PermissionState { GRANTED, PARTIAL, DENIED }

interface SettingsViewModel {
    val state: StateFlow<SettingsUiState>
    fun update()
    fun rebuild()
    fun wipe()
    fun downloadModel(id: String)
    fun deleteModel(id: String)
    fun selectSpeechModel(id: String)
    fun setPref(pref: IndexPrefs)
    fun requestPermission()
    fun selectMorePhotos()
}
