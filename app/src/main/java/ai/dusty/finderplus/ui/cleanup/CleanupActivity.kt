package ai.dusty.finderplus.ui.cleanup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import ai.dusty.finderplus.media.DuplicateGroup
import ai.dusty.finderplus.media.MediaFile
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.ui.AppLocale
import ai.dusty.finderplus.ui.MediaImages
import ai.dusty.finderplus.ui.components.ButtonKind
import ai.dusty.finderplus.ui.components.FinderButton
import ai.dusty.finderplus.ui.components.InfoCard
import ai.dusty.finderplus.ui.components.ProgressLine
import ai.dusty.finderplus.ui.components.Tone
import ai.dusty.finderplus.ui.components.formatBytes
import ai.dusty.finderplus.ui.popup.finderColorScheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free up space: duplicate copies, look-alike copies and the largest files, with the space each choice
 * frees. Tap to select, hold to open. Removal goes through Android's trash — confirmed by the system
 * and restorable for 30 days.
 */
@AndroidEntryPoint
class CleanupActivity : ComponentActivity() {

    private val vm: CleanupViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val trashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onTrashResult(r.resultCode == Activity.RESULT_OK)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.scan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ai.dusty.finderplus.ui.ScreenDrawing.apply(this)
        setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = finderColorScheme()) {
                LaunchedEffect(Unit) {
                    vm.effects.collect { e ->
                        when (e) {
                            is CleanupEffect.ConfirmTrash -> trashLauncher.launch(IntentSenderRequest.Builder(e.request.intentSender).build())
                            is CleanupEffect.Toast -> Toast.makeText(this@CleanupActivity, e.text, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                CleanupScreen(vm, onClose = { finish() }, onOpen = ::open, onGrant = { permissionLauncher.launch(mediaPermissions()) })
            }
        }
    }

    /** Hold a tile to look at the file before deciding. */
    private fun open(file: MediaFile) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
private fun CleanupScreen(vm: CleanupViewModel, onClose: () -> Unit, onOpen: (MediaFile) -> Unit, onGrant: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🧹 Free up space", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClose) { Text("✓ Done") }
                    }
                    Text(
                        "Duplicate copies and the biggest files. Tap to select, hold to open. Removed files go to the trash for 30 days.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!ui.access) {
                    item {
                        InfoCard(
                            tone = Tone.WARNING, title = "Allow access to your photos and videos",
                            body = "Needed to measure files and find copies. Nothing leaves this phone.",
                            actions = { FinderButton("✅ Allow", onGrant, ButtonKind.PRIMARY) },
                        )
                    }
                    return@LazyColumn
                }

                item { StorageCard(ui) }
                ui.message?.let { m -> item { InfoCard(tone = Tone.SUCCESS, title = m, actions = { FinderButton("👍 OK", vm::dismissMessage, ButtonKind.TEXT) }) } }

                item { Tabs(ui, vm::setTab) }

                when (ui.tab) {
                    CleanupTab.DUPLICATES -> {
                        if (ui.checking != null) {
                            item {
                                val (d, t) = ui.checking!!
                                InfoCard { ProgressLine(if (t > 0) d.toFloat() / t else null, "Comparing files with the same size… $d of $t") }
                            }
                        }
                        if (ui.duplicates.isEmpty() && ui.checking == null && !ui.loading) {
                            item { Empty("No duplicate copies", "Every file is unique, byte for byte.") }
                        }
                        if (ui.duplicates.isNotEmpty()) {
                            item {
                                InfoCard(
                                    title = "${formatBytes(ui.duplicateBytes)} in ${ui.duplicates.sumOf { it.extras.size }} extra copies",
                                    body = "Byte-for-byte identical. One copy of each is always kept — the original where we can tell.",
                                    actions = { FinderButton("☑️ Select all extra copies", vm::selectAllDuplicateExtras, ButtonKind.TONAL) },
                                )
                            }
                        }
                        items(ui.duplicates, key = { "d" + it.keep.id }) { g -> GroupCard(g, ui.selected, exact = true, vm, onOpen) }
                    }
                    CleanupTab.LOOK_ALIKES -> {
                        when {
                            !ui.lookAlikesReady -> item {
                                Empty("Available after indexing", "Look-alikes are found by what photos show. Install visual search in Models and let the index run.")
                            }
                            ui.lookAlikes.isEmpty() -> item { Empty("No look-alikes", "No resized, re-sent or burst copies were found.") }
                            else -> item {
                                InfoCard(
                                    body = "The same photo in different sizes or quality — messenger copies, exports, bursts. " +
                                        "Nothing here is selected for you; the sharpest copy of each is marked to keep.",
                                )
                            }
                        }
                        items(ui.lookAlikes, key = { "l" + it.keep.id }) { g -> GroupCard(g, ui.selected, exact = false, vm, onOpen) }
                    }
                    CleanupTab.LARGE -> {
                        item { KindFilter(ui.largeFilter, vm::setLargeFilter) }
                        items(ui.largeShown, key = { "b" + it.id }) { f -> LargeRow(f, f.id in ui.selected, { vm.toggle(f) }, { onOpen(f) }) }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            if (ui.selected.isNotEmpty()) SelectionBar(ui, vm)
        }
    }
}

@Composable
private fun StorageCard(ui: CleanupUi) {
    InfoCard {
        if (ui.loading) {
            ProgressLine(null, "Measuring your gallery…")
            return@InfoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (k in listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)) {
                Column {
                    Text(formatBytes(ui.bytesByKind[k] ?: 0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "%,d %s".format(ui.countByKind[k] ?: 0, when (k) { MediaKind.IMAGE -> "photos"; MediaKind.VIDEO -> "videos"; MediaKind.AUDIO -> "audio" }),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tabs(ui: CleanupUi, onTab: (CleanupTab) -> Unit) {
    // Equal tabs across the width, with each one's size or count under its name.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in CleanupTab.entries) {
            val badge = when (t) {
                CleanupTab.DUPLICATES -> ui.duplicates.takeIf { it.isNotEmpty() }?.let { formatBytes(ui.duplicateBytes) }
                CleanupTab.LOOK_ALIKES -> ui.lookAlikes.takeIf { it.isNotEmpty() }?.let { "${it.size}" }
                CleanupTab.LARGE -> null
            }
            val on = ui.tab == t
            Surface(
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTab(t) },
            ) {
                Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.title, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    Text(badge ?: " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun KindFilter(selected: MediaKind?, onSelect: (MediaKind?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("🗂️ All", maxLines = 1) }, modifier = Modifier.weight(1f))
        FilterChip(selected = selected == MediaKind.VIDEO, onClick = { onSelect(MediaKind.VIDEO) }, label = { Text("🎬 Videos", maxLines = 1) }, modifier = Modifier.weight(1f))
        FilterChip(selected = selected == MediaKind.IMAGE, onClick = { onSelect(MediaKind.IMAGE) }, label = { Text("🖼️ Photos", maxLines = 1) }, modifier = Modifier.weight(1f))
        FilterChip(selected = selected == MediaKind.AUDIO, onClick = { onSelect(MediaKind.AUDIO) }, label = { Text("🎧 Audio", maxLines = 1) }, modifier = Modifier.weight(1f))
    }
}

/** One set of copies: tiles in a grid, the keeper marked, the space it would free up front. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(g: DuplicateGroup, selected: Set<Long>, exact: Boolean, vm: CleanupViewModel, onOpen: (MediaFile) -> Unit) {
    val anySelected = g.files.any { it.id in selected }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (exact) "${g.files.size} copies · ${formatBytes(g.keep.size)} each" else "${g.files.size} look-alikes",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Text("frees ${formatBytes(g.reclaimable)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                g.files.forEach { f -> Tile(f, f.id in selected, keep = f.id == g.keep.id, onToggle = { vm.toggle(f) }, onOpen = { onOpen(f) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Keeping ${g.reason}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (anySelected) FinderButton("☐ Unselect", { vm.unselectGroup(g) }, ButtonKind.TEXT)
                else FinderButton("☑️ Select extras", { vm.selectExtras(g) }, ButtonKind.TEXT)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(f: MediaFile, selected: Boolean, keep: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(104.dp)) {
        Box(
            Modifier.size(104.dp).clip(shape)
                .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.error else Color.Transparent, shape)
                .combinedClickable(onClick = onToggle, onLongClick = onOpen),
        ) {
            Thumb(f, Modifier.fillMaxSize())
            when {
                selected -> Badge("Remove", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, Modifier.align(Alignment.TopEnd))
                keep -> Badge("Keep", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.align(Alignment.TopEnd))
            }
        }
        Text(f.folder, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(
            buildString {
                append(shortDate(f.bornMs))
                if (f.pixels > 0) append(" · ${f.width}×${f.height}")
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(5.dp).background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LargeRow(f: MediaFile, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onToggle, onLongClick = onOpen),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(f, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(f.folder).append(" · ").append(shortDate(f.bornMs))
                        f.durationMs?.takeIf { it > 0 }?.let { append(" · ").append(duration(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Text(formatBytes(f.size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SelectionBar(ui: CleanupUi, vm: CleanupViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${ui.selected.size} selected", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("frees ${formatBytes(ui.selectedBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FinderButton("✕ Clear", vm::clearSelection, ButtonKind.TEXT)
            FinderButton("🗑️ Move to trash", vm::trashSelected, ButtonKind.DANGER)
        }
    }
}

@Composable
private fun Empty(title: String, body: String) = InfoCard(title = title, body = body)

@Composable
private fun Thumb(f: MediaFile, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, f.uri) {
        value = withContext(Dispatchers.IO) { if (Build.VERSION.SDK_INT >= 29) MediaImages.thumbnail(context, f.uri, 256) else null }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(f.kind.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shortDate(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("d MMM yyyy", AppLocale.ENGLISH).format(Date(ms))

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
