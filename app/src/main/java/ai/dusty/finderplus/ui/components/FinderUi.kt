package ai.dusty.finderplus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The few building blocks every finder+ screen is made of, so Models, Private folders and the pop-up
 * read as one app: the same card shapes, the same four tones, the same button hierarchy.
 */

/** What a card or pill is telling you, mapped to color roles of the current scheme. */
enum class Tone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

private val SuccessGreen = Color(0xFF7BD88F)
private val WarningAmber = Color(0xFFF5C26B)

@Composable
private fun Tone.container(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
    Tone.ACCENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    Tone.SUCCESS -> SuccessGreen.copy(alpha = 0.12f)
    Tone.WARNING -> WarningAmber.copy(alpha = 0.14f)
    Tone.DANGER -> MaterialTheme.colorScheme.errorContainer
}

@Composable
fun Tone.content(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    Tone.ACCENT -> MaterialTheme.colorScheme.primary
    Tone.SUCCESS -> SuccessGreen
    Tone.WARNING -> WarningAmber
    Tone.DANGER -> MaterialTheme.colorScheme.onErrorContainer
}

/** Full-screen page: title row with a Done action, then a scrolling list of sections. */
@Composable
fun FinderScreen(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("✓ Done") }
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    )
                }
            }
            content()
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** A titled group of rows. */
@Composable
fun SectionHeader(title: String, blurb: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (blurb != null) {
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A rounded card in one of the [Tone]s, with an optional title and an action row underneath. */
@Composable
fun InfoCard(
    tone: Tone = Tone.NEUTRAL,
    title: String? = null,
    body: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(color = tone.container(), shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else tone.content(),
                )
            }
            if (body != null) {
                Text(
                    body, style = MaterialTheme.typography.bodySmall,
                    color = if (tone == Tone.DANGER) tone.content() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content?.invoke(this)
            if (actions != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/** Small rounded label: "Installed", "Essential", "Queued". */
@Composable
fun StatusPill(text: String, tone: Tone) {
    Surface(color = tone.container(), shape = RoundedCornerShape(50)) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
            color = tone.content(), maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

enum class ButtonKind { PRIMARY, TONAL, OUTLINE, TEXT, DANGER }

/** One button API with a clear hierarchy: one PRIMARY per card at most. */
@Composable
fun FinderButton(
    text: String,
    onClick: () -> Unit,
    kind: ButtonKind = ButtonKind.TONAL,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val label: @Composable RowScope.() -> Unit = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    when (kind) {
        ButtonKind.PRIMARY -> Button(onClick, modifier, enabled, content = label)
        ButtonKind.TONAL -> FilledTonalButton(onClick, modifier, enabled, content = label)
        ButtonKind.OUTLINE -> OutlinedButton(onClick, modifier, enabled, content = label)
        ButtonKind.TEXT -> TextButton(onClick, modifier, enabled, content = label)
        ButtonKind.DANGER -> Button(
            onClick, modifier, enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = label,
        )
    }
}

/** A captioned progress bar; indeterminate when [fraction] is null. */
@Composable
fun ProgressLine(fraction: Float?, caption: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp))
        }
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A dot + text line used for checklists ("✓ Recovery key saved"). */
@Composable
fun CheckLine(text: String, tone: Tone) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(tone.content(), CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).widthIn(max = 520.dp))
    }
}

/** Confirmation for anything destructive or slow. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            FinderButton(confirm, { onConfirm(); onDismiss() }, if (danger) ButtonKind.DANGER else ButtonKind.PRIMARY)
        },
        dismissButton = { FinderButton("✕ Cancel", onDismiss, ButtonKind.TEXT) },
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
