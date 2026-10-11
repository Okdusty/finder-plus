package ai.dusty.finderplus.ui.cleanup

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.dusty.finderplus.media.DuplicateFinder
import ai.dusty.finderplus.media.DuplicateGroup
import ai.dusty.finderplus.media.LookAlikeFinder
import ai.dusty.finderplus.media.MediaFile
import ai.dusty.finderplus.model.MediaKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CleanupTab(val title: String) { DUPLICATES("Duplicates"), LOOK_ALIKES("Look-alikes"), LARGE("Largest") }

data class CleanupUi(
    val access: Boolean = true,
    val loading: Boolean = true,
    val checking: Pair<Int, Int>? = null,
    val bytesByKind: Map<MediaKind, Long> = emptyMap(),
    val countByKind: Map<MediaKind, Int> = emptyMap(),
    val duplicates: List<DuplicateGroup> = emptyList(),
    val lookAlikes: List<DuplicateGroup> = emptyList(),
    /** False until the index has embedded photos; look-alikes come from those vectors. */
    val lookAlikesReady: Boolean = false,
    val large: List<MediaFile> = emptyList(),
    val tab: CleanupTab = CleanupTab.DUPLICATES,
    val largeFilter: MediaKind? = null,
    val selected: Set<Long> = emptySet(),
    val message: String? = null,
) {
    private val byId: Map<Long, MediaFile> by lazy {
        (duplicates.flatMap { it.files } + lookAlikes.flatMap { it.files } + large).associateBy { it.id }
    }
    val selectedBytes: Long get() = selected.sumOf { byId[it]?.size ?: 0 }
    val duplicateBytes: Long get() = duplicates.sumOf { it.reclaimable }
    val largeShown: List<MediaFile> get() = large.filter { largeFilter == null || it.kind == largeFilter }.take(LARGE_LIMIT)

    companion object { const val LARGE_LIMIT = 150 }
}

sealed interface CleanupEffect {
    /** The system's own confirmation for moving files to the trash. */
    data class ConfirmTrash(val request: PendingIntent) : CleanupEffect
    data class Toast(val text: String) : CleanupEffect
}

/**
 * Finds what can go — byte-identical copies, look-alike copies, and the largest files — and removes
 * only what the user selects, through Android's own trash, so every removal is undoable for 30 days.
 *
 * Two guarantees: one copy of every duplicate or look-alike set is always kept (selection refuses to
 * take the last one), and nothing is ever deleted outright by this app.
 */
@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val repo: CleanupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(CleanupUi())
    val ui: StateFlow<CleanupUi> = _ui

    private val _effects = MutableSharedFlow<CleanupEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<CleanupEffect> = _effects

    private var scanJob: Job? = null
    private var pendingTrash: Set<Long> = emptySet()

    init { scan() }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            if (!repo.hasMediaAccess()) {
                _ui.update { it.copy(access = false, loading = false) }
                return@launch
            }
            _ui.update { it.copy(access = true, loading = true, checking = null, message = null) }
            val media = repo.loadMedia()
            _ui.update {
                it.copy(
                    bytesByKind = media.groupBy { f -> f.kind }.mapValues { (_, v) -> v.sumOf { f -> f.size } },
                    countByKind = media.groupBy { f -> f.kind }.mapValues { (_, v) -> v.size },
                    large = media.sortedByDescending { f -> f.size },
                    loading = false,
                )
            }

            // Look-alikes first: they only compare stored vectors, so they are quick.
            val photos = media.filter { it.kind == MediaKind.IMAGE }.associateBy { it.id }
            val vectors = repo.photoVectors(photos.keys)
            val look = LookAlikeFinder.groups(vectors).mapNotNull { ids ->
                val files = ids.mapNotNull(photos::get)
                if (files.size < 2) null else {
                    val (keep, why) = LookAlikeFinder.chooseBest(files)
                    DuplicateGroup(files.sortedByDescending { it.pixels }, keep, why)
                }
            }.sortedByDescending { it.reclaimable }
            _ui.update { it.copy(lookAlikes = look, lookAlikesReady = vectors.size >= 2) }

            // Exact duplicates: reads only same-size files, and only as much of them as needed.
            val dupes = DuplicateFinder.find(
                media,
                fingerprint = { f, stage -> repo.fingerprint(f, stage) },
                onProgress = { done, total -> if (done % 10 == 0 || done == total) _ui.update { it.copy(checking = done to total) } },
            )
            repo.saveFingerprints(media)
            _ui.update { s ->
                // Extra exact copies start selected, unless the user has already chosen.
                val preselect = if (s.selected.isEmpty()) dupes.flatMap { g -> g.extras.map { it.id } }.toSet() else s.selected
                s.copy(duplicates = dupes, checking = null, selected = preselect)
            }
        }
    }

    fun setTab(tab: CleanupTab) = _ui.update { it.copy(tab = tab) }
    fun setLargeFilter(kind: MediaKind?) = _ui.update { it.copy(largeFilter = kind) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }

    /** Toggle one file, refusing to select the last remaining copy of anything. */
    fun toggle(file: MediaFile) {
        val s = _ui.value
        if (file.id in s.selected) {
            _ui.update { it.copy(selected = it.selected - file.id) }
            return
        }
        val sets = (s.duplicates + s.lookAlikes).filter { g -> g.files.any { it.id == file.id } }
        if (sets.any { g -> g.files.all { it.id == file.id || it.id in s.selected } }) {
            _effects.tryEmit(CleanupEffect.Toast("Keep at least one copy"))
            return
        }
        _ui.update { it.copy(selected = it.selected + file.id) }
    }

    /** Select everything in [group] except the copy suggested for keeping. */
    fun selectExtras(group: DuplicateGroup) = _ui.update { it.copy(selected = it.selected + group.extras.map { f -> f.id }) }

    fun unselectGroup(group: DuplicateGroup) = _ui.update { it.copy(selected = it.selected - group.files.map { f -> f.id }.toSet()) }

    fun selectAllDuplicateExtras() = _ui.update { s -> s.copy(selected = s.selected + s.duplicates.flatMap { g -> g.extras.map { it.id } }) }

    fun clearSelection() = _ui.update { it.copy(selected = emptySet()) }

    /**
     * Ask Android to move the selection to the trash. The system shows its own confirmation, and the
     * files stay restorable from the trash for 30 days — this app never deletes outright.
     */
    fun trashSelected() {
        val s = _ui.value
        if (s.selected.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) {
            _effects.tryEmit(CleanupEffect.Toast("Moving to the trash needs Android 11 or newer"))
            return
        }
        val uris = (s.duplicates.flatMap { it.files } + s.lookAlikes.flatMap { it.files } + s.large)
            .filter { it.id in s.selected }.distinctBy { it.id }.map { Uri.parse(it.uri) }
        val request = runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, true) }.getOrNull()
        if (request == null) {
            _effects.tryEmit(CleanupEffect.Toast("Android refused the request"))
            return
        }
        pendingTrash = s.selected
        _effects.tryEmit(CleanupEffect.ConfirmTrash(request))
    }

    /** The system dialog closed. On OK the files are in the trash: drop them from every list. */
    fun onTrashResult(confirmed: Boolean) {
        val gone = pendingTrash
        pendingTrash = emptySet()
        if (!confirmed || gone.isEmpty()) return
        _ui.update { s ->
            val freed = s.large.filter { it.id in gone }.sumOf { it.size } // `large` holds every file
            fun keepGroups(gs: List<DuplicateGroup>) = gs.mapNotNull { g ->
                val left = g.files.filterNot { it.id in gone }
                if (left.size < 2) null else g.copy(files = left, keep = left.firstOrNull { it.id == g.keep.id } ?: left.first())
            }
            val removed = (s.large).filter { it.id in gone }
            s.copy(
                duplicates = keepGroups(s.duplicates),
                lookAlikes = keepGroups(s.lookAlikes),
                large = s.large.filterNot { it.id in gone },
                bytesByKind = s.bytesByKind.mapValues { (k, v) -> v - removed.filter { it.kind == k }.sumOf { it.size } },
                countByKind = s.countByKind.mapValues { (k, v) -> v - removed.count { it.kind == k } },
                selected = s.selected - gone,
                message = "Freed ${formatSize(freed)} · ${gone.size} file${if (gone.size == 1) "" else "s"} in the trash, restorable for 30 days",
            )
        }
    }

    private fun formatSize(b: Long) = ai.dusty.finderplus.ui.components.formatBytes(b)
}
