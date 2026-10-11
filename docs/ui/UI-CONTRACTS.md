# UI Contracts (hand-off boundary)

The build stops here. These are the `UiState` / `ViewModel` / event contracts the future Glance widget and Compose pop-up bind to. They are pure Kotlin (depend only on `core-model`), so a UI implementer can start without touching the engine. Ship location: `app/src/main/java/ai/dusty/finderplus/ui/contract/`.

## 1. Widget

```kotlin
sealed interface WidgetState {
  data class Idle(val indexedCount: Int, val videoCount: Int, val audioCount: Int,
                  val lastUpdatedMs: Long?, val recent: List<String>) : WidgetState
  data class Indexing(val progress: IndexProgress) : WidgetState
  data class Paused(val progress: IndexProgress, val reason: PauseReason) : WidgetState
  data object NeedsPermission : WidgetState
  data class PartialAccess(val visibleCount: Int) : WidgetState
}
enum class PauseReason { THERMAL, LOW_BATTERY, NOT_CHARGING, MEMORY }

sealed interface WidgetAction {
  data object OpenSearch : WidgetAction
  data object Update : WidgetAction              // enqueue incremental index
  data object Stop : WidgetAction                // cooperative stop
  data object FixPermission : WidgetAction
  data class OpenRecent(val query: String) : WidgetAction
}
```

## 2. Search pop-up

```kotlin
sealed interface SearchUiState {
  data class Empty(val suggestions: List<String>, val recent: List<String>) : SearchUiState
  data class Loading(val partial: List<ResultGroup>) : SearchUiState
  data class Results(val groups: List<ResultGroup>, val streaming: Boolean) : SearchUiState
  data class NoResults(val query: String, val pendingItems: Int) : SearchUiState
  data class NeedsIndex(val pendingItems: Int) : SearchUiState
}
data class ResultGroup(val kind: MediaKind, val count: Int, val results: List<SearchResult>)

interface SearchViewModel {
  val state: StateFlow<SearchUiState>
  fun onQueryChanged(text: String)      // debounced ~150 ms; streams SearchEngine.search()
  fun onResultTap(result: SearchResult) // -> CopyToClipboard by default (IndexPrefs.tapAction)
  fun onResultLongPress(result: SearchResult, action: ResultAction)
  fun onDismiss()
  val effects: Flow<SearchEffect>
}
enum class ResultAction { OPEN_IN_GALLERY, COPY_TEXT, COPY_FILE, SHARE, SHOW_TAGS, OPEN_LOCATION }
enum class TapAction { COPY_TO_CLIPBOARD, OPEN_IN_GALLERY }   // default COPY_TO_CLIPBOARD
sealed interface SearchEffect {
  // Default tap: one ClipData carries the media URI + the extracted content text (see 05-MEDIA-PROFILE.md).
  data class CopyToClipboard(val result: SearchResult) : SearchEffect
  data class Toast(val message: String) : SearchEffect
  data class OpenInGallery(val uri: String, val seekMs: Long?) : SearchEffect
  data class Share(val uri: String) : SearchEffect
  data class ShowTags(val tags: List<Tag>) : SearchEffect
}

// SearchResult.copyText carries the content placed on the clipboard (transcript / OCR / AI profile).
```

## 3. Settings / onboarding

```kotlin
data class SettingsUiState(
  val index: IndexStats, val models: List<ModelRow>, val prefs: IndexPrefs,
  val permission: PermissionState, val footprintBytes: Long,
)
data class IndexStats(val total: Int, val images: Int, val videos: Int, val audio: Int,
                      val failed: Int, val lastUpdatedMs: Long?)
data class ModelRow(val id: String, val role: ModelRole, val label: String, val sizeBytes: Long,
                    val installed: Boolean, val download: DownloadProgress?)
data class DownloadProgress(val received: Long, val total: Long, val verifying: Boolean)
data class IndexPrefs(val transcribeOnlyWhileCharging: Boolean, val keyframesPerVideo: Int,
                      val downloadOverCellular: Boolean, val encryptAtRest: Boolean)
enum class PermissionState { GRANTED, PARTIAL, DENIED }

interface SettingsViewModel {
  val state: StateFlow<SettingsUiState>
  fun update(); fun rebuild(); fun wipe()
  fun downloadModel(id: String); fun deleteModel(id: String); fun selectSpeechModel(id: String)
  fun setPref(pref: IndexPrefs)
  fun requestPermission(); fun selectMorePhotos()
}
```

## 4. Progress stream (shared)

Both the widget and the notification observe the same source of truth:

```kotlin
interface IndexProgressSource { fun progress(): Flow<IndexProgress> }   // backed by IndexOrchestrator
```

`IndexProgress` (from `core-model`) carries `status`, `total`, `done`, `failed`, `currentPass`, `etaSeconds` — everything the widget status line, the pop-up "still pending" footnote, and the foreground notification need.

Implementing the UI = binding Glance composables to `WidgetState`/`WidgetAction`, a Compose dialog to `SearchUiState`/`SearchViewModel`, and a Compose dialog to `SettingsUiState`/`SettingsViewModel`. No engine or DB knowledge required past these interfaces.
