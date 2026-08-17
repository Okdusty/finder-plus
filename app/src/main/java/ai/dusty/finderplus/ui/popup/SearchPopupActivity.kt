package ai.rightone.finderplus.ui.popup

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
import ai.rightone.finderplus.index.IndexWorker
import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.SearchResult
import ai.rightone.finderplus.ui.ClipboardWriter
import ai.rightone.finderplus.ui.contract.ResultGroup
import ai.rightone.finderplus.ui.contract.PreviewUi
import ai.rightone.finderplus.ui.contract.SearchEffect
import ai.rightone.finderplus.ui.contract.SearchUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The popup's palette, sourced from the Material 3 color scheme so that on Android 12+ (with
 * [finderColorScheme]'s dynamic scheme) the whole overlay follows the user's wallpaper. The names
 * survive from the hand-tuned palette because they say what each colour is *for* in this UI —
 * mapping sites read better than raw scheme roles would.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clipboard = ClipboardWriter(this)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { IndexWorker.enqueue(this) }

        setContent {
            MaterialTheme(colorScheme = finderColorScheme()) {
                val state by viewModel.state.collectAsState()
                val status by viewModel.status.collectAsState()
                val filter by viewModel.filter.collectAsState()
                val preview by viewModel.preview.collectAsState()
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
                            is SearchEffect.OpenInGallery -> openInGallery(effect.uri, effect.displayName, effect.mime)
                            is SearchEffect.Toast -> toast(effect.message)
                            else -> Unit
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = CardBg,
                        tonalElevation = 8.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            SearchField(text) { text = it; viewModel.onQueryChanged(it) }

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

                            when (val s = state) {
                                is SearchUiState.Empty ->
                                    Suggestions(s.suggestions) { text = it; viewModel.onQueryChanged(it) }
                                is SearchUiState.Results ->
                                    Results(
                                        s.groups,
                                        viewModel::onResultTap, viewModel::onOpen, viewModel::openPreview,
                                        viewModel::upvote, viewModel::downvote,
                                    )
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

                            BottomBar(
                                running = status.running || status.paused,
                                onIndex = { permissionLauncher.launch(mediaPermissions()) },
                                onPrivate = {
                                    startActivity(
                                        Intent(this@SearchPopupActivity,
                                            ai.rightone.finderplus.ui.settings.PrivacyActivity::class.java)
                                    )
                                },
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
                        ReviewSheet(
                            groups = groups,
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
                            onAnswerConcept = viewModel::answerConcepts,
                            onNamePerson = viewModel::namePerson,
                            onSkip = viewModel::skipGroup,
                            onUndo = viewModel::undoLast,
                            onClose = viewModel::closeReview,
                        )
                    }

                    preview?.let { p ->
                        MediaPreview(
                            preview = p,
                            onCopy = { viewModel.onResultTap(p.result); viewModel.closePreview() },
                            onOpen = { viewModel.onOpen(p.result); viewModel.closePreview() },
                            onClose = viewModel::closePreview,
                            onUpvote = { viewModel.upvote(p.result) },
                            onDownvote = { viewModel.downvote(p.result) },
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

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun openInGallery(uri: String, displayName: String? = null, mime: String? = null) {
        runCatching {
            // Vaulted media is encrypted and outside MediaStore: stage a decrypted copy and hand the
            // viewer a content:// URI it can actually read.
            val shareable = ai.rightone.finderplus.ui.VaultAccess.shareableUri(this, uri, displayName)
                ?: throw IllegalStateException("cannot stage $uri")
            val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (shareable.scheme == "content" && mime != null) intent.setDataAndType(shareable, mime)
            else intent.data = shareable
            startActivity(intent)
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
private fun SearchField(text: String, onChange: (String) -> Unit) {
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

/** Quick category filters — the "easier categorization" of photos vs videos vs audio. */
@Composable
private fun FilterRow(
    selected: MediaKind?,
    status: IndexStatusUi,
    onSelect: (MediaKind?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("All", selected == null) { onSelect(null) }
        Chip("Photos", selected == MediaKind.IMAGE) { onSelect(MediaKind.IMAGE) }
        Chip("Videos", selected == MediaKind.VIDEO) { onSelect(MediaKind.VIDEO) }
        Chip("Audio", selected == MediaKind.AUDIO) { onSelect(MediaKind.AUDIO) }
        // End inset: if the row ever does scroll, the last chip stops short of the clipped edge
        // instead of being sliced mid-label.
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) Accent.copy(alpha = 0.18f) else ChipBg,
        modifier = Modifier.clip(RoundedCornerShape(50)).combinedClickableCompat(onClick),
    ) {
        Text(
            label,
            color = if (active) Accent else Ink,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

@Composable
private fun Suggestions(items: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Text("Try:", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        items.forEach { s ->
            TextButton(onClick = { onPick(s) }, modifier = Modifier.height(34.dp)) {
                Text("• $s", fontSize = 13.sp)
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
                ResultRow(r, onTap, onOpen, onPreview, onUpvote, onDownvote)
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
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // Tap still copies — that is the app's core gesture and moving it would be a regression.
            // Long-press previews, which is the cheap way to check "is this the right one" first.
            .combinedClickable(onClick = { onTap(r) }, onLongClick = { onPreview(r) })
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r.item.uri, r.item.kind)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Content leads: the matched snippet, else what the pipeline says the item IS. The raw
            // filename is the last resort — in a gallery it is a serial number, not a description.
            val snippet = usefulSnippet(r.hits.firstOrNull()?.snippet)
            Text(
                snippet ?: r.subtitle ?: r.item.displayName ?: "Media",
                color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // When, not how many bytes — dates are how people remember media; sizes are noise here.
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
        // One vote pair + one open affordance. Tap-row copies, long-press previews; anything more
        // per row was clutter (the old dot + expand glyph said little and explained less).
        VoteArrow(up = true) { onUpvote(r) }
        VoteArrow(up = false) { onDownvote(r) }
        Box(
            Modifier.size(40.dp).clip(CircleShape).combinedClickableCompat { onOpen(r) },
            contentAlignment = Alignment.Center,
        ) {
            Text("↗", color = Dim, fontSize = 14.sp)
        }
    }
}

/**
 * The Reddit-style pair, minus everything social: no counter, no lit state, no memory. A tap gives a
 * ~300 ms accent flash as acknowledgment and returns to rest — the vote tunes ranking, it is not a
 * displayed property of the media. Glyphs are drawn (not emoji) to stay in the dev aesthetic; the
 * 40 dp box keeps the target comfortable even though the triangle is small.
 */
@Composable
private fun VoteArrow(up: Boolean, onVote: () -> Unit) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash) { delay(300); flash = false }
    }
    val tint by animateColorAsState(if (flash) Accent else Dim, label = "voteTint")
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
 * Large look at one item before acting on it.
 *
 * Loads a real bitmap rather than reusing the 46 dp list thumbnail — the point is to see detail the row
 * cannot show. Falls back to the kind badge when the provider cannot produce a thumbnail (some audio,
 * and anything whose file has gone away since indexing).
 */
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
) {
    val r = preview.result
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, r.item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29)
                    ai.rightone.finderplus.ui.MediaImages.thumbnail(context, r.item.uri, 1080)
                else null
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
            tonalElevation = 12.dp,
        ) {
            Column(Modifier.padding(14.dp)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp)
                        .clip(RoundedCornerShape(14.dp)).background(ChipBg),
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
                    if (r.item.kind == MediaKind.VIDEO || r.item.kind == MediaKind.AUDIO) {
                        // A still frame cannot play; make it obvious that opening is what plays it.
                        Surface(
                            shape = CircleShape,
                            color = Color(0x99000000),
                            modifier = Modifier.align(Alignment.Center)
                                .clip(CircleShape).combinedClickableCompat(onOpen),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow, contentDescription = "Play",
                                tint = Ink, modifier = Modifier.padding(12.dp).size(30.dp),
                            )
                        }
                    }
                }

                // Title = what the item is (the profile's one-liner); the filename demotes to a dim
                // caption beneath it. The vote pair rides the title row — "rate this result" belongs
                // next to the thing being rated, not among the action buttons.
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
                    VoteArrow(up = true, onVote = onUpvote)
                    VoteArrow(up = false, onVote = onDownvote)
                }
                previewMeta(r)?.let {
                    Text(it, color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }

                run {
                    // Every chip removable in place. A wrong label is not merely deleted: the engine
                    // records it as a negative example and re-checks every similar labelling, so one
                    // removal here corrects the mistake wherever the same reasoning made it.
                    // The row also *accepts* labels: "+ label" opens an inline field, and a typed
                    // label is USER-grade supervision — added here precisely because this is where
                    // the user is looking when they notice one missing.
                    var adding by remember(r.item.id) { mutableStateOf(false) }
                    var newLabel by remember(r.item.id) { mutableStateOf("") }
                    val tagScroll = rememberScrollState()
                    // The "+" chip lives at the row's end; opening its field scrolls it into view so
                    // typing never happens off-screen.
                    LaunchedEffect(adding) { if (adding) tagScroll.animateScrollTo(tagScroll.maxValue) }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(tagScroll),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        preview.tags.forEach { t ->
                            Surface(color = ChipBg, shape = RoundedCornerShape(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t, color = Ink, fontSize = 11.sp,
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
                        // The add affordance sits LAST and collapsed to "+": real tags describe the
                        // item and deserve the leading positions; a control that pushed them
                        // off-screen had its priorities inverted.
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
                                    "+", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .combinedClickableCompat { adding = true }
                                        .padding(horizontal = 11.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }

                // The unconfirmed guesses, each answerable in place — the supervisor loop scaled down
                // to one item. Long-press already means "look closer"; this makes it also mean "teach",
                // without hunting for the item inside the review queue.
                if (preview.suggestions.isNotEmpty()) {
                    Text(
                        "Is this right?",
                        color = Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        preview.suggestions.forEach { (label, _) ->
                            Surface(color = ChipBg, shape = RoundedCornerShape(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        label, color = Ink, fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 9.dp, top = 5.dp, bottom = 5.dp),
                                    )
                                    Text(
                                        "✓", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(CircleShape)
                                            .combinedClickableCompat { onSuggestion(label, true) }
                                            .padding(horizontal = 7.dp, vertical = 4.dp),
                                    )
                                    Text(
                                        "✕", color = Color(0xFFE5484D), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(CircleShape)
                                            .combinedClickableCompat { onSuggestion(label, false) }
                                            .padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Only prose is displayed: the summary sentences, or the transcript/OCR when the body
                // IS that content. The raw "Tags:/Album:" profile dump stays what a tap copies — it is
                // never shown raw here.
                previewBody(preview.body, r.subtitle)?.let { body ->
                    Text(
                        body,
                        color = Dim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp)
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onCopy) { Text("Copy media") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onOpen) { Text("Open", color = Ink) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close", color = Dim) }
                }
            }
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
 * What the preview sheet displays as body text. The copy blob is often the structured profile
 * ("name / Summary: … / Tags: … / Album: …") — displaying that raw repeated every fact twice. Here:
 * summary sentences only (minus the one already used as the title), or the body verbatim when it is
 * genuine content (transcript/OCR). Returns null when nothing reads like prose.
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

/**
 * Snippets like "09" mystify more than they explain: require a little length and at least one
 * letter before a snippet may headline a row.
 */
private fun usefulSnippet(s: String?): String? {
    val t = s?.replace('\n', ' ')?.trim() ?: return null
    if (t.length < 4 || t.none { it.isLetter() }) return null
    return t
}

/** Best-known moment for the item; MediaStore's DATE_MODIFIED arrives in seconds, so normalize. */
private fun itemDateMs(r: SearchResult): Long? =
    r.item.dateTakenMs?.takeIf { it > 0 }
        ?: r.item.dateModified.takeIf { it > 0 }
            ?.let { if (it < 10_000_000_000L) it * 1000 else it }

/** "22 Mar" within the current year, "22 Mar 2025" otherwise — when, at a glance. */
private fun formatDate(ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val pattern =
        if (then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

@Composable
private fun Thumb(uri: String, kind: MediaKind) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29)
                    ai.rightone.finderplus.ui.MediaImages.thumbnail(context, uri, 160)
                else null
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

@Composable
private fun BottomBar(running: Boolean, onIndex: () -> Unit, onPrivate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tap a result to copy it", color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        // The only door to the vault settings. Without it the feature exists but cannot be found,
        // which is the same as not existing for anyone who does not read the source.
        TextButton(onClick = onPrivate) { Text("Private", fontSize = 12.sp) }
        TextButton(onClick = onIndex, enabled = !running) {
            Text(if (running) "Indexing…" else "Index now", fontSize = 12.sp)
        }
    }
}

private fun MediaKind.title() = when (this) {
    MediaKind.IMAGE -> "Photos"
    MediaKind.VIDEO -> "Videos"
    MediaKind.AUDIO -> "Audio"
}

/** Stand-in text when no thumbnail exists — plain words in place of the old glyph badges. */
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

/**
 * Entry point to the supervisor loop.
 *
 * Shown only when something is actually waiting, and phrased as an offer rather than a badge: the
 * classifier's uncertain band is not a queue of errors to clear, it is the set of questions whose answers
 * would teach it the most. Hiding it at zero keeps the pop-up what it is — a search box.
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
                Text("Help it learn your gallery", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "$count label${if (count == 1) "" else "s"} to confirm — grouped, so one answer covers many",
                    color = Dim, fontSize = 11.sp,
                )
            }
            Text("Review", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            // "Not now" — snoozes for hours rather than dismissing forever, because the queue keeps
            // growing while indexing runs and supervision should stay findable, just not insistent.
            Text(
                "✕", color = Dim, fontSize = 13.sp,
                modifier = Modifier.clip(CircleShape).combinedClickableCompat(onDismiss).padding(9.dp),
            )
        }
    }
}
