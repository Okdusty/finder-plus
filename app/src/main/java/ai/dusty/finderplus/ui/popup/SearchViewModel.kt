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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _assistMode = MutableStateFlow(assistPrefs.mode)
    val assistMode: StateFlow<ai.dusty.finderplus.index.AssistPrefs.Mode> = _assistMode

    val hasApiKey: Boolean get() = assistPrefs.apiKey.isNotBlank()

    private val _assistProvider = MutableStateFlow(assistPrefs.provider)
    val assistProvider: StateFlow<ai.dusty.finderplus.index.CloudProvider> = _assistProvider

    private val _cloudModel = MutableStateFlow(assistPrefs.cloudModel)
    val cloudModel: StateFlow<String> = _cloudModel

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

    /** The user types a label straight onto the previewed item — strongest supervision there is. */
    fun addLabel(itemId: Long, label: String) = viewModelScope.launch {
        runCatching { reviewGroups.addManualLabel(itemId, label) }
        _preview.value?.result?.let { if (it.item.id == itemId) openPreview(it) }
        refreshReviewCount()
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
    private val _correction = MutableStateFlow<String?>(null)
    val correction: StateFlow<String?> = _correction

    private val query = MutableStateFlow("")
    private val kindFilter = MutableStateFlow<MediaKind?>(null)

    /**
     * Open review groups, or null when the sheet is closed.
     *
     * Held as a list the sheet consumes head-first: answering removes the head so the next question is
     * already loaded. Re-querying after every answer would re-run face clustering, which is O(n^2) over
     * every embedded face and takes seconds — far too slow to sit between two taps.
     */
    private val _review = MutableStateFlow<List<ai.dusty.finderplus.index.ReviewGroup>?>(null)
    val review: StateFlow<List<ai.dusty.finderplus.index.ReviewGroup>?> = _review

    private val _reviewCount = MutableStateFlow(0)
    val reviewCount: StateFlow<Int> = _reviewCount

    fun refreshReviewCount() = viewModelScope.launch {
        val n = runCatching { reviewGroups.pendingCount() }.getOrDefault(0)
        // Below the floor the prompt stays hidden: interrupting someone's search to ask about three
        // labels is how the feature turns from an assistant into a nag. The questions keep; the sheet
        // stays reachable from any long-press preview meanwhile.
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
     * One entry per answered-or-skipped group, newest last. Bounded: an unbounded stack over a long
     * session would pin every snapshot bitmap-adjacent object alive for no benefit — nobody un-does
     * their way through a hundred answers.
     */
    private data class UndoEntry(
        val group: ai.dusty.finderplus.index.ReviewGroup,
        val answered: ai.dusty.finderplus.index.ReviewGroups.Answered?,
    )

    private val undoStack = ArrayDeque<UndoEntry>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private fun push(entry: UndoEntry) {
        undoStack.addLast(entry)
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        _canUndo.value = true
    }

    /**
     * Take back the most recent answer or skip: the database reverts (exactly — see
     * [ai.dusty.finderplus.index.ReviewGroups.undo]) and the question returns to the front of the
     * queue so the user can answer it differently.
     */
    fun undoLast() = viewModelScope.launch {
        val entry = undoStack.removeLastOrNull() ?: return@launch
        _canUndo.value = undoStack.isNotEmpty()
        entry.answered?.let { a ->
            runCatching {
                reviewGroups.undo(a, entry.group.members.associate { it.itemId to it.score })
            }
        }
        _review.value = listOf(entry.group) + (_review.value ?: emptyList())
        refreshReviewCount()
    }

    /** Drop the current question without answering — undoable, so a mis-tap costs nothing. */
    fun skipGroup() {
        val head = _review.value?.firstOrNull() ?: return
        push(UndoEntry(head, answered = null))
        _review.value = _review.value?.drop(1)
    }

    fun answerConcepts(label: String, accepted: List<Long>, declined: List<Long>) = viewModelScope.launch {
        val head = _review.value?.firstOrNull()
        val answered = runCatching { reviewGroups.answerConcepts(label, accepted, declined) }.getOrNull()
        if (head != null) push(UndoEntry(head, answered))
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
    fun removeLabel(itemId: Long, label: String) = viewModelScope.launch {
        val requeued = runCatching { reviewGroups.removeLabel(itemId, label) }.getOrDefault(0)
        _effects.emit(
            ai.dusty.finderplus.ui.contract.SearchEffect.Toast(
                if (requeued > 0) "Removed — re-checking $requeued similar" else "Removed",
            )
        )
        if (requeued > 0) ai.dusty.finderplus.index.IndexWorker.enqueue(appContext)
        _preview.value?.result?.let { if (it.item.id == itemId) openPreview(it) }
        refreshReviewCount()
    }

    /** Accept or decline ONE suggestion from the long-press preview, then refresh what it shows. */
    fun answerSuggestion(itemId: Long, label: String, accept: Boolean) = viewModelScope.launch {
        runCatching {
            if (accept) reviewGroups.answerConcepts(label, listOf(itemId), emptyList())
            else reviewGroups.answerConcepts(label, emptyList(), listOf(itemId))
        }
        refreshReviewCount()
        // Reload the open preview so the chip moves from "suggested" to "tag" (or disappears) live.
        _preview.value?.result?.let { if (it.item.id == itemId) openPreview(it) }
    }

    /**
     * "Not now" on the review prompt. Six hours rather than forever: the queue keeps growing while
     * indexing runs, and a permanent dismissal would silently orphan the whole supervised-learning
     * loop behind one tap made while busy.
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

    val state: StateFlow<SearchUiState> =
        combine(query.debounce(150), kindFilter) { q, kind -> q to kind }
            .flatMapLatest { (q, kind) ->
                if (q.isBlank()) flowOf(EMPTY)
                else engine.search(q).map { results ->
                    // The engine sets this as a side effect of the emission being mapped, so reading
                    // it here (not in a separate flow) keeps the note in step with the results.
                    _correction.value = engine.lastCorrection
                    toUiState(q, results, kind)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY)

    init {
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
     * Shown immediately with what the search already returned, and enriched a moment later — the image
     * and name are the slow part to read, so making the user wait on a tag query would only add latency
     * to something they can already see.
     */
    fun openPreview(result: SearchResult) {
        if (_preview.value?.result?.item?.id != result.item.id) {
            _preview.value = ai.dusty.finderplus.ui.contract.PreviewUi(result, body = result.copyText)
            recordBallot(result, ai.dusty.finderplus.search.Votes.PREVIEW)
        }
        viewModelScope.launch {
            val all = runCatching { contentDao.tagsForItem(result.item.id) }.getOrDefault(emptyList())
            // SUGGESTED is split out rather than mixed in: a guess awaiting the user's verdict must
            // not read as an established tag, and separating it is what lets the preview double as a
            // one-item review surface.
            val suggested = all.filter { it.source == 8 }
                .sortedByDescending { it.confidence }
                .map { it.label to it.confidence }
                .take(6)
            val tags = all.filter { it.source != 8 }
                // User labels first, then learned, then concepts, then detectors: most trustworthy
                // description of the item leads.
                .sortedWith(compareBy({ TAG_ORDER.indexOf(it.source).let { i -> if (i < 0) 99 else i } }, { -it.confidence }))
                .map { it.label }
                .distinct()
                .take(12)
            // Ignore a late result if the user already closed or switched preview.
            _preview.value?.takeIf { it.result.item.id == result.item.id }?.let {
                _preview.value = it.copy(tags = tags, suggestions = suggested)
            }
        }
    }

    fun closePreview() { _preview.value = null }

    fun setFilter(kind: MediaKind?) { kindFilter.value = kind }

    /** Tap = copy the actual media to the clipboard. */
    fun onResultTap(result: SearchResult) {
        _effects.tryEmit(SearchEffect.CopyToClipboard(result))
        recordBallot(result, ai.dusty.finderplus.search.Votes.TAP)
    }

    /**
     * Explicit relevance votes — the arrow pair on a row or the preview. Strictly a human gesture:
     * nothing but those tap handlers may call these. Deliberately no counter, no lit state, nothing
     * displayed afterwards — the vote is a ranking tuning signal ("award for that result"), not a
     * social property of the media, and it flows into the same bounded per-term score the implicit
     * ballots use.
     */
    fun upvote(result: SearchResult) { recordBallot(result, ai.dusty.finderplus.search.Votes.UP) }

    fun downvote(result: SearchResult) { recordBallot(result, ai.dusty.finderplus.search.Votes.DOWN) }

    /** Long-press / ↗ = open in the system gallery, seeking to the matched moment for A/V. */
    fun onOpen(result: SearchResult) {
        val seek = result.hits.firstNotNullOfOrNull { it.startMs }
        _effects.tryEmit(SearchEffect.OpenInGallery(result.item.uri, seek, result.item.displayName, result.item.mime))
        recordBallot(result, ai.dusty.finderplus.search.Votes.OPEN)
    }

    /**
     * Invisible relevance voting: acting on a result is the ballot. The chosen item is credited for
     * every term of the query that found it; the results displayed *above* it get a small debit —
     * they were seen and passed over. Deliberately no UI: the user searches, picks, and the ranking
     * quietly learns which answer that query meant. Ranking-side application is bounded
     * (see [ai.dusty.finderplus.search.Votes]) so history tunes order without overruling relevance.
     */
    private fun recordBallot(chosen: SearchResult, weight: Float) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val terms = ai.dusty.finderplus.search.Votes.terms(query.value)
            if (terms.isEmpty()) return@runCatching
            val now = System.currentTimeMillis()
            val dao = voteDao
            fun deposit(itemId: Long, delta: Float) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                for (t in terms) runCatching {
                    dao.accumulate(
                        t, itemId, delta, now,
                        ai.dusty.finderplus.search.Votes.MIN_SCORE,
                        ai.dusty.finderplus.search.Votes.MAX_SCORE,
                    )
                }
            }
            deposit(chosen.item.id, weight)
            // The skip debit reads the list as displayed (kind filter applied) — that is what the
            // user actually looked past.
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
        private val SUGGESTIONS = listOf(
            "kids at the beach", "invoice", "the voicemail about the rent", "type:video last summer",
        )
        private val EMPTY = SearchUiState.Empty(SUGGESTIONS, recent = emptyList())

        /**
         * Tag sources in descending trust: USER, LEARNED, CONCEPT, OBJECT, LABEL, CATEGORY,
         * OCR_KEYWORD. The preview leads with the most trustworthy description of the item, so a name
         * the user typed outranks a detector's guess.
         */
        private val TAG_ORDER = listOf(4, 6, 7, 1, 0, 3, 2)

        /** Questions below this count are not worth a banner; they wait in the queue instead. */
        private const val MIN_PROMPT_COUNT = 8

        private const val KEY_REVIEW_SNOOZE = "review_snooze_until"
        private const val SNOOZE_MS = 6L * 60 * 60 * 1000

        private const val UNDO_DEPTH = 8
    }
}
