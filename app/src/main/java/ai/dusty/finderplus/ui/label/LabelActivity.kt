package ai.dusty.finderplus.ui.label

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import ai.dusty.finderplus.index.MediaLabeler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Label one specific item. finder+ registers for VIEW and SEND on media, so it appears in any gallery's
 * "Open with" / "Share" menu — the user picks a file and tags it directly.
 *
 * Labels land as [ai.dusty.finderplus.model.TagSource.USER] and the item's search artifacts are
 * rebuilt on save, so the label is searchable immediately rather than after the next index run.
 */
@AndroidEntryPoint
class LabelActivity : ComponentActivity() {

    @Inject lateinit var labeler: MediaLabeler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = (intent?.data ?: intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.toString()

        setContent {
            MaterialTheme(colorScheme = ai.dusty.finderplus.ui.popup.finderColorScheme()) {
                var itemId by remember { mutableStateOf<Long?>(null) }
                var name by remember { mutableStateOf<String?>(null) }
                var text by remember { mutableStateOf("") }
                var status by remember { mutableStateOf("Resolving…") }

                LaunchedEffect(uri) {
                    if (uri == null) { status = "No media supplied"; return@LaunchedEffect }
                    val id = labeler.resolveItem(uri)
                    if (id == null) {
                        status = "Not indexed yet — run an update first"
                    } else {
                        itemId = id
                        name = labeler.displayName(id)
                        text = labeler.userLabels(id).joinToString(", ")
                        status = "Your labels are searchable immediately"
                    }
                }

                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xF2201F24),
                        tonalElevation = 8.dp,
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Label this media", color = Color(0xFFEDEDF2), fontSize = 15.sp)
                            name?.let { Text(it, color = Color(0xFF9A99A3), fontSize = 11.sp, maxLines = 1) }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                placeholder = { Text("labels, comma separated", color = Color(0xFF9A99A3)) },
                                enabled = itemId != null,
                                shape = RoundedCornerShape(12.dp),
                            )
                            Text(status, color = Color(0xFF9A99A3), fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                                TextButton(onClick = { finish() }) { Text("Cancel") }
                                TextButton(
                                    enabled = itemId != null,
                                    onClick = {
                                        val id = itemId ?: return@TextButton
                                        lifecycleScope.launch {
                                            labeler.setLabels(id, text.split(',', '\n'))
                                            Toast.makeText(this@LabelActivity, "Labels saved", Toast.LENGTH_SHORT).show()
                                            finish()
                                        }
                                    },
                                ) { Text("Save") }
                            }
                        }
                    }
                }
            }
        }
    }
}
