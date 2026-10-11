package ai.dusty.finderplus.diagnostics

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.dusty.finderplus.FinderApp
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.DiagEventEntity
import ai.dusty.finderplus.index.PowerPolicy
import ai.dusty.finderplus.settings.Diagnostics
import ai.dusty.finderplus.ui.components.ButtonKind
import ai.dusty.finderplus.ui.components.FinderButton
import ai.dusty.finderplus.ui.components.FinderScreen
import ai.dusty.finderplus.ui.components.InfoCard
import ai.dusty.finderplus.ui.components.SectionHeader
import ai.dusty.finderplus.ui.popup.finderColorScheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What indexing actually did on this phone: time per item for each step, model load times, pauses and
 * their readings, and the phone's heat and memory now. For tuning the settings from evidence.
 */
@AndroidEntryPoint
class DiagnosticsActivity : ComponentActivity() {

    @Inject lateinit var db: FinderDatabase
    @Inject lateinit var power: PowerPolicy

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        io.launch {
            val message = runCatching {
                (application as FinderApp).diagnostics.flush()
                contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
                    w.write("# finder+ diagnostics, ${java.util.Date()}\n# at,kind,name,ms,detail\n")
                    for (e in db.diagDao().all()) {
                        w.write("${e.at},${e.kind},${csv(e.name)},${e.ms},${csv(e.detail.orEmpty())}\n")
                    }
                    // This app's own log; reading it needs no permission.
                    w.write("\n# log\n")
                    val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
                    proc.inputStream.bufferedReader().use { r -> r.copyTo(w) }
                }
                "Log exported"
            }.getOrElse { "Could not export: ${it.message}" }
            runOnUiThread { Toast.makeText(this@DiagnosticsActivity, message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ai.dusty.finderplus.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ai.dusty.finderplus.ui.ScreenDrawing.apply(this)
        setContent {
            MaterialTheme(colorScheme = finderColorScheme()) {
                DiagnosticsScreen(
                    load = {
                        (application as FinderApp).diagnostics.flush()
                        val day = System.currentTimeMillis() - DAY_MS
                        Snapshot(
                            passes = db.diagDao().recent(Diagnostics.PASS, day, 20_000),
                            loads = db.diagDao().recent(Diagnostics.LOAD, 0, 20),
                            pauses = db.diagDao().recent(Diagnostics.PAUSE, 0, 20),
                            slices = db.diagDao().recent(Diagnostics.SLICE, 0, 12),
                        )
                    },
                    now = ::readings,
                    onExport = { exportLog.launch("finder-diagnostics-${System.currentTimeMillis() / 1000}.txt") },
                    onClear = { io.launch { db.diagDao().clearAll() } },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun readings(): String {
        val h = power.heat()
        val p = power.powerState()
        return "🔋 Battery ${h.batteryC?.let { "%.1f °C".format(it) } ?: "unknown"} · ${p.levelPct}%" +
            (if (p.pluggedIn && p.discharging) " · draining on a cable" else if (p.pluggedIn) " · on a charger" else "") +
            "\n🖥️ Processor ${h.processorC?.let { "%.1f °C".format(it) } ?: "not readable on this phone"}" +
            "\nHeat level ${h.thermal.name.lowercase()} · throttling forecast ${h.headroom?.let { "%.2f".format(it) } ?: "not reported"}" +
            "\nFree memory ${power.availableMb()} MB"
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    }
}

class Snapshot(
    val passes: List<DiagEventEntity>,
    val loads: List<DiagEventEntity>,
    val pauses: List<DiagEventEntity>,
    val slices: List<DiagEventEntity>,
)

/** Per step: how many items, and the median and 90th-percentile time per item. */
internal fun perStep(passes: List<DiagEventEntity>): List<Triple<String, Int, Pair<Long, Long>>> =
    passes.groupBy { it.name }.map { (name, es) ->
        val ms = es.map { it.ms }.sorted()
        Triple(name, ms.size, ms[ms.size / 2] to ms[minOf(ms.size - 1, (ms.size * 9) / 10)])
    }.sortedByDescending { it.second * it.third.first }

@Composable
private fun DiagnosticsScreen(
    load: suspend () -> Snapshot,
    now: () -> String,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var readings by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { snap = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } }
    LaunchedEffect(Unit) { while (true) { readings = withContext(Dispatchers.IO) { runCatching(now).getOrDefault("") }; delay(2_000) } }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    FinderScreen(title = "🩺 Diagnostics", subtitle = "What indexing did on this phone, to tune the settings by.", onClose = onClose) {
        item { InfoCard(title = "🌡️ Now", body = readings) }

        item { SectionHeader("⏱️ Time per item, last 24 hours", "Median and slowest tenth, for each step.") }
        item {
            InfoCard {
                val steps = snap?.passes?.let(::perStep).orEmpty()
                if (steps.isEmpty()) Text("Nothing indexed in the last 24 hours.", style = MaterialTheme.typography.bodySmall)
                for ((name, n, t) in steps) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(name.lowercase().replace('_', ' '), style = mono, modifier = Modifier.weight(1f))
                        Text("$n items", style = mono, modifier = Modifier.width(90.dp))
                        Text("${seconds(t.first)} · ${seconds(t.second)}", style = mono)
                    }
                }
            }
        }

        item { SectionHeader("🧠 Model loads", "Most recent first.") }
        item { EventList(snap?.loads.orEmpty(), mono) { "${it.name} · ${seconds(it.ms)} · ${it.detail.orEmpty()}" } }

        item { SectionHeader("⏸️ Pauses", "When indexing stopped to wait, and the readings at that moment.") }
        item { EventList(snap?.pauses.orEmpty(), mono) { "${reason(it.name)} · ${it.detail.orEmpty()}" } }

        item { SectionHeader("📦 Batches", "How each batch of work ended.") }
        item { EventList(snap?.slices.orEmpty(), mono) { "${reason(it.name)} · ${seconds(it.ms)} · ${it.detail.orEmpty()}" } }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FinderButton("🔄 Refresh", { refresh++ }, ButtonKind.TONAL)
                FinderButton("📤 Export log…", onExport, ButtonKind.TONAL)
                FinderButton("🗑️ Clear", { onClear(); snap = null }, ButtonKind.OUTLINE)
            }
        }
    }
}

@Composable
private fun EventList(events: List<DiagEventEntity>, style: androidx.compose.ui.text.TextStyle, line: (DiagEventEntity) -> String) {
    InfoCard {
        if (events.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodySmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (e in events) Text("${ago(e.at)}  ${line(e)}", style = style)
        }
    }
}

private fun seconds(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)

private fun reason(name: String) = when (name) {
    "THERMAL" -> "too hot"
    "LOW_BATTERY" -> "battery low"
    "LOW_MEMORY" -> "memory low"
    "UNPLUGGED" -> "unplugged"
    "BUDGET" -> "batch time used"
    "NONE" -> "nothing left"
    else -> name.lowercase()
}

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}
