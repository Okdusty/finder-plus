package ai.dusty.finderplus.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.dusty.finderplus.model.ModelSpec
import ai.dusty.finderplus.model.ModelTier
import ai.dusty.finderplus.models.InstallProgress
import ai.dusty.finderplus.ui.components.ButtonKind
import ai.dusty.finderplus.ui.components.ConfirmDialog
import ai.dusty.finderplus.ui.components.FinderButton
import ai.dusty.finderplus.ui.components.FinderScreen
import ai.dusty.finderplus.ui.components.InfoCard
import ai.dusty.finderplus.ui.components.ProgressLine
import ai.dusty.finderplus.ui.components.SectionHeader
import ai.dusty.finderplus.ui.components.StatusPill
import ai.dusty.finderplus.ui.components.Tone
import ai.dusty.finderplus.ui.components.content
import ai.dusty.finderplus.ui.components.formatBytes
import ai.dusty.finderplus.ui.popup.finderColorScheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Models screen. The FOSS build ships no weights, so content search depends on what is installed here:
 * one-tap setup bundles, models grouped by purpose, a background install with live progress, and
 * plain-language errors with a retry.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ai.dusty.finderplus.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ai.dusty.finderplus.ui.ScreenDrawing.apply(this)
        setContent {
            MaterialTheme(colorScheme = finderColorScheme()) {
                ModelScreen(vm, onClose = { finish() }, onPinWidget = {
                    if (!vm.pinWidget()) toast("Long-press your home screen → Widgets → finder+")
                }, onAllowBackground = { ai.dusty.finderplus.ui.BackgroundAccess.request(this) })
            }
        }
        // Network type, free space and the widget can all change while the screen sits open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { vm.refresh(); delay(5_000) }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

@Composable
private fun ModelScreen(vm: SettingsViewModel, onClose: () -> Unit, onPinWidget: () -> Unit, onAllowBackground: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var confirmRemove by remember { mutableStateOf<ModelSpec?>(null) }
    var confirmBundle by remember { mutableStateOf<Pair<ModelTier, Long>?>(null) }

    FinderScreen(
        title = "🧠 Models",
        subtitle = "On-device AI. Downloaded once, verified, then everything runs offline.",
        onClose = onClose,
    ) {
        item { StorageCard(ui) }

        if (ui.queue.isNotEmpty()) item { InstallingCard(ui, vm) }

        if (ui.essentialsMissingBytes > 0 || ui.recommendedMissingBytes > 0) {
            item {
                InfoCard(
                    tone = Tone.ACCENT,
                    title = "⚡ Quick setup",
                    body = if (ui.essentialsMissingBytes > 0)
                        "Turn on search by meaning, text reading, objects and faces in one tap. " +
                            "Add visual search and speech with the full set."
                    else "Essentials are in. Add visual search and speech transcription with the full set.",
                    actions = {
                        if (ui.essentialsMissingBytes > 0) {
                            FinderButton(
                                "Essentials · ${formatBytes(ui.essentialsMissingBytes)}",
                                { confirmOrInstall(ui, ModelTier.ESSENTIAL, ui.essentialsMissingBytes, vm) { confirmBundle = it } },
                                ButtonKind.PRIMARY,
                            )
                        }
                        if (ui.recommendedMissingBytes > ui.essentialsMissingBytes) {
                            FinderButton(
                                "Full set · ${formatBytes(ui.recommendedMissingBytes)}",
                                { confirmOrInstall(ui, ModelTier.RECOMMENDED, ui.recommendedMissingBytes, vm) { confirmBundle = it } },
                                if (ui.essentialsMissingBytes > 0) ButtonKind.OUTLINE else ButtonKind.PRIMARY,
                            )
                        }
                    },
                ) {
                    if (!ui.unmetered) {
                        Text(
                            "You're on mobile data — large downloads may cost you. Wi-Fi recommended.",
                            style = MaterialTheme.typography.labelSmall, color = Tone.WARNING.content(),
                        )
                    }
                }
            }
        }

        for ((category, rows) in ui.sections) {
            item(key = "h-${category.name}") { SectionHeader(category.title, category.blurb) }
            items(rows, key = { it.spec.id }) { row ->
                ModelRow(
                    row,
                    onInstall = { vm.install(row.spec) },
                    onCancel = { vm.cancel(row.spec) },
                    onRemove = { confirmRemove = row.spec },
                )
            }
            if (category == ai.dusty.finderplus.model.ModelCategory.SPEECH && ui.speechOptions.size > 1) {
                item { SpeechPicker(ui, vm::selectSpeech) }
            }
        }

        item { SectionHeader("🔋 Background indexing", "Indexing a gallery takes hours of work in the background.") }
        item {
            InfoCard(
                tone = if (ui.backgroundAllowed) Tone.SUCCESS else Tone.WARNING,
                title = if (ui.backgroundAllowed) "Battery optimization is off for finder+" else "Battery optimization is limiting indexing",
                body = if (ui.backgroundAllowed)
                    "Indexing can keep running in the background until it finishes."
                else "Android pauses optimized apps in the background and keeps them off the fast cores, so " +
                    "indexing stalls or crawls. Allowing unrestricted battery use lets it finish.",
                actions = if (ui.backgroundAllowed) null else ({
                    FinderButton("✅ Allow", onAllowBackground, ButtonKind.TONAL)
                }),
            )
        }

        item { SectionHeader("🏠 Home screen", "Search your gallery from anywhere.") }
        item {
            InfoCard(
                title = if (ui.widgetPlaced) "Widget is on your home screen" else "Add the search widget",
                body = if (ui.widgetPlaced)
                    "Tap it to search; its button updates the index and shows live progress."
                else "A search bar for your home screen, with live indexing progress and a pause button. " +
                    "You can also add the finder+ tile to Quick Settings.",
                actions = if (ui.widgetPlaced) null else ({
                    FinderButton(if (ui.canPinWidget) "Add widget" else "How to add it", onPinWidget, ButtonKind.TONAL)
                }),
            )
        }

        item {
            Text(
                "Every download is checked against a SHA-256 fingerprint before it is used, resumes if " +
                    "interrupted, and keeps going if you leave this screen. Models are open-licensed; " +
                    "no gallery content ever leaves the phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    confirmRemove?.let { spec ->
        ConfirmDialog(
            title = "Remove ${spec.label}?",
            body = "Frees ${formatBytes(ai.dusty.finderplus.model.ModelCatalog.bundleBytes(spec))}. Everything it already " +
                "indexed stays searchable; new items wait until you install it again.",
            confirm = "Remove",
            danger = true,
            onConfirm = { vm.remove(spec) },
            onDismiss = { confirmRemove = null },
        )
    }
    confirmBundle?.let { (tier, bytes) ->
        ConfirmDialog(
            title = "Download ${formatBytes(bytes)} on mobile data?",
            body = "You're not on Wi-Fi. The download continues in the background and resumes if the connection drops.",
            confirm = "Download",
            onConfirm = { vm.installBundle(tier) },
            onDismiss = { confirmBundle = null },
        )
    }
}

/** Big downloads on mobile data get one confirmation; on Wi-Fi they just start. */
private fun confirmOrInstall(ui: ModelsUi, tier: ModelTier, bytes: Long, vm: SettingsViewModel, ask: (Pair<ModelTier, Long>) -> Unit) {
    if (!ui.unmetered && bytes > 50_000_000) ask(tier to bytes) else vm.installBundle(tier)
}

@Composable
private fun StorageCard(ui: ModelsUi) {
    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${formatBytes(ui.footprint)} used by models", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatBytes(ui.free)} free on this phone",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(if (ui.unmetered) "Wi-Fi" else "Mobile data", if (ui.unmetered) Tone.SUCCESS else Tone.WARNING)
        }
    }
}

@Composable
private fun InstallingCard(ui: ModelsUi, vm: SettingsViewModel) {
    val current = ui.current as? InstallProgress.Downloading
    val label = current?.let { c -> ai.dusty.finderplus.model.ModelCatalog.byId(c.modelId)?.label } ?: "Preparing…"
    val more = ui.queue.size - 1
    InfoCard(
        tone = Tone.ACCENT,
        title = "Installing $label",
        actions = {
            if (more > 0) {
                Text("+$more more queued", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            } else {
                Text("Keeps going in the background", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            }
            FinderButton(if (ui.queue.size > 1) "Cancel all" else "Cancel", vm::cancelAll, ButtonKind.TEXT)
        },
    ) {
        if (current == null) {
            ProgressLine(null, "Waiting for a network connection…")
        } else {
            ProgressLine(
                if (current.verifying) null else current.fraction,
                if (current.verifying) "Verifying ${current.file}…"
                else "${formatBytes(current.received)} of ${formatBytes(current.total)} · ${current.file}",
            )
        }
    }
}

@Composable
private fun ModelRow(row: ModelRowUi, onInstall: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    val spec = row.spec
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spec.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when (spec.tier) {
                    ModelTier.ESSENTIAL -> StatusPill("Essential", Tone.ACCENT)
                    ModelTier.RECOMMENDED -> StatusPill("Recommended", Tone.NEUTRAL)
                    ModelTier.OPTIONAL -> Unit
                }
            }
            if (spec.note.isNotEmpty()) {
                Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when (val st = row.state) {
                is ModelRowUi.State.Downloading -> {
                    ProgressLine(
                        if (st.verifying) null else st.fraction,
                        if (st.verifying) "Verifying…" else "${formatBytes(st.received)} of ${formatBytes(st.total)}",
                    )
                }
                else -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatBytes(row.bundleBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when (row.state) {
                    ModelRowUi.State.Installed -> {
                        StatusPill("Installed", Tone.SUCCESS)
                        FinderButton("🗑️ Remove", onRemove, ButtonKind.TEXT)
                    }
                    ModelRowUi.State.Queued -> {
                        StatusPill("Queued", Tone.NEUTRAL)
                        FinderButton("✕ Cancel", onCancel, ButtonKind.TEXT)
                    }
                    is ModelRowUi.State.Downloading -> FinderButton("✕ Cancel", onCancel, ButtonKind.TEXT)
                    ModelRowUi.State.NotInstalled -> FinderButton(
                        when {
                            row.error != null -> "Retry"
                            row.resumeBytes > 0 -> "Resume"
                            else -> "Install"
                        },
                        onInstall,
                        ButtonKind.TONAL,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechPicker(ui: ModelsUi, onSelect: (ModelSpec) -> Unit) {
    InfoCard(title = "Use for transcription", body = "Several speech models are installed. Pick the one that runs.") {
        ui.speechOptions.forEach { spec ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(spec) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.speechSelected == spec.id, onClick = { onSelect(spec) })
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
