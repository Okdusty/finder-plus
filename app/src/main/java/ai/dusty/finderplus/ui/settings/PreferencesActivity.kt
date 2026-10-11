package ai.dusty.finderplus.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.dusty.finderplus.FinderApp
import ai.dusty.finderplus.index.AutoIndex
import ai.dusty.finderplus.index.ModelCoordinator
import ai.dusty.finderplus.index.ResetWorker
import ai.dusty.finderplus.settings.Amount
import ai.dusty.finderplus.settings.MinusOne
import ai.dusty.finderplus.settings.Pick
import ai.dusty.finderplus.settings.PrefsSettingsStore
import ai.dusty.finderplus.settings.Setting
import ai.dusty.finderplus.settings.SettingSection
import ai.dusty.finderplus.settings.SettingsCatalog
import ai.dusty.finderplus.settings.Toggle
import ai.dusty.finderplus.settings.Words
import ai.dusty.finderplus.ui.components.ButtonKind
import ai.dusty.finderplus.ui.components.ConfirmDialog
import ai.dusty.finderplus.ui.components.FinderButton
import ai.dusty.finderplus.ui.components.FinderScreen
import ai.dusty.finderplus.ui.components.InfoCard
import ai.dusty.finderplus.ui.components.SectionHeader
import ai.dusty.finderplus.ui.components.Tone
import ai.dusty.finderplus.ui.popup.finderColorScheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Every user setting, generated from [SettingsCatalog]: one section per [SettingSection], one row per
 * setting, low-level ones behind the advanced switch. Resetting the index sits at the bottom.
 */
@AndroidEntryPoint
class PreferencesActivity : ComponentActivity() {

    @Inject lateinit var coordinator: ModelCoordinator
    @Inject lateinit var db: ai.dusty.finderplus.db.FinderDatabase
    @Inject lateinit var backups: ai.dusty.finderplus.backup.Backups
    @Inject lateinit var power: ai.dusty.finderplus.index.PowerPolicy

    override fun onDestroy() {
        // The root shell is held only while a screen shows its reading.
        ai.dusty.finderplus.index.RootThermal.stop()
        super.onDestroy()
    }

    /** The live readings at the top of the screen. */
    private fun readings(): LiveHeat {
        val h = power.heat()
        val p = power.powerState()
        return LiveHeat(
            batteryC = h.batteryC, levelPct = p.levelPct, plugged = p.pluggedIn, draining = p.discharging,
            processorC = h.processorC, level = h.thermal.name.lowercase().replaceFirstChar { it.uppercase() },
            headroom = h.headroom, stopping = power.tooHotToContinue(h), waiting = power.tooHotToStart(h),
            batteryStopC = ai.dusty.finderplus.settings.Settings.valueOrNull(ai.dusty.finderplus.settings.HeatSettings.BATTERY_STOP)?.toFloat(),
        )
    }

    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())

    /** Runs a file job off the main thread and says how it went. */
    private fun fileJob(done: (Any?) -> String, block: suspend () -> Any?) = background {
        val message = runCatching { done(block()) }.getOrElse { "Could not finish: ${it.message ?: it.javaClass.simpleName}" }
        runOnUiThread { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() }
    }

    private val exportIndex = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        uri ?: return@registerForActivityResult
        fileJob({ "Index exported (${ai.dusty.finderplus.ui.components.formatBytes(it as Long)})" }) { backups.exportIndex(uri) }
    }
    private val backUpWork = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        fileJob({ "Backed up $it records, with your settings" }) { backups.backUpWork(uri) }
    }
    private val restoreWork = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        fileJob({ r ->
            r as ai.dusty.finderplus.backup.Backups.Restored
            ai.dusty.finderplus.index.IndexWorker.enqueueAutomatic(this)
            "Restored ${r.matched} records and ${r.settings} settings" + if (r.unmatched > 0) "; ${r.unmatched} were for files not on this phone" else ""
        }) { backups.restoreWork(uri) }
    }
    private val exportTraining = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        uri ?: return@registerForActivityResult
        fileJob({ "Exported $it training records" }) { backups.exportTraining(uri) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ai.dusty.finderplus.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ai.dusty.finderplus.ui.ScreenDrawing.apply(this)
        val store = (application as FinderApp).settings
        setContent {
            MaterialTheme(colorScheme = finderColorScheme()) {
                PreferencesScreen(
                    store, db, onChanged = ::applied, onRunAgain = ::runAgain, onClose = { finish() }, readings = ::readings,
                    files = DataActions(
                        exportIndex = { exportIndex.launch("finder-index-${stamp()}.db") },
                        backUp = { backUpWork.launch("finder-work-${stamp()}.json") },
                        restore = { restoreWork.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        exportTraining = { exportTraining.launch("finder-training-${stamp()}.jsonl") },
                    ),
                )
            }
        }
    }

    /** What has to happen for a change to take effect before the next index or restart. */
    private fun applied(setting: Setting<*>) {
        when (setting.section) {
            SettingSection.SCHEDULE -> AutoIndex.apply(this)
            // A model loaded with the old hardware, threads or size is unloaded, and the next item
            // loads it with the new ones.
            SettingSection.ACCELERATION, SettingSection.MEMORY -> coordinator.onPressure()
            SettingSection.PROCESSING -> {
                AutoIndex.apply(this)
                coordinator.onPressure()
            }
            // Search text is rebuilt from what the index already holds; no model runs again.
            SettingSection.LABELS -> background {
                db.mediaItemDao().markArtifactsStale()
                ai.dusty.finderplus.index.IndexWorker.enqueueAutomatic(this)
            }
            // Every item's meaning vectors are made again at the new size.
            SettingSection.MODEL_SIZES -> runAgain(ai.dusty.finderplus.model.Pass.TEXT_EMBED)
            else -> {}
        }
    }

    private fun runAgain(pass: ai.dusty.finderplus.model.Pass) = background {
        val n = db.workUnitDao().requeueAll(pass.ordinal, System.currentTimeMillis())
        android.util.Log.i("finderSettings", "queued ${pass.name} again for $n items")
        ai.dusty.finderplus.index.IndexWorker.enqueue(this)
    }

    /** Short database work that must finish even when the screen closes at once. */
    private fun background(block: suspend () -> Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            .launch { runCatching { block() } }
    }
}

/** What the phone reports about its heat and power right now. */
private class LiveHeat(
    val batteryC: Float?,
    val levelPct: Int,
    val plugged: Boolean,
    val draining: Boolean,
    val processorC: Float?,
    val level: String,
    val headroom: Float?,
    val stopping: Boolean,
    val waiting: Boolean,
    val batteryStopC: Float?,
)

/** Battery and processor temperatures, live, and whether indexing is within the heat limits. */
@Composable
private fun LiveHeatCard(readings: () -> LiveHeat, ui: android.content.SharedPreferences) {
    var now by remember { mutableStateOf<LiveHeat?>(null) }
    var useRoot by remember { mutableStateOf(ui.getBoolean(KEY_ROOT_THERMAL, false)) }
    androidx.compose.runtime.LaunchedEffect(useRoot) {
        if (useRoot) {
            val granted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.dusty.finderplus.index.RootThermal.start() }
            if (!granted) { useRoot = false; ui.edit().putBoolean(KEY_ROOT_THERMAL, false).apply() }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching(readings).getOrNull() }
            kotlinx.coroutines.delay(2_000)
        }
    }
    val r = now
    InfoCard(
        tone = when {
            r == null -> Tone.NEUTRAL
            r.stopping -> Tone.DANGER
            r.waiting -> Tone.WARNING
            else -> Tone.SUCCESS
        },
        title = "🌡️ Right now",
    ) {
        if (r == null) {
            Text("Reading…", style = MaterialTheme.typography.bodySmall)
            return@InfoCard
        }
        val power = when {
            r.plugged && r.draining -> "draining on a cable that cannot keep up"
            r.plugged -> "on a charger"
            else -> "on battery"
        }
        Text(
            "🔋 Battery  ${r.batteryC?.let { "%.1f °C".format(it) } ?: "not reported"} · ${r.levelPct}% · $power",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🖥️ Processor  ${r.processorC?.let { "%.1f °C".format(it) } ?: if (useRoot) "reading with root…" else "not readable on this phone"}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            // Phones that keep the kernel's thermal zones from apps still report it to root.
            if (r.processorC == null && !useRoot && ai.dusty.finderplus.index.RootThermal.available()) {
                TextButton(onClick = { useRoot = true; ui.edit().putBoolean(KEY_ROOT_THERMAL, true).apply() }) { Text("🔓 Read with root") }
            }
        }
        Text(
            "📈 Heat level ${r.level} · throttling forecast ${r.headroom?.let { "%.2f".format(it) } ?: "not reported"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when {
                r.stopping -> "Over the heat limits: indexing stops and waits to cool."
                r.waiting -> "Between the lines: running work continues, a new batch waits to cool."
                else -> "Within the heat limits."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The Data section's file actions, each of which asks where the file goes or comes from. */
private class DataActions(
    val exportIndex: () -> Unit,
    val backUp: () -> Unit,
    val restore: () -> Unit,
    val exportTraining: () -> Unit,
)

private const val UI_PREFS = "finder-settings-ui"
private const val KEY_ADVANCED = "advanced"
private const val KEY_ROOT_THERMAL = "root_thermal"

@Composable
private fun PreferencesScreen(
    store: PrefsSettingsStore,
    db: ai.dusty.finderplus.db.FinderDatabase,
    onChanged: (Setting<*>) -> Unit,
    onRunAgain: (ai.dusty.finderplus.model.Pass) -> Unit,
    onClose: () -> Unit,
    files: DataActions,
    readings: () -> LiveHeat,
) {
    val context = LocalContext.current
    val ui = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    var advanced by remember { mutableStateOf(ui.getBoolean(KEY_ADVANCED, false)) }
    // Read so every row recomposes after a change anywhere, including a reset.
    val revision by store.changes.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDefaults by remember { mutableStateOf(false) }
    var confirmForgetPicks by remember { mutableStateOf(false) }

    var find by remember { mutableStateOf("") }
    // A search looks through every setting, advanced ones included, by its name, description and section.
    val sections = remember(advanced, find) {
        val words = find.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        SettingsCatalog.all
            .filter { s ->
                if (words.isEmpty()) advanced || !s.advanced
                else "${s.title} ${s.summary} ${s.section.title}".lowercase().let { t -> words.all { it in t } }
            }
            .groupBy { it.section }
            .toSortedMap(compareBy { it.ordinal })
    }

    FinderScreen(
        title = "⚙️ Settings",
        subtitle = "Every change takes effect without a restart unless its description says otherwise.",
        onClose = onClose,
    ) {
        item { LiveHeatCard(readings, ui) }
        item {
            OutlinedTextField(
                value = find, onValueChange = { find = it }, singleLine = true,
                placeholder = { Text("🔎 Search settings") },
                trailingIcon = { if (find.isNotEmpty()) TextButton(onClick = { find = "" }) { Text("✕ Clear") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (find.isNotBlank() && sections.isEmpty()) {
            item { Text("No setting matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (find.isBlank()) item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🛠️ Advanced", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Shows the low-level controls: thresholds, sizes and per-model choices.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(advanced, { advanced = it; ui.edit().putBoolean(KEY_ADVANCED, it).apply() })
                }
            }
        }
        for ((section, settings) in sections) {
            item(key = "h-${section.name}") { SectionHeader(section.title, section.blurb) }
            item(key = "s-${section.name}") {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        settings.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                            SettingRow(s, store, db, revision) { onChanged(s) }
                        }
                    }
                }
            }
            if (section == SettingSection.ACCELERATION) item(key = "accel-test") { AccelerationTestCard() }
        }

        if (find.isBlank()) item {
            InfoCard(
                title = "🩺 Diagnostics",
                body = "Time per item for each step, model load times, and when and why indexing paused.",
                actions = { FinderButton("🩺 Open", { context.startActivity(android.content.Intent(context, ai.dusty.finderplus.diagnostics.DiagnosticsActivity::class.java)) }, ButtonKind.TONAL) },
            )
        }
        if (advanced) {
            item { SectionHeader("🔁 Run again", "Runs one kind of reading again on every item, at the next index. What it found before is replaced.") }
            item { RunAgainCard(onRunAgain) }
        }

        item { SectionHeader("💾 Data", "Copies of what the app holds, and starting over.") }
        item {
            InfoCard(
                title = "💼 Your work",
                body = "Labels you gave, answers, rankings, votes, label rules, settings, search history and " +
                    "private-folder rules, in one file. A restore matches each file on this phone by its id, " +
                    "name, size and date. Vault keys are not included; they have their own recovery file.",
                actions = {
                    FinderButton("💾 Back up…", files.backUp, ButtonKind.TONAL)
                    FinderButton("📥 Restore…", files.restore, ButtonKind.OUTLINE)
                },
            )
        }
        item {
            InfoCard(
                title = "🗄️ The index",
                body = "The whole database as one SQLite file: descriptions, labels, text read from media, " +
                    "transcripts and vectors. It holds what indexing read from your media, so keep it somewhere private.",
                actions = { FinderButton("📤 Export…", files.exportIndex, ButtonKind.TONAL) },
            )
        }
        item {
            InfoCard(
                title = "🎓 Training data",
                body = "Your rankings with their group-relative rewards, your answers and your votes, one record " +
                    "per line, each naming its file, for training a model elsewhere.",
                actions = { FinderButton("📤 Export…", files.exportTraining, ButtonKind.TONAL) },
            )
        }
        item {
            InfoCard(
                title = "👆 Pick history",
                body = "What copying, opening, previewing and voting taught the ranking, per query. History " +
                    "from versions that kept it per word is part of it.",
                actions = { FinderButton("🗑️ Forget…", { confirmForgetPicks = true }, ButtonKind.OUTLINE) },
            )
        }
        item {
            InfoCard(
                title = "↩️ Settings back to defaults",
                body = "Every setting on this screen returns to how the app ships.",
                actions = { FinderButton("↩️ Reset settings…", { confirmDefaults = true }, ButtonKind.OUTLINE) },
            )
        }
        item {
            InfoCard(
                tone = Tone.DANGER,
                title = "⚠️ Reset the index",
                body = "Deletes everything indexing found: descriptions, labels, text, transcripts and search " +
                    "vectors. Models, private folders and these settings stay. Indexing then starts over, " +
                    "which takes hours on a large gallery.",
                actions = { FinderButton("⚠️ Reset index…", { confirmReset = true }, ButtonKind.DANGER) },
            )
        }
    }

    if (confirmDefaults) {
        ConfirmDialog(
            title = "Reset every setting?",
            body = "All settings return to their defaults. The index is not touched.",
            confirm = "Reset settings",
            onConfirm = { store.resetAll(); SettingsCatalog.all.forEach(onChanged) },
            onDismiss = { confirmDefaults = false },
        )
    }
    if (confirmReset) ResetIndexDialog(onDismiss = { confirmReset = false })
    if (confirmForgetPicks) {
        ConfirmDialog(
            title = "Forget pick history?",
            body = "Results rank by relevance alone until you pick again. Votes waiting to be saved are dropped too.",
            confirm = "Forget",
            danger = true,
            onConfirm = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { db.voteDao().clearAllVotes(); db.voteDao().clearAllExplicit() }
                }
            },
            onDismiss = { confirmForgetPicks = false },
        )
    }
}

@Composable
private fun ResetIndexDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keepLabels by remember { mutableStateOf(true) }
    var typed by remember { mutableStateOf("") }
    val word = "reset"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset the index?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This cannot be undone. Type \"$word\" to confirm.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keepLabels = !keepLabels }) {
                    Checkbox(keepLabels, { keepLabels = it })
                    Text("Keep the labels I gave and my answers to questions")
                }
                OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            FinderButton(
                "⚠️ Reset index",
                { ResetWorker.enqueue(context, keepLabels); onDismiss() },
                ButtonKind.DANGER,
                enabled = typed.trim().equals(word, ignoreCase = true),
            )
        },
        dismissButton = { FinderButton("Cancel", onDismiss, ButtonKind.TEXT) },
    )
}

@Composable
private fun SettingRow(
    setting: Setting<*>,
    store: PrefsSettingsStore,
    db: ai.dusty.finderplus.db.FinderDatabase,
    revision: Long,
    onChanged: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val changed = remember(revision) { store.isChanged(setting) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(setting.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(setting.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (setting is Toggle) {
                Switch(store.get(setting), { store.set(setting, it); onChanged() }, Modifier.padding(start = 8.dp))
            }
        }
        when (setting) {
            is Toggle -> {}
            is Amount -> AmountField(setting, store, revision, onChanged)
            is Pick<*> -> PickChips(setting, store, revision, onChanged)
            is Words -> when {
                setting.appsFor != null -> AppChooser(setting, store, revision, onChanged)
                setting.labelRules -> LabelRulesEditor(setting, store, db, revision, onChanged)
                setting.folders -> FolderPicker(setting, store, db, revision, onChanged)
                else -> WordsField(setting, store, revision, onChanged)
            }
        }
        // Every setting shows what it ships with, and can go back to it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Default: ${defaultText(setting)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { resetOne(setting, store); onChanged() }, enabled = changed) {
                Text(if (changed) "↩️ Use default" else "✓ Is default")
            }
        }
    }
}

/** A setting's default as the row would show it. */
private fun defaultText(setting: Setting<*>): String = when (setting) {
    is Toggle -> if (setting.default) "On" else "Off"
    is Amount -> when {
        setting.default == -1.0 && setting.minusOne == MinusOne.OFF -> "Off (−1)"
        setting.default == -1.0 && setting.minusOne == MinusOne.AUTO -> "Automatic (−1)"
        else -> setting.format(setting.default) + if (setting.unit.isNotEmpty()) " ${setting.unit}" else ""
    }
    is Pick<*> -> setting.choices().firstOrNull { it.first == (setting.default as Enum<*>).name }?.second.orEmpty()
    is Words -> when {
        setting.default.isEmpty() -> if (setting.labelRules || setting.folders) "None" else "Empty"
        setting.default.length > 48 -> setting.default.take(48).trimEnd() + "…"
        else -> setting.default
    }
}

private fun resetOne(setting: Setting<*>, store: PrefsSettingsStore) {
    when (setting) {
        is Toggle -> store.set(setting, setting.default)
        is Amount -> store.set(setting, setting.default)
        is Pick<*> -> setting.resetIn(store)
        is Words -> store.set(setting, setting.default)
    }
}

private fun Amount.format(v: Double): String =
    if (v == -1.0) "-1" else if (decimals == 0) v.toLong().toString() else "%.${decimals}f".format(java.util.Locale.ROOT, v)

@Composable
private fun AmountField(setting: Amount, store: PrefsSettingsStore, revision: Long, onChanged: () -> Unit) {
    var text by remember(revision) { mutableStateOf(setting.format(store.get(setting))) }
    val parsed = text.trim().replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && setting.accepts(parsed)
    val meaning = when {
        parsed == -1.0 && setting.minusOne == MinusOne.OFF -> "Off"
        parsed == -1.0 && setting.minusOne == MinusOne.AUTO -> "Automatic"
        else -> null
    }
    val range = buildString {
        append("${setting.format(setting.min)} to ${setting.format(setting.max)}")
        if (setting.unit.isNotEmpty()) append(" ").append(setting.unit)
        when (setting.minusOne) {
            MinusOne.OFF -> append(", or −1 for off")
            MinusOne.AUTO -> append(", or −1 for automatic")
            MinusOne.NOT_ALLOWED -> {}
        }
        append(". Default ${setting.format(setting.default)}.")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.trim().replace(',', '.').toDoubleOrNull()?.takeIf(setting::accepts)?.let {
                if (it != store.get(setting)) { store.set(setting, it); onChanged() }
            }
        },
        singleLine = true,
        isError = !valid,
        suffix = { if (setting.unit.isNotEmpty() && meaning == null) Text(setting.unit) },
        supportingText = { Text(if (!valid) "Allowed: $range" else meaning?.let { "$it · $range" } ?: range) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(260.dp),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PickChips(setting: Pick<*>, store: PrefsSettingsStore, revision: Long, onChanged: () -> Unit) {
    val current = remember(revision) { setting.currentName(store) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, label) in setting.choices()) {
            FilterChip(
                selected = name == current,
                onClick = { if (name != current) { setting.selectByName(store, name); onChanged() } },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun WordsField(setting: Words, store: PrefsSettingsStore, revision: Long, onChanged: () -> Unit) {
    var text by remember(revision) { mutableStateOf(store.get(setting)) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; store.set(setting, it); onChanged() },
        singleLine = !setting.multiline,
        minLines = if (setting.multiline) 3 else 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** An app that opens [Words.appsFor], chosen from those installed. */
@Composable
private fun AppChooser(setting: Words, store: PrefsSettingsStore, revision: Long, onChanged: () -> Unit) {
    val context = LocalContext.current
    val mime = setting.appsFor ?: return
    val apps = remember(mime) { ai.dusty.finderplus.ui.MediaOpener.appsFor(context, mime) }
    val chosen = remember(revision) { store.get(setting) }
    var choosing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            apps.firstOrNull { it.first == chosen }?.second ?: if (chosen.isEmpty()) "None chosen" else "$chosen (not installed)",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        FinderButton("📱 Choose…", { choosing = true }, ButtonKind.OUTLINE)
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(setting.title) },
            text = {
                if (apps.isEmpty()) Text("No installed app opens this kind of file.")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.first }) { (pkg, label) ->
                        Text(
                            label,
                            fontWeight = if (pkg == chosen) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { store.set(setting, pkg); onChanged(); choosing = false }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { FinderButton("✕ Close", { choosing = false }, ButtonKind.TEXT) },
        )
    }
}

/** Passes that can run again, with what each reads. */
private val RERUNNABLE = listOf(
    ai.dusty.finderplus.model.Pass.OCR to "Text in photos",
    ai.dusty.finderplus.model.Pass.OBJECTS to "Captions and object names",
    ai.dusty.finderplus.model.Pass.FACES to "Faces",
    ai.dusty.finderplus.model.Pass.IMAGE_EMBED to "Visual search vectors of photos",
    ai.dusty.finderplus.model.Pass.KEYFRAMES to "Video keyframes",
    ai.dusty.finderplus.model.Pass.CONCEPTS to "Concept labels",
    ai.dusty.finderplus.model.Pass.TRANSCRIBE to "Speech",
    ai.dusty.finderplus.model.Pass.TEXT_EMBED to "Meaning search vectors",
)

@Composable
private fun RunAgainCard(onRunAgain: (ai.dusty.finderplus.model.Pass) -> Unit) {
    var confirm by remember { mutableStateOf<Pair<ai.dusty.finderplus.model.Pass, String>?>(null) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            RERUNNABLE.forEachIndexed { i, (pass, title) ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    FinderButton("🔁 Run again", { confirm = pass to title }, ButtonKind.TEXT)
                }
            }
        }
    }
    confirm?.let { (pass, title) ->
        ConfirmDialog(
            title = "Read \"$title\" again?",
            body = "Every item is read again for this at the next index, which starts now. On a large " +
                "gallery the slow kinds take hours.",
            confirm = "Run again",
            onConfirm = { onRunAgain(pass) },
            onDismiss = { confirm = null },
        )
    }
}

/** The rules as a list: each renames or merges one label, or hides it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LabelRulesEditor(
    setting: Words,
    store: PrefsSettingsStore,
    db: ai.dusty.finderplus.db.FinderDatabase,
    revision: Long,
    onChanged: () -> Unit,
) {
    val rules = remember(revision) { ai.dusty.finderplus.settings.LabelRules.parse(store.get(setting)) }
    var adding by remember { mutableStateOf(false) }
    fun save(next: Map<String, String?>) {
        store.set(setting, ai.dusty.finderplus.settings.LabelRules.format(next))
        onChanged()
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rules.isEmpty()) {
            Text("No rules.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for ((from, to) in rules) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (to == null) "$from  ·  hidden" else "$from  →  $to",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { save(rules - from) }) { Text("✕ Remove") }
            }
        }
        FinderButton("➕ Add rule…", { adding = true }, ButtonKind.OUTLINE)
    }
    if (adding) {
        val labels by androidx.compose.runtime.produceState(initialValue = emptyList<String>()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { db.contentDao().labelsByUse(500) }.getOrDefault(emptyList())
            }
        }
        var from by remember { mutableStateOf("") }
        var to by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Label rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val matches = remember(from, labels) {
                        val f = from.trim().lowercase()
                        if (f.isEmpty()) emptyList() else labels.filter { it.lowercase().contains(f) && it.lowercase() != f }.take(6)
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in matches) FilterChip(selected = false, onClick = { from = m }, label = { Text(m) })
                    }
                    OutlinedTextField(to, { to = it }, label = { Text("Becomes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Leave \"Becomes\" empty to hide the label. Giving two labels the same new name merges them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                FinderButton("💾 Save", {
                    save(rules + (from.trim().lowercase() to to.trim().ifEmpty { null }))
                    adding = false
                }, ButtonKind.PRIMARY, enabled = from.isNotBlank() && !from.contains(ai.dusty.finderplus.settings.LabelRules.ARROW))
            },
            dismissButton = { FinderButton("Cancel", { adding = false }, ButtonKind.TEXT) },
        )
    }
}

/** Folders as chips to switch on and off: those with indexed media, and any already chosen. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FolderPicker(
    setting: Words,
    store: PrefsSettingsStore,
    db: ai.dusty.finderplus.db.FinderDatabase,
    revision: Long,
    onChanged: () -> Unit,
) {
    val chosen = remember(revision) { store.get(setting).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() }
    val folders by androidx.compose.runtime.produceState(initialValue = emptyList<ai.dusty.finderplus.db.dao.FolderCount>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { db.mediaItemDao().folders() }.getOrDefault(emptyList())
        }
    }
    val known = folders.map { it.name.lowercase() }.toSet()
    val all = folders.map { it.name to it.count } + chosen.filter { it.lowercase() !in known }.map { it to null }
    if (all.isEmpty()) {
        Text("No folders indexed yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((name, count) in all) {
            val on = chosen.any { it.equals(name, ignoreCase = true) }
            FilterChip(
                selected = on,
                onClick = {
                    val next = if (on) chosen.filterNot { it.equals(name, ignoreCase = true) } else chosen + name
                    store.set(setting, next.joinToString("\n"))
                    onChanged()
                },
                label = { Text(if (count != null) "$name · $count" else name) },
            )
        }
    }
}

/**
 * Measures the processor against the accelerator for each model on this phone, keeps the accelerator
 * only where it is faster with the same results, and shows what it found.
 */
@Composable
private fun AccelerationTestCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ai.dusty.finderplus.index.AccelerationTestWorker.PREFS, Context.MODE_PRIVATE) }
    var report by remember { mutableStateOf(prefs.getString(ai.dusty.finderplus.index.AccelerationTestWorker.KEY_REPORT, null)) }
    var running by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            running = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ai.dusty.finderplus.index.AccelerationTestWorker.running(context)
            }
            report = prefs.getString(ai.dusty.finderplus.index.AccelerationTestWorker.KEY_REPORT, null)
            kotlinx.coroutines.delay(2_000)
        }
    }
    InfoCard(
        title = "🧪 Test on this phone",
        body = "Runs each model on a few of your photos and texts, on the processor and on the accelerator, " +
            "and keeps the accelerator only where it runs the whole model, gives the same results and is " +
            "clearly faster. The choices above are set from the result.",
        actions = {
            FinderButton(
                if (running) "⏳ Testing…" else "🧪 Run test",
                { ai.dusty.finderplus.index.AccelerationTestWorker.enqueue(context); running = true },
                ButtonKind.TONAL, enabled = !running,
            )
        },
    ) {
        report?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        }
    }
}
