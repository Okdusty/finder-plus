# 02 · Architecture

Multi-module Kotlin/Android project. No full app frame — the only user surfaces are a Glance **widget**, a translucent **search pop-up**, and a dialog-themed **settings/onboarding** sheet. The heavy lifting is the on-device index (see `01-DB-ENGINE.md`).

## 1. Module graph

```
                         ┌───────────┐
                         │    app    │  widget · pop-up host · settings · Application · DI · service
                         └─────┬─────┘
        ┌──────────────┬───────┼────────┬───────────────┐
        ▼              ▼       ▼        ▼               ▼
 ┌────────────┐ ┌────────────┐ │ ┌─────────────┐ ┌─────────────┐
 │engine-index│ │engine-search│ │ │  ai-vision  │ │  ai-speech  │  ai-text
 └─────┬──────┘ └──────┬─────┘ │ └──────┬──────┘ └──────┬──────┘
       │               │       │        │                │
       ├───────────────┴───────┼────────┴────────────────┤
       ▼                       ▼                         ▼
 ┌────────────┐          ┌────────────┐            ┌────────────┐
 │  core-db   │          │ core-media │            │ core-model │
 └─────┬──────┘          └─────┬──────┘            └─────┬──────┘
       └───────────────────────┴─────────────────────────┘
                         (all depend on core-model)
```

Dependency rule: **downward only**. `core-model` depends on nothing (pure Kotlin). No module depends on `app`.

## 2. Module responsibilities & public surface

| Module | Type | Responsibility | Exposes |
|---|---|---|---|
| `core-model` | pure Kotlin/JVM | domain types shared everywhere | `MediaItem`, `MediaKind`, `Pass`, `Tag`, `Document`, `Segment`, `Embedding`, `SearchQuery`, `SearchResult`, `SearchHit`, `IndexProgress`, `Trigger` |
| `core-db` | android-lib | Room DB, DAOs, FTS5, vector store, migrations, converters | `FinderDatabase`, `*Dao`, `VectorStore`, `WorkLedger` |
| `core-media` | android-lib | MediaStore reader, diff, thumbnails, PCM/frame decode | `MediaStoreReader`, `MediaDiffer`, `FrameExtractor`, `PcmDecoder`, `ThumbnailLoader` |
| `engine-index` | android-lib | flagship orchestrator, pass handlers, WorkManager, model coordinator, stop/resume | `IndexOrchestrator`, `IndexWorker`, `ModelCoordinator`, `StopSignal`, `PassHandler` |
| `engine-search` | android-lib | query parsing, hybrid FTS+vector ranking, fusion | `SearchEngine`, `QueryParser`, `RankFuser` |
| `ai-vision` | android-lib | ML Kit labeling/OCR + CLIP image/text embedding (ONNX) | `ImageAnalyzer`, `Labeler`, `OcrReader`, `ClipImageEncoder`, `ClipTextEncoder` |
| `ai-speech` | android-lib | whisper.cpp JNI multilingual ASR + model mgmt | `SpeechRecognizer`, `WhisperEngine` (JNI), `AudioAnalyzer` |
| `ai-text` | android-lib | multilingual sentence embedding (ONNX) | `TextEmbedder`, `LanguageUtils` |
| `app` | android-app | Glance widget, pop-up Activity host, settings, `FinderApp`, Hilt graph, `IndexingService`, ViewModels + UiState | — (UI is **design-only** this phase) |

## 3. `gradle/libs.versions.toml`

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
coroutines = "1.9.0"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
room = "2.6.1"
workmanager = "2.10.0"
hilt = "2.52"
hiltWork = "1.2.0"
glance = "1.1.1"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
mlkitLabeling = "17.0.9"
mlkitText = "16.0.1"
onnxruntime = "1.20.0"
exifinterface = "1.3.7"
junit = "4.13.2"
androidxTestJunit = "1.2.1"
robolectric = "4.14"
truth = "1.4.4"
turbine = "1.2.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "workmanager" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "hiltWork" }
hilt-work-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltWork" }
glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
mlkit-labeling = { module = "com.google.mlkit:image-labeling", version.ref = "mlkitLabeling" }
mlkit-text = { module = "com.google.mlkit:text-recognition", version.ref = "mlkitText" }
onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnxruntime" }
androidx-exifinterface = { module = "androidx.exifinterface:exifinterface", version.ref = "exifinterface" }
junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestJunit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

## 4. `settings.gradle.kts`

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "finder-plus"
include(":app", ":core-model", ":core-db", ":core-media",
        ":engine-index", ":engine-search", ":ai-vision", ":ai-speech", ":ai-text")
```

## 5. DI (Hilt) wiring plan

- `@HiltAndroidApp class FinderApp` bootstraps the graph + a custom `Configuration.Provider` for WorkManager (so `IndexWorker` gets injected).
- Modules:
  - `DbModule` → `FinderDatabase` + each DAO + `VectorStore`.
  - `MediaModule` → `MediaStoreReader`, `MediaDiffer`, `FrameExtractor`, `PcmDecoder`.
  - `AiModule` → `ImageAnalyzer`, `SpeechRecognizer`, `TextEmbedder`, `ModelManager` (each an interface bound to an impl; native-backed ones are `@Singleton`).
  - `EngineModule` → `IndexOrchestrator`, `ModelCoordinator`, all `PassHandler`s multibound into a `Set`.
  - `SearchModule` → `SearchEngine`, `QueryParser`.
- `IndexWorker` uses `@HiltWorker` + `WorkerAssistedFactory`.

## 6. AndroidManifest permission set (`app`)

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.INTERNET" />          <!-- model download only -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

The single launcher-capable component is the dialog-themed `SearchPopupActivity` / `SettingsActivity` (needed for the first-run permission grant). It is `Theme.Translucent`, `excludeFromRecents`, `launchMode="singleTop"` — so it never presents as a full app frame, satisfying the "widget + pop-up only" constraint.

## 7. `core-model` shared types (authoritative)

```kotlin
enum class MediaKind { IMAGE, VIDEO, AUDIO }
enum class Trigger { MANUAL, SCHEDULED, BOOT_RESUME }

data class MediaItem(
  val id: Long, val uri: String, val kind: MediaKind,
  val displayName: String?, val mime: String?, val sizeBytes: Long,
  val dateTakenMs: Long?, val dateModified: Long, val durationMs: Long?,
  val width: Int?, val height: Int?, val lat: Double?, val lon: Double?,
  val place: String?, val bucketId: Long?, val bucketName: String?,
  val indexState: IndexState, val pipelineVersion: Int,
)
enum class IndexState { NEW, PARTIAL, DONE, FAILED, STALE }

enum class TagSource { LABEL, OBJECT, OCR_KEYWORD, CATEGORY, USER }
data class Tag(val itemId: Long, val source: TagSource, val label: String, val confidence: Float)

enum class DocSource { OCR, TRANSCRIPT }
data class Document(val itemId: Long, val source: DocSource, val lang: String?, val text: String)
data class Segment(val itemId: Long, val startMs: Long, val endMs: Long, val text: String)

enum class EmbeddingKind { IMAGE_CLIP, TEXT_TRANSCRIPT }
data class Embedding(val itemId: Long, val kind: EmbeddingKind, val sourceRef: Int,
                     val modelId: String, val vec: FloatArray)

data class SearchQuery(val raw: String, val kinds: Set<MediaKind> = emptySet(),
                       val after: Long? = null, val before: Long? = null,
                       val place: String? = null, val text: String = "")
data class SearchHit(val startMs: Long?, val endMs: Long?, val snippet: String?, val field: String)
data class SearchResult(val item: MediaItem, val score: Float, val confidence: Float,
                        val hits: List<SearchHit>, val thumbnailUri: String)

data class IndexProgress(val runId: Long, val status: RunStatus, val total: Int, val done: Int,
                         val failed: Int, val currentPass: Pass?, val etaSeconds: Long?)
enum class RunStatus { IDLE, SCANNING, RUNNING, PAUSED, STOPPING, STOPPED, DONE, FAILED }
```

These are the contract every other module and the future UI bind to.
