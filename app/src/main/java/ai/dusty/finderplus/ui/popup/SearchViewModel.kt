package ai.dusty.finderplus.ui.popup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.dusty.finderplus.db.dao.MediaItemDao
import ai.dusty.finderplus.index.IndexOrchestrator
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.RunStatus
import ai.dusty.finderplus.model.SearchResult
import ai.dusty.finderplus.search.SearchEngine
import ai.dusty.finderplus.ui.contract.ResultGroup
import ai.dusty.finderplus.ui.contract.SearchEffect
import ai.dusty.finderplus.ui.contract.SearchUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The pop-up's smart-search setup card. */
sealed interface SetupUi {
    data class Offer(val bytes: Long, val unmetered: Boolean) : SetupUi
    data class Installing(val current: Pair<String?, Float>?, val remaining: Int) : SetupUi
}

/** Index status shown inline in the pop-up, so search always explains what it can't find yet. */
data class IndexStatusUi(
    val indexedItems: Int = 0,
    val photos: Int = 0,
    val videos: Int = 0,
    val audio: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val phase: String? = null,
) {
    val percent: Int get() = if (total <= 0) 0 else done * 100 / total
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val engine: SearchEngine,
    private val orchestrator: IndexOrchestrator,
    private val mediaItemDao: MediaItemDao,
    private val contentDao: ai.dusty.finderplus.db.dao.ContentDao,
    private val voteDao: ai.dusty.finderplus.db.dao.VoteDao,
    private val reviewGroups: ai.dusty.finderplus.index.ReviewGroups,
    private val assistPrefs: ai.dusty.finderplus.index.AssistPrefs,
    private val questionPrefs: ai.dusty.finderplus.index.QuestionPrefs,
    private val installer: ai.dusty.finderplus.models.ModelInstaller,
    private val history: SearchHistory,
    private val reranker: ai.dusty.finderplus.index.Reranker,
    private val pending: ai.dusty.finderplus.pending.PendingChanges,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    // ---- recommendations: the user's own searches, then the most common indexed labels ----

    private val emptyState = MutableStateFlow(SearchUiState.Empty(suggestions = emptyList(), recent = emptyList()))

    /** Queries already counted in this pop-up session, so a tap plus a close count once. */
    private val committed = HashSet<String>()

    /** For writes that must finish even when the pop-up closes at once. */
    private val lasting = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun refreshRecommendations() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val recent = history.rank(RECENT_LIMIT).map { it.query }
        val taken = recent.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val fromGallery = runCatching { contentDao.topContentLabels(GALLERY_LIMIT * 2) }.getOrDefault(emptyList())
            .map { it.label }
            .filter { it.length >= 3 && it.lowercase(java.util.Locale.ROOT) !in taken }
            .take(GALLERY_LIMIT)
        emptyState.value = SearchUiState.Empty(suggestions = fromGallery, recent = recent)
    }

    /**
     * Count [text] as a real search — called when the user acts on what it found, picks it from the
     * list, presses the keyboard's search key, or leaves the pop-up on its results.
     */
    fun commitSearch(text: String = query.value) {
        val q = text.trim()
        if (q.isEmpty() || !committed.add(q.lowercase(java.util.Locale.ROOT))) return
        // Outside the view model's scope: acting on a result often closes the pop-up straight away,
        // which would cancel the write before it ran.
        lasting.launch {
            history.record(q)
            refreshRecommendations()
        }
    }

    /** The pop-up is closing: keep the query if it actually found something. */
    fun commitIfFoundSomething() {
        if (state.value is SearchUiState.Results) commitSearch()
    }

    fun pickRecommendation(text: String) {
        query.value = text
        commitSearch(text)
    }

    fun forgetSearch(text: String) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        history.remove(text)
        committed.remove(text.lowercase(java.util.Locale.ROOT))
        refreshRecommendations()
    }

    /** One-tap setup offer, shown in the pop-up while the essential models are missing. */
    private val setupPrefs = appContext.getSharedPreferences("finder-setup", android.content.Context.MODE_PRIVATE)
    private val setupSnooze = MutableStateFlow(setupPrefs.getLong("snoozed_until", 0))

    val setup: StateFlow<SetupUi?> = combine(
        installer.queue, installer.progress, installer.installedVersion, setupSnooze,
    ) { queue, progress, _, snoozedUntil ->
        val essentials = installer.bundle(ai.dusty.finderplus.model.ModelTier.ESSENTIAL)
        val missing = essentials.filterNot { installer.isInstalled(it) }
        when {
            missing.isEmpty() -> null
            missing.any { it.id in queue } -> SetupUi.Installing(
                (progress as? ai.dusty.finderplus.models.InstallProgress.Downloading)?.let { p ->
                    ai.dusty.finderplus.model.ModelCatalog.byId(p.modelId)?.label to p.fraction
                },
                remaining = missing.count { it.id in queue },
            )
            System.currentTimeMillis() < snoozedUntil -> null
            else -> SetupUi.Offer(missing.sumOf { installer.remainingBytes(it) }, installer.onUnmeteredNetwork())
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun installEssentials() = installer.install(installer.bundle(ai.dusty.finderplus.model.ModelTier.ESSENTIAL))

    /** "Not now" hides the offer for three days, not for good: without the essentials, search can't see content. */
    fun snoozeSetup() {
        val until = System.currentTimeMillis() + 3L * 24 * 3600 * 1000
        setupPrefs.edit().putLong("snoozed_until", until).apply()
        setupSnooze.value = until
    }

    private val _assistMode = MutableStateFlow(assistPrefs.mode)
    val assistMode: StateFlow<ai.dusty.finderplus.index.AssistPrefs.Mode> = _assistMode

    val hasApiKey: Boolean get() = assistPrefs.apiKey.isNotBlank()

    private val _assistProvider = MutableStateFlow(assistPrefs.provider)
    val assistProvider: StateFlow<ai.dusty.finderplus.index.CloudProvider> = _assistProvider

    private val _cloudModel = MutableStateFlow(assistPrefs.cloudModel)
    val cloudModel: StateFlow<String> = _cloudModel

    private val _askUnsure = MutableStateFlow(questionPrefs.askLowConfidence)
    val askUnsure: StateFlow<Boolean> = _askUnsure

    /** Whether labels the describe model was merely unsure of are asked about too. */
    fun setAskUnsure(ask: Boolean) {
        questionPrefs.askLowConfidence = ask
        _askUnsure.value = ask
        refreshReviewCount()
    }

    fun setAssistMode(mode: ai.dusty.finderplus.index.AssistPrefs.Mode) {
        assistPrefs.mode = mode
        _assistMode.value = mode
    }

    fun setAssistProvider(p: ai.dusty.finderplus.index.CloudProvider) {
        assistPrefs.provider = p
        _assistProvider.value = p
        _cloudModel.value = assistPrefs.cloudModel
    }

    fun setCloudModel(m: String) {
        assistPrefs.setModelFor(assistPrefs.provider, m)
        _cloudModel.value = assistPrefs.cloudModel
    }

    fun setApiKey(key: String) = assistPrefs.setApiKeyFor(assistPrefs.provider, key)

    fun keySavedForCurrentProvider(): Boolean = assistPrefs.apiKey.isNotBlank()

    private val _ollamaUrl = MutableStateFlow(assistPrefs.ollamaUrl)
    val ollamaUrl: StateFlow<String> = _ollamaUrl

    fun setOllamaUrl(url: String) {
        assistPrefs.ollamaUrl = url
        _ollamaUrl.value = assistPrefs.ollamaUrl
    }

    /** Everything the assist panel shows: queue depth, live run progress, what the judge applied. */
    data class AssistStatus(
        val pending: Int,
        val run: ai.dusty.finderplus.index.AssistPrefs.Status,
        val judged: List<ai.dusty.finderplus.db.dao.LabelCount>,
    )

    private val _assistStatus = MutableStateFlow<AssistStatus?>(null)
    val assistStatus: StateFlow<AssistStatus?> = _assistStatus

    /** Cheap enough to poll while the panel is open — three indexed queries and a prefs read. */
    fun refreshAssistStatus() = viewModelScope.launch {
        _assistStatus.value = runCatching {
            AssistStatus(
                pending = reviewGroups.pendingCount(),
                run = assistPrefs.status(),
                judged = reviewGroups.judgedLabels(),
            )
        }.getOrNull()
    }

    /** Take back everything the judge said about one label. */
    fun revertAiLabel(label: String) = viewModelScope.launch {
        val purged = runCatching { reviewGroups.revertJudgedLabel(label) }.getOrDefault(0)
        _effects.emit(ai.dusty.finderplus.ui.contract.SearchEffect.Toast("Reverted \"$label\" on $purged items"))
        refreshAssistStatus()
    }

    /** A label the user typed onto the previewed item. */
    /** Votes and label edits waiting to be saved. */
    val pendingSummary: StateFlow<ai.dusty.finderplus.pending.PendingChanges.Summary> = pending.summary

    /** Hold a label the user added in the preview until changes are saved. */
    fun addLabel(itemId: Long, label: String) = viewModelScope.launch {
        runCatching { pending.editLabel(itemId, label, ai.dusty.finderplus.db.entity.PendingLabelEntity.ADD) }
        reloadPending(itemId)
    }

    private suspend fun reloadPending(itemId: Long) {
        val held = runCatching { pending.labelsFor(itemId) }.getOrDefault(emptyMap())
        _preview.value?.takeIf { it.result.item.id == itemId }?.let { _preview.value = it.copy(pending = held) }
    }

    /** Apply every held vote and label edit; afterwards nothing is shown for them any more. */
    fun saveChanges() = viewModelScope.launch {
        val (applied, requeued) = runCatching { pending.apply() }.getOrDefault(0 to 0)
        _effects.emit(
            ai.dusty.finderplus.ui.contract.SearchEffect.Toast(
                buildString {
                    append("Saved $applied change").append(if (applied == 1) "" else "s")
                    if (requeued > 0) append(" — re-checking $requeued similar")
                },
            )
        )
        if (requeued > 0) ai.dusty.finderplus.index.IndexWorker.enqueue(appContext)
        _myVotes.value = emptyMap()
        _preview.value?.result?.let { openPreview(it) }
        refreshReviewCount()
    }

    fun discardChanges() = viewModelScope.launch {
        runCatching { pending.discard() }
        _myVotes.value = emptyMap()
        _preview.value?.result?.let { reloadPending(it.item.id) }
    }

    /** Hand the current backlog to the configured judge. Runs in the background; UI stays free. */
    fun startAutoReview() {
        if (_assistMode.value == ai.dusty.finderplus.index.AssistPrefs.Mode.MANUAL) return
        ai.dusty.finderplus.index.JudgeWorker.enqueue(appContext)
        _effects.tryEmit(
            ai.dusty.finderplus.ui.contract.SearchEffect.Toast(
                "AI review started — unsure answers stay in your queue",
            )
        )
    }

    private val prefs = appContext.getSharedPreferences("finder-review", android.content.Context.MODE_PRIVATE)

    /** "Showing results for …" note from the engine's typo correction, or null. */
    override fun onCleared() {
        // The on-phone deep search model holds gigabytes; it is not kept once the pop-up closes.
        reranker.release()
        super.onCleared()
    }

    private val _correction = MutableStateFlow<String?>(null)
    val correction: StateFlow<String?> = _correction

    private val query = MutableStateFlow("")
    private val kindFilter = MutableStateFlow<MediaKind?>(null)

    /**
     * Open review groups, or null when the sheet is closed.
     *
     * Held as a list the sheet consumes head-first, so the next question is already loaded. Re-querying
     * after each answer would re-run face clustering, O(n^2) over every embedded face: too slow to sit
     * between two taps.
     */
    private val _review = MutableStateFlow<List<ai.dusty.finderplus.index.ReviewGroup>?>(null)
    val review: StateFlow<List<ai.dusty.finderplus.index.ReviewGroup>?> = _review

    private val _reviewCount = MutableStateFlow(0)
    val reviewCount: StateFlow<Int> = _reviewCount

    fun refreshReviewCount() = viewModelScope.launch {
        val n = runCatching { reviewGroups.pendingCount() }.getOrDefault(0)
        // Below MIN_PROMPT_COUNT the prompt stays hidden so a handful of questions never interrupts a
        // search. They are kept, and the sheet stays reachable from a long-press preview.
        _reviewCount.value = if (snoozed() || n < MIN_PROMPT_COUNT) 0 else n
    }

    fun openReview() = viewModelScope.launch {
        _review.value = runCatching { reviewGroups.groups() }.getOrDefault(emptyList())
    }

    fun closeReview() {
        _review.value = null
        undoStack.clear()
        _canUndo.value = false
        refreshReviewCount()
    }

    /**
     * One entry per answered or skipped group, newest last. Capped at [UNDO_DEPTH] so a long session
     * doesn't hold every snapshot.
     */
    private data class UndoEntry(
        val group: ai.dusty.finderplus.index.ReviewGroup,
        val answered: ai.dusty.finderplus.index.ReviewGroups.Answered?,
        val selection: Selection? = null,
    )

    /** What was selected in a group when it was answered: the items marked as not it, or the names chosen. */
    data class Selection(val marked: Set<Long> = emptySet(), val names: List<String> = emptyList())

    private val _restored = MutableStateFlow<Pair<ai.dusty.finderplus.index.ReviewGroup, Selection>?>(null)

    /** The selection to show again for a group brought back by undo. */
    val restored: StateFlow<Pair<ai.dusty.finderplus.index.ReviewGroup, Selection>?> = _restored.asStateFlow()

    private val undoStack = ArrayDeque<UndoEntry>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private fun push(entry: UndoEntry) {
        undoStack.addLast(entry)
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        _canUndo.value = true
    }

    /**
     * Take back the most recent answer or skip: the database change is reverted exactly (see
     * [ai.dusty.finderplus.index.ReviewGroups.undo]) and the question returns to the front of the queue.
     */
    fun undoLast() = viewModelScope.launch {
        val entry = undoStack.removeLastOrNull() ?: return@launch
        _canUndo.value = undoStack.isNotEmpty()
        entry.answered?.let { a ->
            runCatching {
                reviewGroups.undo(a, entry.group.members.associate { it.itemId to it.score })
            }
        }
        _restored.value = entry.selection?.let { entry.group to it }
        _review.value = listOf(entry.group) + (_review.value ?: emptyList())
        refreshReviewCount()
    }

    fun answerGroup(accepted: List<Long>, declined: List<Long>) = viewModelScope.launch {
        val head = _review.value?.firstOrNull() ?: return@launch
        val answered = runCatching { reviewGroups.answer(head, accepted, declined) }.getOrNull()
        push(UndoEntry(head, answered, Selection(marked = declined.toSet())))
        advance()
    }

    /** The names chosen for the photo at the head of the queue, best first; the rest are wrong. */
    fun answerRanking(ranked: List<String>) = viewModelScope.launch {
        val head = _review.value?.firstOrNull() ?: return@launch
        val answered = runCatching { reviewGroups.answerRanking(head, ranked) }.getOrNull()
        push(UndoEntry(head, answered, Selection(names = ranked)))
        advance()
    }

    fun namePerson(faceIds: List<Long>, name: String) = viewModelScope.launch {
        val head = _review.value?.firstOrNull()
        val answered = runCatching {
            reviewGroups.nameCluster(faceIds, name, reject = faceIds.isEmpty() || name.isBlank())
        }.getOrNull()
        if (head != null) push(UndoEntry(head, answered))
        advance()
    }

    /**
     * Remove a label the pipeline (or a past answer) got wrong. Similar labellings are queued for
     * reconsideration by the engine; the indexer is nudged so that happens in seconds, not at the
     * next scheduled run.
     */
    /**
     * Hold the removal of a label until changes are saved. Removing it again takes the removal back,
     * and removing a label added but not yet saved drops the addition.
     */
    fun removeLabel(itemId: Long, label: String) = viewModelScope.launch {
        val held = runCatching { pending.labelsFor(itemId)[label] }.getOrNull()
        val next = if (held == null) ai.dusty.finderplus.db.entity.PendingLabelEntity.REMOVE else null
        runCatching { pending.editLabel(itemId, label, next) }
        reloadPending(itemId)
    }

    /** Hold a yes or no on ONE suggestion from the preview; the same answer again takes it back. */
    fun answerSuggestion(itemId: Long, label: String, accept: Boolean) = viewModelScope.launch {
        val want = if (accept) ai.dusty.finderplus.db.entity.PendingLabelEntity.ACCEPT else ai.dusty.finderplus.db.entity.PendingLabelEntity.DECLINE
        val held = runCatching { pending.labelsFor(itemId)[label] }.getOrNull()
        runCatching { pending.editLabel(itemId, label, if (held == want) null else want) }
        reloadPending(itemId)
    }

    /**
     * "Not now" on the review prompt. It expires after six hours: the queue keeps growing while indexing
     * runs, and a permanent dismissal would silently stop the review loop.
     */
    fun snoozeReview() {
        prefs.edit().putLong(KEY_REVIEW_SNOOZE, System.currentTimeMillis() + SNOOZE_MS).apply()
        _reviewCount.value = 0
    }

    private fun snoozed(): Boolean = prefs.getLong(KEY_REVIEW_SNOOZE, 0L) > System.currentTimeMillis()

    private fun advance() {
        _review.value = _review.value?.drop(1)
        refreshReviewCount()
    }

    private val _preview = MutableStateFlow<ai.dusty.finderplus.ui.contract.PreviewUi?>(null)
    val preview: StateFlow<ai.dusty.finderplus.ui.contract.PreviewUi?> = _preview.asStateFlow()

    private val _effects = MutableSharedFlow<SearchEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private val _status = MutableStateFlow(IndexStatusUi())
    val status: StateFlow<IndexStatusUi> = _status.asStateFlow()

    val filter: StateFlow<MediaKind?> = kindFilter.asStateFlow()

    /** Deep search for the current results: whether it can be asked for, and how far it has got. */
    data class DeepSearchUi(val offer: Boolean, val checked: Int, val total: Int, val running: Boolean)

    private val _deep = MutableStateFlow<DeepSearchUi?>(null)
    val deep: StateFlow<DeepSearchUi?> = _deep.asStateFlow()

    /** The query deep search was asked for with the button, when the settings wait to be asked. */
    private val deepRequested = MutableStateFlow<String?>(null)

    fun requestDeepSearch() { deepRequested.value = query.value }

    val state: StateFlow<SearchUiState> =
        combine(query.debounce(150), kindFilter, deepRequested) { q, kind, asked -> Triple(q, kind, asked == q) }
            .flatMapLatest { (q, kind, asked) ->
                if (q.isBlank()) emptyState.also { _deep.value = null }
                else searchWithDeepRank(q, asked).map { results ->
                    // The engine sets this as a side effect of the emission being mapped, so reading
                    // it here (not in a separate flow) keeps the note in step with the results.
                    _correction.value = engine.lastCorrection
                    toUiState(q, results, kind)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Empty(emptyList(), emptyList()))

    /**
     * The engine's results, then, when deep search applies, the same results re-emitted in the vision
     * model's order as each score arrives.
     */
    private fun searchWithDeepRank(q: String, asked: Boolean) = kotlinx.coroutines.flow.flow {
        var last: List<SearchResult> = emptyList()
        _deep.value = null
        engine.search(q).collect { last = it; emit(it) }
        val deep = ai.dusty.finderplus.settings.DeepSearchSettings
        val run = when (ai.dusty.finderplus.settings.Settings[deep.WHEN]) {
            ai.dusty.finderplus.settings.RerankWhen.OFF -> false
            ai.dusty.finderplus.settings.RerankWhen.ON_REQUEST -> asked
            ai.dusty.finderplus.settings.RerankWhen.NO_KEYWORDS -> asked || !engine.lastHadKeywordMatch
            ai.dusty.finderplus.settings.RerankWhen.ALWAYS -> true
        }
        val canRun = last.isNotEmpty() && ai.dusty.finderplus.settings.Settings[deep.WHEN] != ai.dusty.finderplus.settings.RerankWhen.OFF &&
            reranker.isReady()
        if (!canRun) return@flow
        if (!run) { _deep.value = DeepSearchUi(offer = true, checked = 0, total = 0, running = false); return@flow }
        val total = minOf(last.size, ai.dusty.finderplus.settings.Settings[deep.TOP].toInt())
        val dropBelow = ai.dusty.finderplus.settings.Settings.valueOrNull(deep.DROP_BELOW)?.toFloat()?.div(10f)
        val scores = HashMap<Long, Float>()
        var checked = 0
        _deep.value = DeepSearchUi(offer = false, checked = 0, total = total, running = true)
        reranker.ask(q, last) { id, reply ->
            ai.dusty.finderplus.search.DeepRank.parseScore(reply)?.let { scores[id] = it }
            checked++
            _deep.value = DeepSearchUi(offer = false, checked = checked, total = total, running = checked < total)
            val byId = last.associateBy { it.item.id }
            emit(ai.dusty.finderplus.search.DeepRank.order(last.map { it.item.id }, scores, dropBelow).mapNotNull(byId::get))
        }
        _deep.value = DeepSearchUi(offer = false, checked = checked, total = total, running = false)
    }

    init {
        // The arrows lit for whatever is being searched.
        viewModelScope.launch {
            query.debounce(150).collect { q ->
                _myVotes.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { pending.votesFor(q) }.getOrDefault(emptyMap())
                }
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { pending.refresh() } }
        refreshRecommendations()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { engine.warmUp() }
        viewModelScope.launch { refreshCounts() }
        viewModelScope.launch {
            orchestrator.progress().collect { p ->
                _status.value = _status.value.copy(
                    running = p.status == RunStatus.RUNNING || p.status == RunStatus.SCANNING,
                    paused = p.status == RunStatus.PAUSED,
                    done = p.done,
                    total = p.total,
                    phase = p.currentPass?.uiLabel(),
                )
                if (p.status == RunStatus.DONE) refreshCounts()
            }
        }
    }

    private suspend fun refreshCounts() {
        runCatching {
            _status.value = _status.value.copy(
                indexedItems = mediaItemDao.count(),
                photos = mediaItemDao.countKind(MediaKind.IMAGE.ordinal),
                videos = mediaItemDao.countKind(MediaKind.VIDEO.ordinal),
                audio = mediaItemDao.countKind(MediaKind.AUDIO.ordinal),
            )
        }
    }

    fun onQueryChanged(text: String) { query.value = text }

    /**
     * Open the preview for [result], then fill in its tags from the database.
     *
     * Shown immediately with what the search returned; tags are filled in when their query completes,
     * so opening never waits on the database.
     */
    fun openPreview(result: SearchResult) {
        commitSearch()
        if (_preview.value?.result?.item?.id != result.item.id) {
            _preview.value = ai.dusty.finderplus.ui.contract.PreviewUi(result, body = result.copyText)
            recordBallot(result, ai.dusty.finderplus.search.Votes.PREVIEW)
        }
        viewModelScope.launch {
            val all = runCatching { contentDao.tagsForItem(result.item.id) }.getOrDefault(emptyList())
            // SUGGESTED (8) and OBJECT_REVIEW (10) await the user's verdict: they are questions, not
            // tags, and are kept apart so a guess never reads as established.
            val suggested = all.filter { it.source == 8 || it.source == 10 }
                .sortedByDescending { it.confidence }
                .map { it.label to it.confidence }
                .take(6)
            // When the face pass described the people, the detector's generic `person` says nothing more.
            val facesDescribed = all.any { it.source == ai.dusty.finderplus.model.TagSource.FACE.ordinal }
            val tags = all.filter { it.source != 8 && it.source != 10 }
                .filterNot { facesDescribed && it.source == 1 && it.label == "person" }
                .sortedWith(compareBy({ TAG_ORDER.indexOf(it.source).let { i -> if (i < 0) 99 else i } }, { -it.confidence }))
                .map { it.label }
                .distinct()
                .take(12)
            val held = runCatching { pending.labelsFor(result.item.id) }.getOrDefault(emptyMap())
            // Ignore a late result if the user already closed or switched preview.
            _preview.value?.takeIf { it.result.item.id == result.item.id }?.let {
                _preview.value = it.copy(tags = tags, suggestions = suggested, pending = held)
            }
        }
    }

    fun closePreview() { _preview.value = null }

    fun setFilter(kind: MediaKind?) { kindFilter.value = kind }

    /** A tap on a result: copy it, open it or preview it, as the settings say. */
    fun onResultTap(result: SearchResult) {
        when (ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.OpeningSettings.TAP]) {
            ai.dusty.finderplus.settings.TapAction.COPY -> copyResult(result)
            ai.dusty.finderplus.settings.TapAction.OPEN -> onOpen(result)
            ai.dusty.finderplus.settings.TapAction.PREVIEW -> openPreview(result)
        }
    }

    /** Copy the actual media to the clipboard. */
    fun copyResult(result: SearchResult) {
        commitSearch()
        _effects.tryEmit(SearchEffect.CopyToClipboard(result))
        recordBallot(result, ai.dusty.finderplus.search.Votes.TAP)
    }

    /** The arrow the user pressed on each result for the current query: 1 up, −1 down. */
    private val _myVotes = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val myVotes: StateFlow<Map<Long, Int>> = _myVotes.asStateFlow()

    /**
     * Explicit relevance votes from the arrow pair on a row or the preview; only those tap handlers may
     * call these. A vote is held, and its arrow lit for that query, until changes are saved or the next
     * index run applies it; pressing it again takes it back, and the other arrow replaces it.
     */
    fun upvote(result: SearchResult) { commitSearch(); vote(result, 1) }

    fun downvote(result: SearchResult) = vote(result, -1)

    private fun vote(result: SearchResult, direction: Int) {
        val q = query.value
        if (pending.voteKey(q).isEmpty()) return
        val id = result.item.id
        val before = _myVotes.value[id] ?: 0
        val after = if (before == direction) 0 else direction
        _myVotes.value = if (after == 0) _myVotes.value - id else _myVotes.value + (id to after)
        lasting.launch { runCatching { pending.vote(q, id, after) } }
    }

    /** Long-press / ↗ = open in the system gallery, seeking to the matched moment for A/V. */
    fun onOpen(result: SearchResult) {
        commitSearch()
        val seek = result.hits.firstNotNullOfOrNull { it.startMs }
        _effects.tryEmit(SearchEffect.OpenInGallery(result.item.uri, seek, result.item.displayName, result.item.mime))
        recordBallot(result, ai.dusty.finderplus.search.Votes.OPEN)
    }

    /**
     * Implicit relevance voting: acting on a result is a ballot. The chosen item is credited for the
     * query as typed; results displayed above it get a small debit, as seen and passed over. Scores
     * are bounded (see [ai.dusty.finderplus.search.Votes]) so history tunes order without overruling
     * relevance.
     */
    private fun recordBallot(chosen: SearchResult, weight: Float) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val key = ai.dusty.finderplus.search.Votes.queryKey(query.value)
            if (key.isEmpty()) return@runCatching
            val now = System.currentTimeMillis()
            val dao = voteDao
            fun deposit(itemId: Long, delta: Float) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    dao.accumulate(
                        key, itemId, delta, now,
                        ai.dusty.finderplus.search.Votes.MIN_SCORE,
                        ai.dusty.finderplus.search.Votes.MAX_SCORE,
                    )
                }
            }
            deposit(chosen.item.id, weight)
            // The skip debit uses the list as displayed (kind filter applied): what the user looked past.
            val shown = (state.value as? SearchUiState.Results)?.groups?.flatMap { it.results }.orEmpty()
            val idx = shown.indexOfFirst { it.item.id == chosen.item.id }
            if (idx > 0) {
                shown.take(idx).takeLast(ai.dusty.finderplus.search.Votes.SKIP_WINDOW).forEach {
                    if (it.item.id != chosen.item.id) deposit(it.item.id, ai.dusty.finderplus.search.Votes.SKIP)
                }
            }
        }
    }

    private fun toUiState(q: String, results: List<SearchResult>, kind: MediaKind?): SearchUiState {
        val filtered = if (kind == null) results else results.filter { it.item.kind == kind }
        if (filtered.isEmpty()) {
            return SearchUiState.NoResults(q, pendingItems = (_status.value.total - _status.value.done).coerceAtLeast(0))
        }
        val groups = MediaKind.entries.mapNotNull { k ->
            val forKind = filtered.filter { it.item.kind == k }
            if (forKind.isEmpty()) null else ResultGroup(k, forKind.size, forKind)
        }
        return SearchUiState.Results(groups, streaming = false)
    }

    companion object {
        /** Recent/frequent searches shown when the field is empty. */
        private const val RECENT_LIMIT = 6

        /** Common indexed labels offered beside (or, with no history yet, instead of) them. */
        private const val GALLERY_LIMIT = 6

        /**
         * Tag source ordinals in descending trust: USER, LEARNED, CONCEPT, FACE, OBJECT, LABEL,
         * CATEGORY, OCR_KEYWORD. The preview leads with the most trustworthy description of the item.
         */
        private val TAG_ORDER = listOf(4, 6, 7, 9, 1, 0, 3, 2)

        /** Questions below this count are not worth a banner; they wait in the queue instead. */
        private const val MIN_PROMPT_COUNT = 8

        private const val KEY_REVIEW_SNOOZE = "review_snooze_until"
        private const val SNOOZE_MS = 6L * 60 * 60 * 1000

        private const val UNDO_DEPTH = 8
    }
}
