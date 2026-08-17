package ai.dusty.finderplus.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.index.IndexWorker
import ai.dusty.finderplus.model.DownloadProgress
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.model.ModelSpec
import ai.dusty.finderplus.speech.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the model-download screen. The only thing that calls [ModelManager.download], closing the
 * gap that made every content pass park as "prerequisite unavailable" with no way to install the
 * model that would unblock it.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val models: ModelManager,
    private val db: FinderDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Top-level models only — companions (projectors, tokenizers) download with their parent. */
    private val companionIds: Set<String> = ModelCatalog.all.mapNotNull { it.requiresId }.toSet()
    val catalog: List<ModelSpec> = ModelCatalog.all.filter { it.id !in companionIds && it.url.isNotEmpty() }

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    init { refresh() }

    fun refresh() {
        _installed.value = ModelCatalog.all
            .filter { models.isInstalled(it.id) }
            .map { it.id }
            .toSet()
    }

    fun footprintBytes(): Long = models.installedFootprintBytes()

    /** True when [spec] and its whole companion chain are installed, i.e. the capability is usable. */
    fun fullyInstalled(spec: ModelSpec): Boolean = companionChain(spec).all { it.id in _installed.value }

    fun progressFor(spec: ModelSpec): DownloadProgress? = _progress.value[spec.id]

    fun download(spec: ModelSpec) {
        if (spec.id in _busy.value) return
        viewModelScope.launch {
            _busy.value = _busy.value + spec.id
            try {
                for (s in companionChain(spec)) {
                    models.download(s.id).collect { p ->
                        _progress.value = _progress.value + (s.id to p)
                    }
                }
                refresh()
                wakePasses()
            } catch (t: Throwable) {
                Log.w("finderSettings", "download ${spec.id} failed", t)
            } finally {
                _busy.value = _busy.value - spec.id
                _progress.value = _progress.value - spec.id
            }
        }
    }

    fun delete(spec: ModelSpec) {
        models.delete(spec.id)
        refresh()
    }

    /** [spec] plus everything it requires, companions first. */
    private fun companionChain(spec: ModelSpec): List<ModelSpec> {
        val out = mutableListOf<ModelSpec>()
        val seen = mutableSetOf<String>()
        fun visit(s: ModelSpec) {
            if (!seen.add(s.id)) return
            s.requiresId?.let { id ->
                ModelCatalog.all.firstOrNull { it.id == id }?.let(::visit)
            }
            out.add(s)
        }
        visit(spec)
        return out
    }

    /** A model just landed: revive skipped units and run an index so its passes pick it up. */
    private suspend fun wakePasses() {
        val revived = db.workUnitDao().requeueAllSkipped(System.currentTimeMillis())
        if (revived > 0) {
            IndexWorker.enqueue(context)
            Log.i("finderSettings", "revived $revived skipped units; indexing enqueued")
        }
    }
}
