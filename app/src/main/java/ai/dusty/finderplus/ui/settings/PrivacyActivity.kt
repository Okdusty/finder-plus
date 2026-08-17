package ai.rightone.finderplus.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.rightone.finderplus.index.VaultEngine
import ai.rightone.finderplus.index.VaultPolicy
import ai.rightone.finderplus.index.VaultWorker
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private folders: which parts of the gallery are encrypted and hidden, and whether new arrivals in
 * them are hidden automatically.
 *
 * The screen exists because the feature is otherwise unusable by anyone but its author — the vault
 * was driven by adb broadcasts. Everything a person needs to trust it lives here: what will be
 * hidden and how much of it, the permission it requires and why, a way back out, and a recovery key,
 * because a vault whose only key lives in one phone's Keystore is one factory reset from a disaster.
 */
@AndroidEntryPoint
class PrivacyActivity : ComponentActivity() {

    private val vm: PrivacyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ai.rightone.finderplus.ui.popup.finderColorScheme()) {
                PrivacyScreen(
                    vm = vm,
                    onGrantAccess = { requestAllFiles() },
                    onExportKey = { pass -> exportRecovery(pass) },
                    onImportKey = { pickRecovery() },
                    onClose = { finish() },
                )
            }
        }
        vm.refresh()
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // returning from the permission screen must update the gate immediately
    }

    /**
     * All-files access, requested the only way Android allows: a system screen the user drives.
     * Deliberately not asked for on first launch — it is needed to *move* files out of shared
     * storage, so it is asked for at the moment someone actually turns hiding on.
     */
    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /**
     * Save the recovery key with one tap.
     *
     * Straight into Downloads via MediaStore — no file picker, no folder decision, and it lands
     * somewhere every backup tool and every "transfer to my computer" flow already looks. A key
     * people abandon halfway through saving protects nothing, so the easy path is the default and
     * the picker is only the fallback. Immediately offers to share it too: a copy that never leaves
     * the phone is a copy that dies with the phone.
     */
    private fun exportRecovery(passphrase: String) {
        val blob = runCatching {
            ai.rightone.finderplus.media.VaultCrypto.init(this)
            ai.rightone.finderplus.media.VaultCrypto.exportRecovery(passphrase.toCharArray())
        }.getOrNull()
        if (blob == null) { toast("Couldn't create a recovery key"); return }

        val saved = runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "finderplus-recovery.key")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Files.getContentUri("external"), values,
            ) ?: return@runCatching null
            contentResolver.openOutputStream(uri)?.use { it.write(blob) }
            uri
        }.getOrNull()

        if (saved == null) {
            // Fallback: let the user choose a destination themselves.
            pendingExport = blob
            saveLauncher.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, "finderplus-recovery.key")
            )
            return
        }

        toast("Saved to Downloads as finderplus-recovery.key")
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, saved)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Keep a copy off this phone",
                )
            )
        }
    }

    private fun pickRecovery() {
        openLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        )
    }

    private var pendingExport: ByteArray? = null

    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val blob = pendingExport
        pendingExport = null
        if (result.resultCode != Activity.RESULT_OK || uri == null || blob == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(blob) } != null
        }.getOrDefault(false)
        toast(if (ok) "Recovery key saved — keep it somewhere safe" else "Couldn't save the key")
    }

    private val openLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        vm.pendingImport = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        toast(if (vm.pendingImport != null) "Key file loaded — enter its passphrase" else "Couldn't read that file")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val engine: VaultEngine,
    val policy: VaultPolicy,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _folders = MutableStateFlow<List<VaultEngine.Folder>>(emptyList())
    val folders: StateFlow<List<VaultEngine.Folder>> = _folders

    private val _auto = MutableStateFlow(policy.auto)
    val auto: StateFlow<Boolean> = _auto

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    var pendingImport: ByteArray? = null

    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun refresh() = viewModelScope.launch {
        _auto.value = policy.auto
        _folders.value = withContext(Dispatchers.IO) {
            runCatching { engine.folders(context) }.getOrDefault(emptyList())
        }
    }

    fun setAuto(on: Boolean) { policy.auto = on; _auto.value = on }

    fun toggleFolder(folder: VaultEngine.Folder) {
        val next = if (folder.decision == VaultPolicy.Decision.HIDE) VaultPolicy.Decision.KEEP
                   else VaultPolicy.Decision.HIDE
        policy.set(folder.relPath, next)
        refresh()
    }

    /** Apply the current rules now: hide what should be hidden. */
    fun applyNow() {
        _busy.value = true
        VaultWorker.enqueue(context)
        viewModelScope.launch { kotlinx.coroutines.delay(1500); _busy.value = false; refresh() }
    }

    /** Bring everything back to its original location, decrypted. */
    fun restoreAll() {
        _busy.value = true
        VaultWorker.enqueue(context, restore = true)
        viewModelScope.launch { kotlinx.coroutines.delay(1500); _busy.value = false; refresh() }
    }

    /**
     * A passphrase nobody has to invent. Six words from a short, unambiguous list: easy to write on
     * paper, easy to type, and ~77 bits of entropy — far stronger than what people choose when asked
     * to make one up, which is the actual threat to a recovery key.
     */
    fun suggestPassphrase(): String {
        val rnd = java.security.SecureRandom()
        return (1..6).map { WORDS[rnd.nextInt(WORDS.size)] }.joinToString("-")
    }

    /** Any existing blob, used to prove an imported key actually opens this vault. */
    private fun sampleBlob(): java.io.File? = runCatching {
        engine.vaultRoot(context).walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(ai.rightone.finderplus.media.VaultCrypto.EXT) }
    }.getOrNull()

    fun importRecovery(passphrase: String): ai.rightone.finderplus.media.VaultCrypto.ImportResult {
        val blob = pendingImport
            ?: return ai.rightone.finderplus.media.VaultCrypto.ImportResult.NOT_A_KEY_FILE
        ai.rightone.finderplus.media.VaultCrypto.init(context)
        val result = ai.rightone.finderplus.media.VaultCrypto.importRecovery(
            blob, passphrase.toCharArray(), sampleBlob(),
        )
        if (result == ai.rightone.finderplus.media.VaultCrypto.ImportResult.OK) pendingImport = null
        return result
    }

    /** Replace the vault key itself and re-encrypt every hidden file under it. */
    fun rotateKey() {
        _busy.value = true
        VaultWorker.enqueue(context, rotate = true)
        viewModelScope.launch { kotlinx.coroutines.delay(2000); _busy.value = false; refresh() }
    }

    private companion object {
        /** Short, unambiguous, easy to transcribe by hand — no lookalike letters or homophones. */
        val WORDS = listOf(
            "anchor", "basket", "candle", "dolphin", "ember", "forest", "garden", "harbor",
            "island", "jacket", "kettle", "lantern", "meadow", "nectar", "orbit", "pepper",
            "quartz", "ribbon", "saddle", "timber", "umbrella", "velvet", "walnut", "yellow",
            "almond", "bridge", "copper", "dragon", "engine", "falcon", "granite", "hammer",
            "indigo", "jungle", "kingdom", "ladder", "marble", "needle", "olive", "pigeon",
        )
    }
}

@Composable
private fun PrivacyScreen(
    vm: PrivacyViewModel,
    onGrantAccess: () -> Unit,
    onExportKey: (String) -> Unit,
    onImportKey: () -> Unit,
    onClose: () -> Unit,
) {
    val folders by vm.folders.collectAsState()
    val auto by vm.auto.collectAsState()
    val busy by vm.busy.collectAsState()
    val hidden = folders.sumOf { it.hiddenCount }
    val marked = folders.count { it.decision == VaultPolicy.Decision.HIDE }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Private folders", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Done",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
                    )
                }
                Text(
                    "Folders you mark private are encrypted and removed from your gallery. " +
                        "They stay fully searchable here — only this app can read them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (!vm.hasAllFiles()) {
                item {
                    Notice(
                        title = "Needs all-files access",
                        body = "Hiding moves files out of shared storage, which Android only allows with " +
                            "this permission. Nothing is moved until you turn a folder on.",
                        action = "Grant access",
                        onAction = onGrantAccess,
                    )
                }
            }

            item { SectionRule("status") }
            item {
                Mono("$hidden hidden and encrypted · $marked folder${if (marked == 1) "" else "s"} marked private")
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide new files automatically", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "New media in a private folder is encrypted as soon as it is indexed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = vm::setAuto)
                }
            }

            item { SectionRule("folders") }
            items(folders, key = { it.relPath }) { f ->
                FolderRow(f) { vm.toggleFolder(f) }
            }

            item { SectionRule("actions") }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Apply now", primary = true, enabled = !busy && marked > 0) { vm.applyNow() }
                    Action("Restore all", primary = false, enabled = !busy && hidden > 0) { vm.restoreAll() }
                }
                Text(
                    "Restore decrypts everything back to its original folder. Do this before " +
                        "uninstalling — the vault lives in this app's private storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            item { SectionRule("recovery key") }
            item { RecoverySection(vm, hidden, onExportKey, onImportKey) }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

/**
 * The escape hatch from a single point of failure. Without a saved key, clearing app data or losing
 * the phone makes every hidden file permanently unreadable — the encryption working exactly as
 * designed, against its owner.
 */
@Composable
private fun RecoverySection(
    vm: PrivacyViewModel,
    hiddenCount: Int,
    onExport: (String) -> Unit,
    onImport: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Column {
        Text(
            // The correction people need to hear: one key covers the whole vault. There is no
            // per-session key, so a passphrase set today already protects every file hidden before it.
            "One key protects every hidden file — the $hiddenCount already encrypted and everything " +
                "hidden later. Save it somewhere other than this phone; without it, clearing this " +
                "app's data makes hidden files unreadable forever.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Field(pass, { pass = it }, "passphrase")

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One tap to a strong passphrase: inventing one is where recovery keys actually fail.
            Action("Suggest", primary = false, enabled = true) {
                pass = vm.suggestPassphrase()
                msg = "Write this down before saving the key"
            }
            if (pass.isNotEmpty()) {
                Action("Copy", primary = false, enabled = true) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(pass))
                    msg = "Passphrase copied"
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Save key", primary = true, enabled = pass.length >= 6) { onExport(pass) }
            Action("Load key file", primary = false, enabled = true) { onImport() }
            if (vm.pendingImport != null) {
                Action("Use key", primary = true, enabled = pass.isNotEmpty()) {
                    msg = when (vm.importRecovery(pass)) {
                        ai.rightone.finderplus.media.VaultCrypto.ImportResult.OK -> "Key restored"
                        ai.rightone.finderplus.media.VaultCrypto.ImportResult.WRONG_PASSPHRASE -> "Wrong passphrase"
                        ai.rightone.finderplus.media.VaultCrypto.ImportResult.NOT_A_KEY_FILE -> "That is not a recovery key"
                        ai.rightone.finderplus.media.VaultCrypto.ImportResult.WOULD_ORPHAN_EXISTING ->
                            "Refused: that key cannot open the files already hidden here. Restore them first."
                    }
                }
            }
        }
        msg?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }

        if (hiddenCount > 0) {
            Text(
                "Replace the key if an old recovery file may have been seen by someone else. " +
                    "Every hidden file is re-encrypted, and previously saved keys stop working.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(Modifier.padding(top = 6.dp)) {
                Action("Replace key & re-encrypt", primary = false, enabled = true) { vm.rotateKey() }
            }
        }
    }
}

@Composable
private fun FolderRow(f: VaultEngine.Folder, onToggle: () -> Unit) {
    val private = f.decision == VaultPolicy.Decision.HIDE
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.relPath, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                buildString {
                    append("${f.count + f.hiddenCount} items")
                    if (f.hiddenCount > 0) append(" · ${f.hiddenCount} hidden")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = private, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAction),
            )
        }
    }
}

@Composable
private fun SectionRule(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Action(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (primary && enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).let {
                if (enabled) it else it
            },
        )
    }
}
