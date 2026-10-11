package ai.dusty.finderplus.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.model.ModelCategory
import ai.dusty.finderplus.model.ModelRole
import ai.dusty.finderplus.model.ModelSpec
import ai.dusty.finderplus.model.ModelTier
import ai.dusty.finderplus.models.InstallProgress
import ai.dusty.finderplus.models.ModelInstaller
import ai.dusty.finderplus.speech.ModelManager
import ai.dusty.finderplus.ui.widget.FinderWidgetReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One model as the Models screen shows it. */
data class ModelRowUi(
    val spec: ModelSpec,
    val bundleBytes: Long,
    val state: State,
    val error: String?,
    val resumeBytes: Long,
) {
    sealed interface State {
        data object NotInstalled : State
        data object Queued : State
        data class Downloading(val fraction: Float, val received: Long, val total: Long, val verifying: Boolean) : State
        data object Installed : State
    }
}

data class ModelsUi(
    val sections: List<Pair<ModelCategory, List<ModelRowUi>>> = emptyList(),
    val essentialsMissingBytes: Long = 0,
    val recommendedMissingBytes: Long = 0,
    val queue: List<String> = emptyList(),
    val current: InstallProgress? = null,
    val footprint: Long = 0,
    val free: Long = 0,
    val unmetered: Boolean = true,
    val speechOptions: List<ModelSpec> = emptyList(),
    val speechSelected: String? = null,
    val widgetPlaced: Boolean = false,
    /** Exempt from battery optimization, which long indexing runs need. */
    val backgroundAllowed: Boolean = false,
    val canPinWidget: Boolean = false,
)

/**
 * Drives the Models screen. Installs run in [ModelInstaller]'s background queue, so this ViewModel
 * only describes state and leaving the screen cancels nothing.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val models: ModelManager,
    private val installer: ModelInstaller,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    val ui: StateFlow<ModelsUi> = combine(
        installer.queue, installer.errors, installer.progress, installer.installedVersion, refreshTick,
    ) { queue, errors, progress, _, _ -> build(queue, errors, progress) }
        // Stats files and walks the models folder: never on the main thread.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUi())

    fun refresh() { refreshTick.value++ }

    private fun build(queue: List<String>, errors: Map<String, String>, progress: InstallProgress?): ModelsUi {
        val rows = ModelCatalog.userFacing.map { spec ->
            val state = when {
                installer.isInstalled(spec) -> ModelRowUi.State.Installed
                progress is InstallProgress.Downloading && progress.modelId == spec.id ->
                    ModelRowUi.State.Downloading(progress.fraction, progress.received, progress.total, progress.verifying)
                spec.id in queue -> ModelRowUi.State.Queued
                else -> ModelRowUi.State.NotInstalled
            }
            ModelRowUi(
                spec = spec,
                bundleBytes = ModelCatalog.bundleBytes(spec),
                state = state,
                error = errors[spec.id],
                resumeBytes = ModelCatalog.withCompanions(spec).sumOf { models.partialBytes(it.id) },
            )
        }
        val sections = ModelCategory.entries.map { cat ->
            cat to rows.filter { it.spec.category == cat }
                .sortedWith(compareBy({ it.spec.tier.ordinal }, { it.bundleBytes }))
        }.filter { it.second.isNotEmpty() }

        val speech = ModelCatalog.asrPreference.filter { installer.isInstalled(it) }
        val mgr = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, FinderWidgetReceiver::class.java)
        return ModelsUi(
            sections = sections,
            essentialsMissingBytes = installer.bundle(ModelTier.ESSENTIAL).sumOf { installer.remainingBytes(it) },
            recommendedMissingBytes = installer.bundle(ModelTier.RECOMMENDED).sumOf { installer.remainingBytes(it) },
            queue = queue,
            current = progress,
            footprint = installer.footprintBytes(),
            free = installer.freeBytes(),
            unmetered = installer.onUnmeteredNetwork(),
            speechOptions = speech,
            speechSelected = models.effectiveAsrId(),
            widgetPlaced = runCatching { mgr.getAppWidgetIds(provider).isNotEmpty() }.getOrDefault(false),
            backgroundAllowed = ai.dusty.finderplus.ui.BackgroundAccess.isUnrestricted(context),
            canPinWidget = runCatching { mgr.isRequestPinAppWidgetSupported }.getOrDefault(false),
        )
    }

    fun install(spec: ModelSpec) = installer.install(spec)
    fun installBundle(tier: ModelTier) = installer.install(installer.bundle(tier))
    fun cancel(spec: ModelSpec) = installer.cancel(spec.id)
    fun cancelAll() = installer.cancelAll()
    fun remove(spec: ModelSpec) { installer.remove(spec); refresh() }

    fun selectSpeech(spec: ModelSpec) {
        if (spec.role == ModelRole.ASR || spec.role == ModelRole.ASR_WHISPER) {
            models.selectedAsrId = spec.id
            refresh()
        }
    }

    /** Asks the launcher to place the widget; the launcher shows its own confirmation. */
    fun pinWidget(): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .requestPinAppWidget(ComponentName(context, FinderWidgetReceiver::class.java), null, null)
    }.getOrDefault(false)
}
