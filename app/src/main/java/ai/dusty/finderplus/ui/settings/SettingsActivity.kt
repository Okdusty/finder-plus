package ai.dusty.finderplus.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.dusty.finderplus.model.ModelSpec
import ai.dusty.finderplus.ui.popup.finderColorScheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Model download manager. The FOSS build ships no weights, so this screen is what turns content
 * search on: download the models for the passes you want, and the next index run picks them up.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = finderColorScheme()) {
                ModelScreen(vm, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ModelScreen(vm: SettingsViewModel, onClose: () -> Unit) {
    val installed by vm.installed.collectAsState()
    val busy by vm.busy.collectAsState()
    val progress by vm.progress.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Models", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Done",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
                    )
                }
                Text(
                    "Models are open-licensed downloads — nothing ships in the APK. Download the ones " +
                        "you want and the next index run picks them up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Text(
                    "Installed: ${formatSize(vm.footprintBytes())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            items(vm.catalog, key = { it.id }) { spec ->
                ModelRow(
                    spec = spec,
                    installed = spec.id in installed,
                    downloading = spec.id in busy,
                    progress = progress[spec.id],
                    onDownload = { vm.download(spec) },
                    onDelete = { vm.delete(spec) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    installed: Boolean,
    downloading: Boolean,
    progress: ai.dusty.finderplus.model.DownloadProgress?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            when {
                downloading -> {
                    Text(
                        progress?.let { "${(it.fraction * 100).toInt()}%" } ?: "Starting…",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                    )
                }
                installed -> TextButton(onClick = onDelete) { Text("Delete", fontSize = 12.sp) }
                else -> TextButton(onClick = onDownload) { Text("Download", fontSize = 12.sp) }
            }
        }
        if (spec.note.isNotEmpty()) {
            Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            formatSize(spec.sizeBytes) + spec.requiresId?.let { " (+ companion)" }.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (downloading && progress != null) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
