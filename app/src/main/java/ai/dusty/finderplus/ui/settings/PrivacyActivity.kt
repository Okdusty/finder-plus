package ai.dusty.finderplus.ui.settings

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.dusty.finderplus.index.VaultEngine
import ai.dusty.finderplus.index.VaultPolicy
import ai.dusty.finderplus.index.VaultReport
import ai.dusty.finderplus.index.VaultWorker
import ai.dusty.finderplus.media.VaultCrypto
import ai.dusty.finderplus.ui.components.ButtonKind
import ai.dusty.finderplus.ui.components.CheckLine
import ai.dusty.finderplus.ui.components.ConfirmDialog
import ai.dusty.finderplus.ui.components.FinderButton
import ai.dusty.finderplus.ui.components.FinderScreen
import ai.dusty.finderplus.ui.components.InfoCard
import ai.dusty.finderplus.ui.components.ProgressLine
import ai.dusty.finderplus.ui.components.SectionHeader
import ai.dusty.finderplus.ui.components.Tone
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private folders: which media folders are encrypted and hidden, and the recovery key that protects
 * them. Shows the vault state (including *locked*, after a reset or a move to a new phone), a recovery
 * key that is verified before it counts as saved, the folders, and restore. Slow or destructive steps
 * run in [VaultWorker].
 */
@AndroidEntryPoint
class PrivacyActivity : ComponentActivity() {

    private val vm: PrivacyViewModel by viewModels()
    private var pendingSave: VaultCrypto.Export? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ai.dusty.finderplus.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ai.dusty.finderplus.ui.ScreenDrawing.apply(this)
        setContent {
            MaterialTheme(colorScheme = ai.dusty.finderplus.ui.popup.finderColorScheme()) {
                LaunchedEffect(Unit) {
                    vm.effects.collect { e ->
                        when (e) {
                            is PrivacyEffect.Toast -> toast(e.message)
                            is PrivacyEffect.PickSaveLocation -> pickSaveLocation(e.export, e.name)
                        }
                    }
                }
                PrivacyScreen(
                    vm = vm,
                    onGrantAccess = ::requestAllFiles,
                    onLoadKeyFile = ::pickKeyFile,
                    onShareKey = ::shareKey,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // returning from the permission screen must update the gate immediately
    }

    /**
     * All-files access, requested the only way Android allows: a system screen the user drives.
     * Asked for at the moment someone turns hiding on, never on first launch.
     */
    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun pickKeyFile() {
        openLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
    }

    private fun pickSaveLocation(export: VaultCrypto.Export, name: String) {
        pendingSave = export
        saveLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name)
        )
    }

    /** A key file that stays on the phone is lost with it, so sending a copy elsewhere is one tap. */
    private fun shareKey(uri: Uri) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "finder+ recovery key")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Keep a copy of your recovery key",
                )
            )
        }.onFailure { toast("No app can receive the key file") }
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val export = pendingSave
        pendingSave = null
        if (result.resultCode != Activity.RESULT_OK || uri == null || export == null) return@registerForActivityResult
        vm.writeTo(uri, export)
    }

    private val openLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        vm.loadKeyFile(bytes)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

sealed interface PrivacyEffect {
    data class Toast(val message: String) : PrivacyEffect
    data class PickSaveLocation(val export: VaultCrypto.Export, val name: String) : PrivacyEffect
}

data class PrivacyUi(
    val folders: List<VaultEngine.Folder> = emptyList(),
    val keyStatus: VaultCrypto.KeyStatus = VaultCrypto.KeyStatus.NO_KEY_YET,
    val keyId: String? = null,
    val recoverySaved: Boolean = false,
    val auto: Boolean = false,
    val allFiles: Boolean = true,
    val running: VaultWorker.Op? = null,
    val progress: Pair<Int, Int>? = null,
    val report: VaultReport? = null,
    val savedKeyUri: Uri? = null,
    val keyFileLoaded: Boolean = false,
    val working: String? = null,
    val keyMessage: Pair<String, Tone>? = null,
    val canSaveCopy: Boolean = false,
) {
    val hidden: Int get() = folders.sumOf { it.hiddenCount }
    val marked: Int get() = folders.count { it.decision == VaultPolicy.Decision.HIDE }
}

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val engine: VaultEngine,
    private val policy: VaultPolicy,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(PrivacyUi())
    val ui: StateFlow<PrivacyUi> = _ui

    private val _effects = MutableSharedFlow<PrivacyEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PrivacyEffect> = _effects

    private var keyFile: ByteArray? = null

    init {
        VaultCrypto.init(context)
        refresh()
        // Follow the vault worker: live progress while it runs, a fresh report and state when it ends.
        viewModelScope.launch {
            var wasRunning = false
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(VaultWorker.UNIQUE).collect { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                val op = running?.let { VaultWorker.opOf(it.tags) }
                val prog = running?.progress?.let { p ->
                    val t = p.getInt(VaultWorker.KEY_TOTAL, 0)
                    if (t > 0) p.getInt(VaultWorker.KEY_DONE, 0) to t else null
                }
                _ui.update { it.copy(running = op, progress = prog) }
                if (wasRunning && running == null) refresh()
                wasRunning = running != null
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val state = withContext(Dispatchers.IO) {
            val folders = runCatching { engine.folders(context) }.getOrDefault(emptyList())
            val status = runCatching { engine.keyStatus(context) }.getOrDefault(VaultCrypto.KeyStatus.NO_KEY_YET)
            Triple(folders, status, VaultCrypto.isRecoveryKeySaved(context))
        }
        _ui.update {
            it.copy(
                folders = state.first,
                keyStatus = state.second,
                keyId = VaultCrypto.activeKeyFingerprint(),
                recoverySaved = state.third,
                auto = policy.auto,
                allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(),
                report = VaultReport.last(context),
            )
        }
    }

    fun setAuto(on: Boolean) {
        if (on && !_ui.value.recoverySaved) return
        policy.auto = on
        _ui.update { it.copy(auto = on) }
    }

    fun toggleFolder(folder: VaultEngine.Folder) {
        val next = if (folder.decision == VaultPolicy.Decision.HIDE) VaultPolicy.Decision.KEEP else VaultPolicy.Decision.HIDE
        policy.set(folder.relPath, next)
        refresh()
    }

    fun run(op: VaultWorker.Op) {
        VaultWorker.enqueue(context, op)
        _ui.update { it.copy(running = op, progress = null) }
    }

    // ---- recovery key ----

    /**
     * A passphrase nobody has to invent: six words from a 280-word list of plain nouns plus a number,
     * about 55 bits — far beyond guessing against 600,000 PBKDF2 rounds, and still easy to write on
     * paper and type on a phone.
     */
    fun suggestPassphrase(): String {
        val rnd = java.security.SecureRandom()
        return (1..6).joinToString("-") { WORDS[rnd.nextInt(WORDS.size)] } + "-" + (10 + rnd.nextInt(90))
    }

    /**
     * Create a recovery file and *prove* it before calling it saved: it must open this vault with the
     * passphrase just typed, and the bytes must read back from storage identical. Only then is the
     * key marked as covered.
     */
    fun saveRecovery(passphrase: String, fileName: String) = viewModelScope.launch {
        _ui.update { it.copy(working = "Creating your recovery key…", keyMessage = null) }
        try {
            val export = withContext(Dispatchers.Default) {
                runCatching { VaultCrypto.exportRecovery(passphrase.toCharArray()) }.getOrNull()
            } ?: return@launch say("Couldn't create a recovery key — the vault may be locked", Tone.DANGER)

            val proven = withContext(Dispatchers.Default) {
                VaultCrypto.checkRecovery(export.bytes, passphrase.toCharArray()) == VaultCrypto.RecoveryCheck.OPENS_THIS_VAULT
            }
            if (!proven) return@launch say("The new key failed its self-check, so nothing was saved", Tone.DANGER)

            val name = normalizeKeyName(fileName)
            val uri = withContext(Dispatchers.IO) { writeToDownloads(export.bytes, name) }
            if (uri == null) {
                _effects.emit(PrivacyEffect.PickSaveLocation(export, name))
            } else {
                saved(uri, export, "Saved to Downloads as $name. Now send a copy somewhere off this phone.")
            }
        } finally {
            _ui.update { it.copy(working = null) }
        }
    }

    /**
     * Another copy of the key file just created, wherever the user picks (a password manager's
     * folder, a cloud drive, a USB stick). Held in memory for this screen only; it is useless without
     * the passphrase.
     */
    private var lastExport: VaultCrypto.Export? = null

    fun saveAnotherCopy(fileName: String) = viewModelScope.launch {
        val export = lastExport ?: return@launch say("Save a recovery key first", Tone.WARNING)
        _effects.emit(PrivacyEffect.PickSaveLocation(export, normalizeKeyName(fileName)))
    }

    fun writeTo(uri: Uri, export: VaultCrypto.Export) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(export.bytes) } != null &&
                    readsBack(uri, export.bytes)
            }.getOrDefault(false)
        }
        if (ok) saved(uri, export, "Recovery key saved — keep it somewhere safe")
        else say("Couldn't save the key file there", Tone.DANGER)
    }

    private fun saved(uri: Uri, export: VaultCrypto.Export, message: String) {
        VaultCrypto.markRecoveryKeySaved(context, export.keyIds)
        lastExport = export
        _ui.update { it.copy(savedKeyUri = uri, canSaveCopy = true) }
        say(message, Tone.SUCCESS)
        refresh()
    }

    fun loadKeyFile(bytes: ByteArray?) {
        keyFile = bytes
        _ui.update { it.copy(keyFileLoaded = bytes != null) }
        say(if (bytes != null) "Key file loaded — enter its passphrase" else "Couldn't read that file", if (bytes != null) Tone.ACCENT else Tone.DANGER)
    }

    /** Check a saved key file without changing anything. */
    fun testKey(passphrase: String) = viewModelScope.launch {
        val blob = keyFile ?: return@launch say("Load a key file first", Tone.WARNING)
        _ui.update { it.copy(working = "Checking the key…") }
        val r = withContext(Dispatchers.Default) { VaultCrypto.checkRecovery(blob, passphrase.toCharArray()) }
        _ui.update { it.copy(working = null) }
        when (r) {
            VaultCrypto.RecoveryCheck.OPENS_THIS_VAULT -> say("This key file and passphrase open your vault", Tone.SUCCESS)
            VaultCrypto.RecoveryCheck.OLDER_KEY -> say("This file is for an older key. Save a new recovery key.", Tone.WARNING)
            VaultCrypto.RecoveryCheck.DIFFERENT_VAULT -> say("This key file belongs to a different vault", Tone.DANGER)
            VaultCrypto.RecoveryCheck.WRONG_PASSPHRASE -> say("Wrong passphrase for this key file", Tone.DANGER)
            VaultCrypto.RecoveryCheck.NOT_A_KEY_FILE -> say("That isn't a finder+ recovery key", Tone.DANGER)
        }
    }

    /** Add the key file's keys to this phone — how a locked vault opens again. */
    fun useKey(passphrase: String) = viewModelScope.launch {
        val blob = keyFile ?: return@launch say("Load a key file first", Tone.WARNING)
        _ui.update { it.copy(working = "Unlocking…") }
        val wasLocked = _ui.value.keyStatus == VaultCrypto.KeyStatus.LOCKED
        val r = withContext(Dispatchers.Default) {
            val samples = engine.blobFiles(context).shuffled().take(8)
            VaultCrypto.importRecovery(blob, passphrase.toCharArray(), samples)
        }
        _ui.update { it.copy(working = null) }
        when (r) {
            VaultCrypto.ImportResult.OK -> {
                keyFile = null
                _ui.update { it.copy(keyFileLoaded = false) }
                say(if (wasLocked) "Vault unlocked — your hidden files open again" else "Key added to this phone", Tone.SUCCESS)
                refresh()
            }
            VaultCrypto.ImportResult.WRONG_PASSPHRASE -> say("Wrong passphrase for this key file", Tone.DANGER)
            VaultCrypto.ImportResult.NOT_A_KEY_FILE -> say("That isn't a finder+ recovery key", Tone.DANGER)
            VaultCrypto.ImportResult.WOULD_ORPHAN_EXISTING ->
                say("That key file belongs to a different vault — it can't open the files hidden here", Tone.DANGER)
        }
    }

    /**
     * Replace the vault key without a moment where files depend on an unsaved key: build the new key
     * and its recovery file, prove the file opens it, save it to Downloads and read it back — and only
     * then install the key and re-encrypt. Any failure before the install changes nothing.
     */
    fun rotateKey(passphrase: String) = viewModelScope.launch {
        _ui.update { it.copy(working = "Preparing a new key…", keyMessage = null) }
        try {
            val pending = withContext(Dispatchers.Default) {
                runCatching { VaultCrypto.beginRotation(passphrase.toCharArray()) }.getOrNull()
            } ?: return@launch say("Couldn't create a new key — the vault may be locked", Tone.DANGER)
            val covered = withContext(Dispatchers.Default) {
                VaultCrypto.recoveryCovers(pending.export.bytes, passphrase.toCharArray(), pending.newKeyId)
            }
            if (!covered) return@launch say("The new key failed its self-check — nothing changed", Tone.DANGER)

            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val name = "finderplus-recovery-$stamp.key"
            val uri = withContext(Dispatchers.IO) { writeToDownloads(pending.export.bytes, name) }
                ?: return@launch say("Couldn't save the new recovery key to Downloads — nothing changed", Tone.DANGER)

            withContext(Dispatchers.Default) { VaultCrypto.commitRotation(pending) }
            VaultCrypto.markRecoveryKeySaved(context, pending.export.keyIds)
            lastExport = pending.export
            _ui.update { it.copy(savedKeyUri = uri, canSaveCopy = true) }
            run(VaultWorker.Op.REWRITE)
            say("New key installed and saved to Downloads as $name. Send a copy off this phone — older key files no longer work.", Tone.SUCCESS)
            refresh()
        } finally {
            _ui.update { it.copy(working = null) }
        }
    }

    private fun say(message: String, tone: Tone) { _ui.update { it.copy(keyMessage = message to tone) } }

    private fun writeToDownloads(bytes: ByteArray, name: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                         else MediaStore.Files.getContentUri("external")
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        if (!readsBack(uri, bytes)) { context.contentResolver.delete(uri, null, null); return null }
        uri
    }.getOrNull()

    private fun readsBack(uri: Uri, bytes: ByteArray): Boolean =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.contentEquals(bytes) == true }
            .getOrDefault(false)

    private fun normalizeKeyName(fileName: String): String {
        val trimmed = fileName.trim().ifEmpty { "finderplus-recovery" }
        return if (trimmed.endsWith(".key", ignoreCase = true)) trimmed else "$trimmed.key"
    }

    private companion object {
        /** Plain, concrete nouns: easy to write down, hard to mishear, no lookalikes. */
        val WORDS = ("acorn actor adult agent album alley amber angle ankle apple apron arena armor arrow atlas attic " +
            "autumn avenue badge bakery balloon bamboo banana banjo barrel basket beach beacon beaver bench berry " +
            "bicycle bishop blanket blossom bonnet border bottle boulder bracelet branch breeze brick bridge brook " +
            "broom bucket buffalo bundle butter button cabin cactus camera camel candle canoe canyon captain carpet " +
            "carrot castle cattle cellar cereal chalk channel cherry chimney circus citrus clock cloud clover coconut " +
            "comet compass copper corner cotton cousin coyote crayon cricket crystal cupboard curtain cushion daisy " +
            "dancer desert diamond dinner doctor dolphin donkey dragon drawer dream drum eagle easel echo eclipse " +
            "elbow engine falcon farmer feather fence ferry fiddle finger fossil fountain fox galaxy garage garden " +
            "garlic gazelle gecko ginger giraffe glacier glove goblet gopher granite grape gravel guitar hammer " +
            "harbor harvest hazel helmet heron hiker hollow honey hornet horse iceberg igloo island ivory jacket " +
            "jaguar jelly jigsaw jungle kayak kettle kitten koala ladder lagoon lantern laptop lemon leopard lettuce " +
            "library lighthouse lily lizard lobster locket lotus magnet mango maple marble meadow melon mermaid " +
            "meteor mitten monkey mosaic moss motor muffin museum mushroom napkin needle nest noodle oasis ocean " +
            "olive onion orange orchid otter oyster paddle palace panda parrot peach peanut pebble pelican pencil " +
            "pepper piano pickle pigeon pillow pilot pine planet plum pocket pony potato puddle pumpkin puzzle " +
            "quartz quilt rabbit raccoon radio rainbow raven ribbon river robin rocket saddle salmon sandal satchel " +
            "saucer scarf seashell shadow sheep shovel silver sketch sleigh snail spider sponge squirrel stable " +
            "statue summit sunrise swan teapot temple thimble thunder tiger timber tomato tractor trumpet tulip " +
            "turtle umbrella valley velvet violin volcano wagon walnut whale whistle willow window wizard yogurt zebra")
            .split(' ')
    }
}

/** Rough strength for display: a long passphrase beats a clever short one. */
private fun strength(p: String): Pair<String, Tone>? = when {
    p.isEmpty() -> null
    p.length < 10 -> "Too short — use 10+ characters or tap Suggest" to Tone.DANGER
    p.length < 16 && p.count { it == '-' || it == ' ' } < 2 -> "Okay — longer is stronger" to Tone.WARNING
    else -> "Strong" to Tone.SUCCESS
}

@Composable
private fun PrivacyScreen(
    vm: PrivacyViewModel,
    onGrantAccess: () -> Unit,
    onLoadKeyFile: () -> Unit,
    onShareKey: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var confirm by remember { mutableStateOf<VaultWorker.Op?>(null) }
    var showKeyForm by remember { mutableStateOf(false) }

    FinderScreen(
        title = "🔒 Private folders",
        subtitle = "Folders you mark private are encrypted and removed from your gallery. They stay searchable here — only this app can open them.",
        onClose = onClose,
    ) {
        item { StatusCard(ui, onLoadKeyFile) }

        ui.running?.let { op ->
            item {
                InfoCard(tone = Tone.ACCENT, title = op.working) {
                    ProgressLine(
                        ui.progress?.let { (d, t) -> d.toFloat() / t },
                        ui.progress?.let { (d, t) -> "$d of $t files · keeps going in the background" } ?: "Starting…",
                    )
                }
            }
        }

        ui.report?.takeIf { ui.running == null }?.let { r -> item { ReportCard(r) } }

        if (!ui.allFiles) {
            item {
                InfoCard(
                    tone = Tone.WARNING,
                    title = "Needs all-files access",
                    body = "Hiding moves files out of shared storage, which Android only allows with this " +
                        "permission. Nothing is moved until you turn a folder on and tap Hide now.",
                    actions = { FinderButton("✅ Grant access", onGrantAccess, ButtonKind.TONAL) },
                )
            }
        }

        item { SectionHeader("🔑 Recovery key", "The only way back if this phone is lost, reset, or the app is removed.") }
        item {
            RecoveryCard(
                ui = ui, vm = vm, expanded = showKeyForm || !ui.recoverySaved,
                onExpand = { showKeyForm = true }, onShareKey = onShareKey, onLoadKeyFile = onLoadKeyFile,
            )
        }
        if (ui.keyFileLoaded) item { KeyFileCard(ui, vm) }

        item { SectionHeader("📁 Folders", "Turn on the folders to keep private. Your camera folder is only hidden if you pick it.") }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide new files automatically", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (ui.recoverySaved) "New media in a private folder is encrypted as soon as it's indexed."
                            else "Available once a recovery key is saved.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ui.auto && ui.recoverySaved, enabled = ui.recoverySaved, onCheckedChange = vm::setAuto)
                }
            }
        }
        if (ui.folders.isEmpty()) {
            item { Text("No media folders found yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(ui.folders, key = { it.relPath }) { f -> FolderRow(f) { vm.toggleFolder(f) } }

        item { SectionHeader("▶️ Actions") }
        item {
            val blocked = when {
                ui.keyStatus == VaultCrypto.KeyStatus.LOCKED -> "Unlock the vault with your recovery key first."
                !ui.recoverySaved -> "Save a recovery key first — it's what makes hiding safe."
                !ui.allFiles -> "Grant all-files access first."
                ui.marked == 0 -> "Turn on at least one folder above."
                else -> null
            }
            InfoCard(
                body = blocked ?: "Encrypts ${ui.marked} folder${if (ui.marked == 1) "" else "s"}. Each file is checked to decrypt perfectly before its original is removed.",
                actions = {
                    FinderButton("🙈 Hide now", { vm.run(VaultWorker.Op.HIDE) }, ButtonKind.PRIMARY, enabled = blocked == null && ui.running == null)
                    FinderButton("♻️ Restore all", { confirm = VaultWorker.Op.RESTORE }, ButtonKind.OUTLINE,
                        enabled = ui.running == null && ui.keyStatus != VaultCrypto.KeyStatus.LOCKED)
                },
            )
        }

        item { SectionHeader("🧰 Maintenance") }
        item {
            InfoCard(
                body = "Check opens every hidden file end to end. Upgrade moves files hidden by older versions to " +
                    "tamper-proof encryption. Replacing the key re-encrypts everything — older recovery files stop working.",
                actions = {
                    FinderButton("🔍 Check", { vm.run(VaultWorker.Op.CHECK) }, ButtonKind.TONAL, enabled = ui.running == null && ui.hidden > 0)
                    FinderButton("⬆️ Upgrade", { vm.run(VaultWorker.Op.UPGRADE) }, ButtonKind.TEXT, enabled = ui.running == null && ui.hidden > 0)
                    FinderButton("🔑 Replace key", { confirm = VaultWorker.Op.ROTATE }, ButtonKind.TEXT,
                        enabled = ui.running == null && ui.hidden > 0 && ui.keyStatus == VaultCrypto.KeyStatus.READY)
                },
            )
        }
        item {
            Text(
                "If you ever uninstall finder+, restore first. Android may offer to keep the app's data when " +
                    "uninstalling — choose Keep to keep hidden files and their key on this phone.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    when (confirm) {
        VaultWorker.Op.RESTORE -> ConfirmDialog(
            title = "Restore every hidden file?",
            body = "All ${ui.hidden} files are decrypted back into their folders and reappear in your gallery. " +
                "Nothing is overwritten — a name clash gets \"(restored)\".",
            confirm = "Restore all",
            onConfirm = { vm.run(VaultWorker.Op.RESTORE) },
            onDismiss = { confirm = null },
        )
        VaultWorker.Op.ROTATE -> RotateKeyDialog(
            onSuggest = vm::suggestPassphrase,
            onConfirm = { pass -> vm.rotateKey(pass) },
            onDismiss = { confirm = null },
        )
        else -> Unit
    }
}

/** The new key's passphrase is asked for up front, because its recovery file is made first. */
@Composable
private fun RotateKeyDialog(onSuggest: () -> String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace the vault key?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A new key and its recovery file are created and saved to Downloads first; then every hidden " +
                        "file is re-encrypted. Recovery files saved before stop working. Files stay readable throughout.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text("Passphrase for the new key") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                strength(pass)?.let { (t, tone) -> CheckLine(t, tone) }
                FinderButton("💡 Suggest", { pass = onSuggest() }, ButtonKind.TEXT)
            }
        },
        confirmButton = {
            FinderButton("🔑 Replace key", { onConfirm(pass); onDismiss() }, ButtonKind.DANGER, enabled = pass.length >= 10)
        },
        dismissButton = { FinderButton("✕ Cancel", onDismiss, ButtonKind.TEXT) },
    )
}

@Composable
private fun StatusCard(ui: PrivacyUi, onLoadKeyFile: () -> Unit) {
    if (ui.keyStatus == VaultCrypto.KeyStatus.LOCKED) {
        InfoCard(
            tone = Tone.DANGER,
            title = "Vault locked on this phone",
            body = "Your hidden files are intact, but this phone no longer holds the key that opens them — " +
                "this happens after a reset or a move to a new phone. Load your recovery key file to unlock.",
            actions = { FinderButton("🔑 Load recovery key", onLoadKeyFile, ButtonKind.PRIMARY) },
        )
        return
    }
    InfoCard(
        title = if (ui.hidden > 0) "${"%,d".format(ui.hidden)} file${if (ui.hidden == 1) "" else "s"} hidden & encrypted" else "Nothing hidden yet",
        body = "AES-256-GCM with tamper detection" + (ui.keyId?.let { " · key $it" } ?: ""),
    ) {
        CheckLine(
            if (ui.recoverySaved) "Recovery key saved for the current key" else "No recovery key for the current key",
            if (ui.recoverySaved) Tone.SUCCESS else Tone.WARNING,
        )
        CheckLine(if (ui.allFiles) "All-files access granted" else "All-files access needed to hide", if (ui.allFiles) Tone.SUCCESS else Tone.WARNING)
        CheckLine(
            "${ui.marked} folder${if (ui.marked == 1) "" else "s"} marked private · auto-hide ${if (ui.auto && ui.recoverySaved) "on" else "off"}",
            Tone.NEUTRAL,
        )
    }
}

@Composable
private fun ReportCard(r: VaultReport) {
    val ago = ((System.currentTimeMillis() - r.at) / 60_000).let { m ->
        when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }
    InfoCard(tone = if (r.ok) Tone.SUCCESS else Tone.WARNING, title = r.summary, body = "Last ${r.op.name.lowercase().replace('_', ' ')} · $ago") {
        r.details.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RecoveryCard(
    ui: PrivacyUi,
    vm: PrivacyViewModel,
    expanded: Boolean,
    onExpand: () -> Unit,
    onShareKey: (Uri) -> Unit,
    onLoadKeyFile: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("finderplus-recovery") }
    val clipboard = LocalClipboardManager.current

    InfoCard(
        tone = if (ui.recoverySaved) Tone.NEUTRAL else Tone.ACCENT,
        title = if (ui.recoverySaved) "Recovery key saved" else "Save a recovery key",
        body = if (ui.recoverySaved)
            "Covers every hidden file" + (ui.keyId?.let { " (key $it)" } ?: "") + ". Keep a copy off this phone — " +
                "email it to yourself, a cloud drive, or a password manager — and the passphrase somewhere separate."
        else "One key opens every hidden file — the ones already hidden and everything hidden later. " +
            "Pick a passphrase, save the file, then send a copy somewhere other than this phone.",
    ) {
        if (expanded && ui.keyStatus != VaultCrypto.KeyStatus.LOCKED) {
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, singleLine = true,
                label = { Text("Passphrase") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            strength(pass)?.let { (t, tone) -> CheckLine(t, tone) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinderButton("💡 Suggest", { pass = vm.suggestPassphrase(); visible = true }, ButtonKind.TEXT)
                FinderButton(if (visible) "Hide" else "Show", { visible = !visible }, ButtonKind.TEXT)
                if (pass.isNotEmpty()) FinderButton("📋 Copy", { clipboard.setText(AnnotatedString(pass)) }, ButtonKind.TEXT)
            }
            OutlinedTextField(
                value = fileName, onValueChange = { fileName = it }, singleLine = true,
                label = { Text("File name") }, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Write the passphrase down. Without it the key file is useless — and nobody, including us, can recover it.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FinderButton(
                if (ui.working != null) ui.working else "Save recovery key",
                { vm.saveRecovery(pass, fileName) },
                ButtonKind.PRIMARY,
                enabled = ui.working == null && pass.length >= 10,
            )
        }
        ui.keyMessage?.let { (msg, tone) -> CheckLine(msg, tone) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ui.savedKeyUri?.let { uri -> FinderButton("📤 Send a copy…", { onShareKey(uri) }, ButtonKind.TONAL) }
            if (ui.canSaveCopy) FinderButton("💾 Save elsewhere…", { vm.saveAnotherCopy(fileName) }, ButtonKind.TEXT)
            if (ui.recoverySaved && !expanded) FinderButton("🆕 New key file", onExpand, ButtonKind.TEXT)
            FinderButton(if (ui.recoverySaved) "Test a key file" else "I have a key file", onLoadKeyFile, ButtonKind.TEXT)
        }
    }
}

@Composable
private fun KeyFileCard(ui: PrivacyUi, vm: PrivacyViewModel) {
    var pass by remember { mutableStateOf("") }
    InfoCard(
        tone = Tone.ACCENT,
        title = "Key file loaded",
        body = "Enter the passphrase it was saved with. Test only checks it; Unlock adds its key to this phone.",
    ) {
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinderButton("🧪 Test", { vm.testKey(pass) }, ButtonKind.TONAL, enabled = pass.isNotEmpty() && ui.working == null)
            FinderButton("🔓 Unlock with it", { vm.useKey(pass) }, ButtonKind.PRIMARY, enabled = pass.isNotEmpty() && ui.working == null)
        }
        ui.working?.let { ProgressLine(null, it) }
    }
}

@Composable
private fun FolderRow(f: VaultEngine.Folder, onToggle: () -> Unit) {
    val private = f.decision == VaultPolicy.Decision.HIDE
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(f.relPath, style = MaterialTheme.typography.bodyMedium, fontWeight = if (private) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                Text(
                    buildString {
                        append("${f.count + f.hiddenCount} items")
                        if (f.hiddenCount > 0) append(" · ${f.hiddenCount} hidden")
                        if (private && f.count > 0) append(" · ${f.count} waiting")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = private, onCheckedChange = { onToggle() })
        }
    }
}
