package ai.dusty.finderplus.ui.popup

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import ai.dusty.finderplus.index.IndexWorker
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.SearchResult
import ai.dusty.finderplus.ui.ClipboardWriter
import ai.dusty.finderplus.ui.contract.ResultGroup
import ai.dusty.finderplus.ui.contract.PreviewUi
import ai.dusty.finderplus.ui.contract.SearchEffect
import ai.dusty.finderplus.ui.contract.SearchUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pop-up's palette, mapped from the Material 3 scheme so the overlay follows the wallpaper on
 * Android 12+ (see [finderColorScheme]). The names say what each colour is for in this UI.
 */
internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Dim: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val CardBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
internal val ChipBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * Dynamic (wallpaper-derived) dark scheme on Android 12+, static dark otherwise. Always dark: this
 * is a translucent overlay above the user's home screen, and a bright sheet flashing over it reads
 * as a glitch rather than a theme.
 */
@Composable
internal fun finderColorScheme() =
    if (android.os.Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(androidx.compose.ui.platform.LocalContext.current)
    else darkColorScheme()

/**
 * The translucent search pop-up. Auto-focused field → streaming results, grouped by media kind with
 * quick category filters. Tapping a result copies the actual media to the clipboard; ↗ opens it.
 * See docs/ui/WIREFRAMES.md §2.
 */
@AndroidEntryPoint
class SearchPopupActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ai.dusty.finderplus.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ai.dusty.finderplus.ui.ScreenDrawing.apply(this)
        val clipboard = ClipboardWriter(this)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            IndexWorker.enqueue(this)
            // Part of setup: indexing runs for hours, which battery optimization would keep cutting short.
            ai.dusty.finderplus.ui.BackgroundAccess.request(this)
        }

        setContent {
            MaterialTheme(colorScheme = finderColorScheme()) {
                val state by viewModel.state.collectAsState()
                val status by viewModel.status.collectAsState()
                val filter by viewModel.filter.collectAsState()
                val preview by viewModel.preview.collectAsState()
                val myVotes by viewModel.myVotes.collectAsState()
                val pendingSummary by viewModel.pendingSummary.collectAsState()
                val review by viewModel.review.collectAsState()
                val reviewCount by viewModel.reviewCount.collectAsState()
                val canUndo by viewModel.canUndo.collectAsState()
                val correction by viewModel.correction.collectAsState()
                var text by remember { mutableStateOf("") }

                LaunchedEffect(Unit) { viewModel.refreshReviewCount() }

                LaunchedEffect(Unit) {
                    viewModel.effects.collect { effect ->
                        when (effect) {
                            is SearchEffect.CopyToClipboard ->
                                toast(clipboard.copyResult(effect.result))
                            is SearchEffect.OpenInGallery -> openInGallery(effect.uri, effect.displayName, effect.mime, effect.seekMs)
                            is SearchEffect.Toast -> toast(effect.message)
                            else -> Unit
                        }
                    }
                }

                // The window spans the screen (see onCreate), so "tap outside to close" is done here: the
                // scrim closes, the card swallows its own taps.
                val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier.fillMaxSize()
                        .clickable(interactionSource = noRipple, indication = null) { finish() },
                ) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable(interactionSource = noRipple, indication = null) {},
                        shape = RoundedCornerShape(22.dp),
                        color = CardBg,
                        tonalElevation = 8.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            SearchField(text, onSubmit = { viewModel.commitSearch() }) { text = it; viewModel.onQueryChanged(it) }

                            IndexStatusBar(status)

                            if (text.isNotBlank()) {
                                FilterRow(filter, status, viewModel::setFilter)
                            }

                            correction?.let { fixed ->
                                Text(
                                    "Showing results for “$fixed”",
                                    color = Accent, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                                )
                            }

                            if (text.isBlank()) {
                                val setup by viewModel.setup.collectAsState()
                                setup?.let { s ->
                                    SetupCard(
                                        s,
                                        onInstall = {
                                            viewModel.installEssentials()
                                            ai.dusty.finderplus.ui.BackgroundAccess.request(this@SearchPopupActivity)
                                        },
                                        onMore = {
                                            startActivity(Intent(this@SearchPopupActivity,
                                                ai.dusty.finderplus.ui.settings.SettingsActivity::class.java))
                                        },
                                        onDismiss = viewModel::snoozeSetup,
                                    )
                                }
                            }

                            when (val s = state) {
                                is SearchUiState.Empty ->
                                    Recommendations(
                                        recent = s.recent,
                                        fromGallery = s.suggestions,
                                        onPick = { text = it; viewModel.pickRecommendation(it) },
                                        onForget = { viewModel.forgetSearch(it) },
                                    )
                                is SearchUiState.Results -> {
                                    val deep by viewModel.deep.collectAsState()
                                    deep?.let { DeepSearchLine(it, viewModel::requestDeepSearch) }
                                    Results(
                                        s.groups,
                                        viewModel::onResultTap, viewModel::onOpen, viewModel::openPreview,
                                        viewModel::upvote, viewModel::downvote, myVotes,
                                    )
                                }
                                is SearchUiState.NoResults -> NoResults(s.query, s.pendingItems)
                                is SearchUiState.Loading ->
                                    CircularProgressIndicator(Modifier.padding(16.dp).size(22.dp), color = Accent)
                                is SearchUiState.NeedsIndex -> Hint("Nothing indexed yet — tap Index.")
                            }

                            if (reviewCount > 0) {
                                ReviewPrompt(
                                    reviewCount,
                                    onOpen = viewModel::openReview,
                                    onDismiss = viewModel::snoozeReview,
                                )
                            }

                            if (pendingSummary.any) {
                                PendingBar(pendingSummary, viewModel::saveChanges, viewModel::discardChanges)
                            }
                            BottomBar(
                                running = status.running || status.paused,
                                onIndex = { permissionLauncher.launch(mediaPermissions()) },
                                onModels = {
                                    startActivity(
                                        Intent(this@SearchPopupActivity,
                                            ai.dusty.finderplus.ui.settings.SettingsActivity::class.java)
                                    )
                                },
                                onPrivate = {
                                    startActivity(
                                        Intent(this@SearchPopupActivity,
                                            ai.dusty.finderplus.ui.settings.PrivacyActivity::class.java)
                                    )
                                },
                                onCleanup = {
                                    startActivity(
                                        Intent(this@SearchPopupActivity,
                                            ai.dusty.finderplus.ui.cleanup.CleanupActivity::class.java)
                                    )
                                },
                                onSettings = {
                                    startActivity(
                                        Intent(this@SearchPopupActivity,
                                            ai.dusty.finderplus.ui.settings.PreferencesActivity::class.java)
                                    )
                                },
                                showHint = state is SearchUiState.Results,
                            )
                        }
                    }

                    // Layered over the card rather than navigated to: the pop-up is a transient
                    // surface, and pushing a screen would lose the query behind it.
                    review?.let { groups ->
                        val assistMode by viewModel.assistMode.collectAsState()
                        val assistProvider by viewModel.assistProvider.collectAsState()
                        val cloudModel by viewModel.cloudModel.collectAsState()
                        val assistStatus by viewModel.assistStatus.collectAsState()
                        val ollamaUrl by viewModel.ollamaUrl.collectAsState()
                        val askUnsure by viewModel.askUnsure.collectAsState()
                        val restored by viewModel.restored.collectAsState()
                        androidx.activity.compose.BackHandler(onBack = viewModel::closeReview)
                        ReviewSheet(
                            groups = groups,
                            restored = restored,
                            canUndo = canUndo,
                            assistMode = assistMode,
                            onAssistMode = viewModel::setAssistMode,
                            assistProvider = assistProvider,
                            onAssistProvider = viewModel::setAssistProvider,
                            cloudModel = cloudModel,
                            onCloudModel = viewModel::setCloudModel,
                            keySaved = viewModel.keySavedForCurrentProvider(),
                            onApiKey = viewModel::setApiKey,
                            ollamaUrl = ollamaUrl,
                            onOllamaUrl = viewModel::setOllamaUrl,
                            assistStatus = assistStatus,
                            onRefreshStatus = viewModel::refreshAssistStatus,
                            onRevertLabel = viewModel::revertAiLabel,
                            onStartAuto = viewModel::startAutoReview,
                            askUnsure = askUnsure,
                            onAskUnsure = viewModel::setAskUnsure,
                            onAnswer = viewModel::answerGroup,
                            onRank = viewModel::answerRanking,
                            onNamePerson = viewModel::namePerson,
                            onUndo = viewModel::undoLast,
                            onClose = viewModel::closeReview,
                        )
                    }

                    preview?.let { p ->
                        MediaPreview(
                            preview = p,
                            onCopy = { viewModel.copyResult(p.result); viewModel.closePreview() },
                            onOpen = { viewModel.onOpen(p.result); viewModel.closePreview() },
                            onClose = viewModel::closePreview,
                            onUpvote = { viewModel.upvote(p.result) },
                            onDownvote = { viewModel.downvote(p.result) },
                            myVote = myVotes[p.result.item.id] ?: 0,
                            pendingSummary = pendingSummary,
                            onSave = viewModel::saveChanges,
                            onDiscard = viewModel::discardChanges,
                            onSuggestion = { label, accept ->
                                viewModel.answerSuggestion(p.result.item.id, label, accept)
                            },
                            onRemoveTag = { label -> viewModel.removeLabel(p.result.item.id, label) },
                            onAddTag = { label -> viewModel.addLabel(p.result.item.id, label) },
                        )
                    }
                }
            }
        }
    }

    /** Leaving on a query that found something counts it as a search the user meant. */
    override fun onStop() {
        super.onStop()
        viewModel.commitIfFoundSomething()
    }

    /**
     * A dialog-themed window wraps its content and leaves the pop-up narrow, so the window fills the
     * screen and the card is inset by margins instead.
     */
    override fun onStart() {
        super.onStart()
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun openInGallery(uri: String, displayName: String? = null, mime: String? = null, seekMs: Long? = null) {
        runCatching {
            // Vaulted media is encrypted and outside MediaStore: stage a decrypted copy and hand the
            // viewer a content:// URI it can actually read.
            val shareable = ai.dusty.finderplus.ui.VaultAccess.shareableUri(this, uri, displayName)
                ?: throw IllegalStateException("cannot stage $uri")
            ai.dusty.finderplus.ui.MediaOpener.open(this, shareable, mime, seekMs)
        }.onFailure { toast("No app can open this") }
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
private fun SearchField(text: String, onSubmit: () -> Unit, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        placeholder = { Text("Search your gallery…", color = Dim) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Dim) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
    )
}

/** Live index status — the pop-up always says what is (and isn't) searchable yet. */
@Composable
private fun IndexStatusBar(status: IndexStatusUi) {
    val line = when {
        status.running && status.total > 0 ->
            "Indexing %,d / %,d · %d%%%s".format(
                status.done, status.total, status.percent,
                status.phase?.let { " · $it" } ?: "",
            )
        status.running -> "Scanning your gallery…"
        status.paused -> "Paused %d%% · cooling down, resumes automatically".format(status.percent)
        // Plain words, zero-count kinds omitted, so the line stays short without resorting to glyphs.
        status.indexedItems > 0 -> buildString {
            append("%,d indexed".format(status.indexedItems))
            if (status.photos > 0) append(" · %,d photos".format(status.photos))
            if (status.videos > 0) append(" · %,d videos".format(status.videos))
            if (status.audio > 0) append(" · %,d audio".format(status.audio))
        }
        else -> "Nothing indexed yet — tap Index to start"
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(line, color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status.running && status.total > 0) {
            LinearProgressIndicator(
                progress = { status.done.toFloat() / status.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                color = Accent,
                trackColor = ChipBg,
            )
        }
    }
}

/** Quick filters by media kind. */
@Composable
private fun FilterRow(
    selected: MediaKind?,
    status: IndexStatusUi,
    onSelect: (MediaKind?) -> Unit,
) {
    // Four equal chips across the width: nothing to scroll, nothing cut off.
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("🗂️ All", selected == null, Modifier.weight(1f)) { onSelect(null) }
        Chip("🖼️ Photos", selected == MediaKind.IMAGE, Modifier.weight(1f)) { onSelect(MediaKind.IMAGE) }
        Chip("🎬 Videos", selected == MediaKind.VIDEO, Modifier.weight(1f)) { onSelect(MediaKind.VIDEO) }
        Chip("🎧 Audio", selected == MediaKind.AUDIO, Modifier.weight(1f)) { onSelect(MediaKind.AUDIO) }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) Accent.copy(alpha = 0.18f) else ChipBg,
        modifier = modifier.clip(RoundedCornerShape(50)).combinedClickableCompat(onClick),
    ) {
        Text(
            label,
            color = if (active) Accent else Ink,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

/**
 * Suggestions from the user's own behaviour: recent and frequent searches, then the most common indexed
 * labels. Never canned examples; with no history and an empty index the section is hidden.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Recommendations(
    recent: List<String>,
    fromGallery: List<String>,
    onPick: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    if (recent.isEmpty() && fromGallery.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (recent.isNotEmpty()) {
            Text("🕘 Recent searches", color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            recent.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .combinedClickableCompat { onPick(q) }
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Dim, modifier = Modifier.size(18.dp))
                    Text(
                        q, color = Ink, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).combinedClickableCompat { onForget(q) },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = Dim, fontSize = 12.sp) }
                }
            }
        }
        if (fromGallery.isNotEmpty()) {
            Text(
                "From your gallery", color = Dim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, top = if (recent.isEmpty()) 0.dp else 8.dp, bottom = 6.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fromGallery.forEach { label -> Chip(label, active = false) { onPick(label) } }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Dim, modifier = Modifier.padding(16.dp), fontSize = 13.sp)

@Composable
private fun NoResults(query: String, pendingItems: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No matches for \"$query\"", color = Ink, fontSize = 14.sp)
        if (pendingItems > 0) {
            Text(
                "%,d items still to index — results improve as it runs".format(pendingItems),
                color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Results(
    groups: List<ResultGroup>,
    onTap: (SearchResult) -> Unit,
    onOpen: (SearchResult) -> Unit,
    onPreview: (SearchResult) -> Unit,
    onUpvote: (SearchResult) -> Unit,
    onDownvote: (SearchResult) -> Unit,
    myVotes: Map<Long, Int> = emptyMap(),
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(top = 6.dp)) {
        groups.forEach { group ->
            item(key = "h-${group.kind}") {
                Text(
                    "${group.kind.title()} · ${group.count}",
                    color = Dim,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                )
            }
            items(group.results, key = { it.item.id }) { r ->
                ResultRow(r, onTap, onOpen, onPreview, onUpvote, onDownvote, myVotes[r.item.id] ?: 0)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    r: SearchResult,
    onTap: (SearchResult) -> Unit,
    onOpen: (SearchResult) -> Unit,
    onPreview: (SearchResult) -> Unit,
    onUpvote: (SearchResult) -> Unit,
    onDownvote: (SearchResult) -> Unit,
    myVote: Int = 0,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // Tap must stay "copy", the app's core gesture; long-press opens the preview.
            .combinedClickable(onClick = { onTap(r) }, onLongClick = { onPreview(r) })
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r.item.uri, r.item.kind, r.hits.firstNotNullOfOrNull { it.startMs })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Content leads: the item's own names, then the few words around the match, each match in
            // the accent colour. Raw text read off an image is too noisy to lead. The file name is the
            // last resort; camera file names are serial numbers, not descriptions.
            val snippet = usefulSnippet(r.hits.firstOrNull()?.snippet)
            val title = listOfNotNull(r.keywords.joinToString(", ").ifEmpty { null }, snippet)
                .joinToString("  |  ").ifEmpty { null }
                ?: r.subtitle ?: r.item.displayName ?: "Media"
            Text(
                highlighted(title, Accent),
                color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // Date rather than size: dates are how people remember media.
            val meta = buildString {
                itemDateMs(r)?.let { append(formatDate(it)) }
                r.item.durationMs?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(formatDuration(it))
                }
                r.hits.firstNotNullOfOrNull { h -> h.startMs }?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("match @ ").append(formatDuration(it))
                }
            }
            if (meta.isNotEmpty()) {
                Text(meta, color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        // Only a vote pair and an open button per row; the row's tap and long-press cover copy and preview.
        VoteArrow(up = true, active = myVote > 0) { onUpvote(r) }
        VoteArrow(up = false, active = myVote < 0) { onDownvote(r) }
        Box(
            Modifier.size(40.dp).clip(CircleShape).combinedClickableCompat { onOpen(r) },
            contentAlignment = Alignment.Center,
        ) {
            Text("↗", color = Dim, fontSize = 14.sp)
        }
    }
}

/**
 * Vote arrow: a short pulse on each tap, and the accent colour while a vote this way waits to be saved.
 * Tapping it again takes the vote back. The 40 dp box keeps the touch target usable around the small
 * drawn triangle.
 */
@Composable
private fun VoteArrow(up: Boolean, active: Boolean = false, onVote: () -> Unit) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash) { delay(300); flash = false }
    }
    val tint by animateColorAsState(if (flash || active) Accent else Dim, label = "voteTint")
    val scale by animateFloatAsState(if (flash) 1.35f else 1f, label = "voteScale")
    Box(
        Modifier.size(40.dp).clip(CircleShape).combinedClickableCompat { flash = true; onVote() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
            val path = Path().apply {
                if (up) {
                    moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
                }
                close()
            }
            drawPath(path, tint)
        }
    }
}

/**
 * Large view of one item before acting on it. Loads a full-size thumbnail rather than reusing the
 * list's, and falls back to the kind badge when none can be produced (some audio, or a file deleted
 * since indexing).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MediaPreview(
    preview: PreviewUi,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onUpvote: () -> Unit = {},
    onDownvote: () -> Unit = {},
    onSuggestion: (label: String, accept: Boolean) -> Unit = { _, _ -> },
    onRemoveTag: (label: String) -> Unit = {},
    onAddTag: (label: String) -> Unit = {},
    myVote: Int = 0,
    pendingSummary: ai.dusty.finderplus.pending.PendingChanges.Summary = ai.dusty.finderplus.pending.PendingChanges.Summary(0, 0),
    onSave: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val r = preview.result
    val context = LocalContext.current
    // Back leaves the player first, then the preview; the pop-up stays open behind it.
    androidx.activity.compose.BackHandler(onBack = onClose)
    // The search keyboard would cover the preview and the player.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(r.item.id) { keyboard?.hide(); focus.clearFocus() }
    // Where the match is in a video or recording: the still shows it and playback starts there.
    val moment = ai.dusty.finderplus.ui.MediaOpener.startAt(r.hits.firstNotNullOfOrNull { it.startMs })
    val playable = r.item.kind == MediaKind.VIDEO || r.item.kind == MediaKind.AUDIO
    var playing by remember(r.item.id) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(initialValue = null, r.item.uri, moment) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (if (r.item.kind == MediaKind.VIDEO && moment != null) ai.dusty.finderplus.index.MediaThumbs.frameAt(context, r.item.uri, moment, 1080) else null)
                    ?: if (Build.VERSION.SDK_INT >= 29) ai.dusty.finderplus.ui.MediaImages.thumbnail(context, r.item.uri, 1080) else null
            }.getOrNull()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xE6000000))
            // Tapping the scrim dismisses; without this the only way out is the button.
            .combinedClickableCompat(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
                // A tap on the card's blank parts is not a tap on the backdrop, which closes the preview.
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
            tonalElevation = 12.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                val play = {
                    if (ai.dusty.finderplus.ui.MediaOpener.playsBuiltIn(r.item.kind)) playing = true else onOpen()
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp)
                        .clip(RoundedCornerShape(14.dp)).background(ChipBg)
                        // The whole picture plays, not only the button on it.
                        .then(if (playable) Modifier.combinedClickableCompat(play) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bmp!!.asImageBitmap(),
                            contentDescription = r.item.displayName,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            r.item.kind.fallbackLabel(),
                            color = Dim, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (playable && !playing) {
                        // Plays here, from the matching moment; the open button still hands it to another app.
                        Surface(
                            shape = CircleShape,
                            color = Color(0x99000000),
                            modifier = Modifier.align(Alignment.Center)
                                .clip(CircleShape).combinedClickableCompat(play),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow, contentDescription = "Play",
                                tint = Ink, modifier = Modifier.padding(12.dp).size(30.dp),
                            )
                        }
                    }
                }

                // Title is the item's description, with the file name as a dim caption. The vote pair
                // sits on the title row, next to what is being rated.
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.subtitle ?: r.item.displayName ?: "Media",
                            color = Ink, style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (r.subtitle != null && !r.item.displayName.isNullOrBlank()) {
                            Text(
                                r.item.displayName!!,
                                color = Dim, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                    VoteArrow(up = true, active = myVote > 0, onVote = onUpvote)
                    VoteArrow(up = false, active = myVote < 0, onVote = onDownvote)
                }
                previewMeta(r)?.let {
                    Text(it, color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }

                run {
                    // Every tag is removable in place. Removal records a negative example and re-checks
                    // similar labellings, so one correction applies wherever the same mistake was made.
                    // The trailing "+" adds a USER label.
                    var adding by remember(r.item.id) { mutableStateOf(false) }
                    var newLabel by remember(r.item.id) { mutableStateOf("") }
                    // Wrapped rather than scrolled sideways, so every label and the add control are in view.
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        preview.tags.forEach { t ->
                            // A removal waiting to be saved: struck through, and ↺ takes it back.
                            val removing = preview.pending[t] == ai.dusty.finderplus.db.entity.PendingLabelEntity.REMOVE
                            Surface(color = ChipBg, shape = RoundedCornerShape(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t, color = if (removing) Dim else Ink, fontSize = 11.sp,
                                        textDecoration = if (removing) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                        modifier = Modifier.padding(start = 9.dp, top = 5.dp, bottom = 5.dp),
                                    )
                                    Text(
                                        if (removing) "↺" else "✕", color = Dim, fontSize = 11.sp,
                                        modifier = Modifier.clip(CircleShape)
                                            .combinedClickableCompat { onRemoveTag(t) }
                                            .padding(horizontal = 7.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        // Labels added and not yet saved.
                        preview.pending.filter { (label, action) -> action == ai.dusty.finderplus.db.entity.PendingLabelEntity.ADD && label !in preview.tags }.keys.forEach { t ->
                            Surface(color = Accent.copy(alpha = 0.16f), shape = RoundedCornerShape(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "+ $t", color = Accent, fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 9.dp, top = 5.dp, bottom = 5.dp),
                                    )
                                    Text(
                                        "✕", color = Dim, fontSize = 11.sp,
                                        modifier = Modifier.clip(CircleShape)
                                            .combinedClickableCompat { onRemoveTag(t) }
                                            .padding(horizontal = 7.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        // The add control sits last; wrapping keeps it on screen however many labels there are.
                        if (adding) {
                            Surface(color = ChipBg, shape = RoundedCornerShape(9.dp)) {
                                BasicTextField(
                                    value = newLabel,
                                    onValueChange = { newLabel = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Ink, fontSize = 11.sp),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onDone = {
                                            if (newLabel.isNotBlank()) onAddTag(newLabel)
                                            newLabel = ""; adding = false
                                        },
                                    ),
                                    decorationBox = { inner ->
                                        Box(Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
                                            if (newLabel.isEmpty()) Text("new label", color = Dim, fontSize = 11.sp)
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.widthIn(min = 90.dp),
                                )
                            }
                        } else {
                            Surface(color = ChipBg, shape = RoundedCornerShape(9.dp)) {
                                Text(
                                    "+ Add label", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .combinedClickableCompat { adding = true }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }

                // Unconfirmed guesses, each answerable in place: a one-item review without opening the queue.
                if (preview.suggestions.isNotEmpty()) {
                    Text(
                        "Is this right?",
                        color = Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        preview.suggestions.forEach { (label, _) ->
                            // An answer waiting to be saved shows on its mark; the same mark again takes it back.
                            val held = preview.pending[label]
                            val yes = held == ai.dusty.finderplus.db.entity.PendingLabelEntity.ACCEPT
                            val no = held == ai.dusty.finderplus.db.entity.PendingLabelEntity.DECLINE
                            Surface(color = ChipBg, shape = RoundedCornerShape(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        label, color = if (no) Dim else Ink, fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 9.dp, top = 5.dp, bottom = 5.dp),
                                    )
                                    Text(
                                        "✓", color = if (no) Dim else Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 4.dp).clip(CircleShape)
                                            .background(if (yes) Accent.copy(alpha = 0.25f) else Color.Transparent)
                                            .combinedClickableCompat { onSuggestion(label, true) }
                                            .padding(horizontal = 7.dp, vertical = 4.dp),
                                    )
                                    Text(
                                        "✕", color = if (yes) Dim else Color(0xFFE5484D), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(end = 4.dp).clip(CircleShape)
                                            .background(if (no) Color(0x40E5484D) else Color.Transparent)
                                            .combinedClickableCompat { onSuggestion(label, false) }
                                            .padding(horizontal = 7.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                previewBody(preview.body, r.subtitle)?.let { body ->
                    Text(
                        body,
                        color = Dim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp)
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                if (pendingSummary.any) PendingBar(pendingSummary, onSave, onDiscard)

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onCopy) { Text("📋 Copy media") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onOpen) { Text("↗️ Open", color = Ink) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("✕ Close", color = Dim) }
                }
            }
        }
        if (playing) {
            ai.dusty.finderplus.ui.FullScreenPlayer(r.item.uri, r.item.displayName, moment, onClose = { playing = false })
        }
    }
}

/** Dimensions, duration, date and album — each fact once, no byte counts. */
private fun previewMeta(r: SearchResult): String? = buildString {
    val w = r.item.width ?: 0
    val h = r.item.height ?: 0
    if (w > 0 && h > 0) append("$w×$h")
    r.item.durationMs?.takeIf { it > 0 }?.let {
        if (isNotEmpty()) append(" · ")
        append(formatDuration(it))
    }
    itemDateMs(r)?.let {
        if (isNotEmpty()) append(" · ")
        append(formatDate(it))
    }
    r.item.bucketName?.takeIf { it.isNotBlank() }?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
    }
}.takeIf { it.isNotEmpty() }

/**
 * Prose for the preview sheet. When the copy text is a structured profile ("Summary: …", "Tags: …",
 * "Album: …"), only the summary is shown, minus the sentence already used as the title; plain content
 * (transcript, OCR) is shown as is. Null when nothing reads like prose.
 */
private fun previewBody(body: String?, subtitle: String?): String? {
    val text = body?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val structured = text.lineSequence().any { l -> PROFILE_MARKERS.any { l.startsWith(it) } }
    val prose = if (structured) {
        text.lineSequence().firstOrNull { it.startsWith("Summary: ") }
            ?.removePrefix("Summary: ")?.trim()
    } else {
        text
    } ?: return null
    // Drop the sentence already serving as the title, so the sheet never says one thing twice.
    val rest = if (subtitle != null && prose.startsWith(subtitle)) {
        prose.removePrefix(subtitle).trimStart('.', ' ')
    } else {
        prose
    }
    return rest.takeIf { it.length >= 4 }
}

private val PROFILE_MARKERS =
    listOf("Summary: ", "Tags: ", "Text: ", "Transcript: ", "Location: ", "Album: ")

/** A snippet may headline a row only with some length and at least one letter; bare digits explain nothing. */
private fun usefulSnippet(s: String?): String? {
    val t = s?.replace('\n', ' ')?.trim() ?: return null
    val plain = ai.dusty.finderplus.model.Highlight.strip(t)
    if (plain.length < 4 || plain.none { it.isLetter() }) return null
    return t
}

/** Display text with the parts the query matched in the accent colour, and the marks removed. */
private fun highlighted(s: String, accent: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var open = false
        for (c in s) when (c) {
            ai.dusty.finderplus.model.Highlight.START -> if (!open) {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)); open = true
            }
            ai.dusty.finderplus.model.Highlight.END -> if (open) { pop(); open = false }
            else -> append(c)
        }
        if (open) pop()
    }

/** Best-known moment for the item; MediaStore's DATE_MODIFIED arrives in seconds, so normalize. */
private fun itemDateMs(r: SearchResult): Long? =
    r.item.dateTakenMs?.takeIf { it > 0 }
        ?: r.item.dateModified.takeIf { it > 0 }
            ?.let { if (it < 10_000_000_000L) it * 1000 else it }

/** "22 Mar" within the current year, "22 Mar 2025" otherwise. */
private fun formatDate(ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val pattern =
        if (then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

/** A result's thumbnail; for a video with a matching moment, the frame at that moment. */
@Composable
private fun Thumb(uri: String, kind: MediaKind, atMs: Long? = null) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, uri, atMs) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (if (kind == MediaKind.VIDEO && atMs != null) ai.dusty.finderplus.index.MediaThumbs.frameAt(context, uri, atMs, 160) else null)
                    ?: if (Build.VERSION.SDK_INT >= 29) ai.dusty.finderplus.ui.MediaImages.thumbnail(context, uri, 160) else null
            }.getOrNull()
        }
    }
    val box = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(ChipBg)
    if (bmp != null) {
        Image(bmp!!.asImageBitmap(), contentDescription = null, modifier = box, contentScale = ContentScale.Crop)
    } else {
        Box(box, contentAlignment = Alignment.Center) {
            Text(kind.fallbackLabel(), color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BottomBar(
    running: Boolean,
    onIndex: () -> Unit,
    onModels: () -> Unit,
    onPrivate: () -> Unit,
    onCleanup: () -> Unit,
    onSettings: () -> Unit,
    showHint: Boolean,
) {
    // The hint shows only when there are results to tap.
    if (showHint) {
        val tap = when (ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.OpeningSettings.TAP]) {
            ai.dusty.finderplus.settings.TapAction.COPY -> "Tap a result to copy it"
            ai.dusty.finderplus.settings.TapAction.OPEN -> "Tap a result to open it"
            ai.dusty.finderplus.settings.TapAction.PREVIEW -> "Tap a result to preview it"
        }
        Text(
            "$tap · hold to preview",
            color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
    // Five equal tiles across the width. The only entry points to these screens; removing one hides
    // the feature.
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MenuTile("🧹", "Clean up", Modifier.weight(1f), onClick = onCleanup)
        MenuTile("🧠", "Models", Modifier.weight(1f), onClick = onModels)
        MenuTile("🔒", "Private", Modifier.weight(1f), onClick = onPrivate)
        MenuTile("⚙️", "Settings", Modifier.weight(1f), onClick = onSettings)
        MenuTile(
            if (running) "⏳" else "▶️", if (running) "Indexing" else "Index now", Modifier.weight(1f),
            accent = true, enabled = !running, onClick = onIndex,
        )
    }
}

/** One square-ish menu tile: an emoji over a one-line label. */
@Composable
private fun MenuTile(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        color = if (accent && enabled) Accent.copy(alpha = 0.16f) else ChipBg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.combinedClickableCompat(onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.6f),
    ) {
        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 17.sp)
            Text(
                label, color = if (accent) Accent else Ink, fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Votes and label edits waiting to be saved, with how to save or drop them. The next index run saves
 * them too.
 */
@Composable
private fun PendingBar(summary: ai.dusty.finderplus.pending.PendingChanges.Summary, onSave: () -> Unit, onDiscard: () -> Unit) {
    val parts = listOfNotNull(
        summary.votes.takeIf { it > 0 }?.let { "$it vote" + if (it == 1) "" else "s" },
        summary.labels.takeIf { it > 0 }?.let { "$it label change" + if (it == 1) "" else "s" },
    )
    Surface(color = Accent.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${parts.joinToString(" · ")} waiting", color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDiscard) { Text("🗑️ Discard", color = Dim, fontSize = 12.sp) }
            TextButton(onClick = onSave) { Text("💾 Save changes", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Deep search's state above the results: an offer to run it, or how far it has got. */
@Composable
private fun DeepSearchLine(d: SearchViewModel.DeepSearchUi, onAsk: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            d.offer -> TextButton(onClick = onAsk) { Text("🔬 Check these with the vision model", fontSize = 12.sp) }
            d.running -> {
                CircularProgressIndicator(Modifier.padding(start = 4.dp, end = 8.dp).size(14.dp), strokeWidth = 2.dp, color = Accent)
                Text("Checking with the vision model · ${d.checked} of ${d.total}", color = Dim, fontSize = 12.sp)
            }
            d.total > 0 -> Text(
                "Top ${d.checked} ordered by the vision model", color = Dim, fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun MediaKind.title() = when (this) {
    MediaKind.IMAGE -> "Photos"
    MediaKind.VIDEO -> "Videos"
    MediaKind.AUDIO -> "Audio"
}

/** Stand-in text when no thumbnail exists. */
private fun MediaKind.fallbackLabel() = when (this) {
    MediaKind.IMAGE -> "IMAGE"
    MediaKind.VIDEO -> "VIDEO"
    MediaKind.AUDIO -> "AUDIO"
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

/** The smart-search offer: what it enables and its download size. Gone once the essentials are installed. */
@Composable
private fun SetupCard(setup: SetupUi, onInstall: () -> Unit, onMore: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = Accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 8.dp, end = 4.dp)) {
            when (setup) {
                is SetupUi.Offer -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("✨ Turn on smart search", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Search by what's in your photos and videos — objects, text and speech — " +
                                    "not just their file names. Everything stays on this phone.",
                                color = Dim, fontSize = 11.sp,
                            )
                        }
                        Text(
                            "✕", color = Dim, fontSize = 13.sp,
                            modifier = Modifier.clip(CircleShape).combinedClickableCompat(onDismiss).padding(9.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        FilledTonalButton(onClick = onInstall, modifier = Modifier.height(34.dp)) {
                            Text("Install · ${ai.dusty.finderplus.ui.components.formatBytes(setup.bytes)}", fontSize = 12.sp)
                        }
                        TextButton(onClick = onMore) { Text("🧠 All models", fontSize = 12.sp) }
                    }
                    if (!setup.unmetered) {
                        Text(
                            "You're on mobile data — the download continues in the background on any network.",
                            color = Dim, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
                        )
                    }
                }
                is SetupUi.Installing -> {
                    Text("⏳ Setting up smart search…", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    val (label, fraction) = setup.current ?: (null to 0f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp).height(3.dp),
                        color = Accent, trackColor = ChipBg,
                    )
                    Text(
                        (label ?: "Waiting for network") + " · ${setup.remaining} left · you can keep searching",
                        color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Entry point to label review, shown only when questions are waiting. Phrased as an offer rather than
 * an error badge: the uncertain band holds the questions whose answers teach the classifier most.
 */
@Composable
private fun ReviewPrompt(count: Int, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = Accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).combinedClickableCompat(onOpen),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("🎓 Help it learn your gallery", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "$count label${if (count == 1) "" else "s"} to confirm — grouped, so one answer covers many",
                    color = Dim, fontSize = 11.sp,
                )
            }
            Text("✅ Review", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            // "Not now": a snooze of hours, not a permanent dismissal (see SearchViewModel.snoozeReview).
            Text(
                "✕", color = Dim, fontSize = 13.sp,
                modifier = Modifier.clip(CircleShape).combinedClickableCompat(onDismiss).padding(9.dp),
            )
        }
    }
}
