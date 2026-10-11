package ai.dusty.finderplus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays a video or recording in place, from [startMs] when known and on to the end, with the system's
 * playback controls. The picture keeps its own proportions within [modifier]'s bounds. Hidden media is
 * staged as a readable copy first, as for opening it elsewhere.
 */
@Composable
fun InlinePlayer(
    uri: String,
    displayName: String?,
    startMs: Long?,
    modifier: Modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp),
) {
    val context = LocalContext.current
    val source by produceState<android.net.Uri?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { VaultAccess.shareableUri(context, uri, displayName) }.getOrNull()
        }
    }
    // Width over height once the player knows it; 0 for sound with no picture.
    var ratio by remember(uri) { mutableStateOf<Float?>(null) }
    val src = source ?: return
    Box(modifier, contentAlignment = Alignment.Center) {
        val shape = when (val r = ratio) {
            null -> Modifier.fillMaxSize()
            0f -> Modifier.fillMaxWidth().height(96.dp)
            else -> Modifier.aspectRatio(r, matchHeightConstraintsFirst = r < 1f)
        }
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    val controller = android.widget.MediaController(ctx).also { it.setAnchorView(this) }
                    setMediaController(controller)
                    setOnPreparedListener { mp ->
                        ratio = if (mp.videoWidth > 0 && mp.videoHeight > 0) mp.videoWidth.toFloat() / mp.videoHeight else 0f
                        startMs?.let { ms -> seekTo(ms.toInt()) }
                        start()
                        // Sound alone has nothing to tap to bring the controls back, so they stay.
                        if (ratio == 0f) controller.show(0)
                    }
                    setVideoURI(src)
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = shape,
        )
    }
}

/**
 * [InlinePlayer] over the whole window: every touch goes to the player or its controls, the keyboard
 * is put away, and back or the close button returns to what was underneath.
 */
@Composable
fun FullScreenPlayer(uri: String, displayName: String?, startMs: Long?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { keyboard?.hide(); focus.clearFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Swallows taps around the picture, so nothing under the player reacts to them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        InlinePlayer(uri, displayName, startMs, Modifier.fillMaxSize())
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
            Text("✕ Close", color = Color.White)
        }
    }
}
