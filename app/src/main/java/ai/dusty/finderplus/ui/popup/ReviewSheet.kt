package ai.dusty.finderplus.ui.popup

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import ai.dusty.finderplus.index.Doubt
import ai.dusty.finderplus.index.GroupKind
import ai.dusty.finderplus.index.GroupMember
import ai.dusty.finderplus.index.ReviewGroup

/**
 * The supervisor surface: one proposed grouping at a time, every member visible, each independently
 * confirmable.
 *
 * ### Why a grid rather than a text field
 *
 * Typing a label teaches from one example. Judging a grid teaches from all of them, at roughly the same
 * cost in attention — the eye rejects a wrong thumbnail in a grid far faster than the hand can type a
 * word. That asymmetry is the whole reason this screen exists: the classifier's uncertain band is large
 * (auto-apply now demands 0.35 confidence, so most of what it notices lands here), and it only becomes
 * an asset if answering is close to free.
 *
 * ### Everything starts accepted
 *
 * The default is "all of these are correct", and the user *removes* the wrong ones. This is deliberate:
 * the group was proposed because the evidence is shared, so most members are usually right, and the
 * cheaper gesture should serve the common case. Starting from all-rejected would make a correct group of
 * twenty cost twenty taps instead of none.
 *
 * A declined member is not merely dropped — it becomes a negative exemplar, which moves the prototype
 * away from it. Saying "not this one" therefore teaches more per tap than confirming a member does.
 */
@Composable
fun ReviewSheet(
    groups: List<ReviewGroup>,
    /** A group brought back by undo, with what was selected in it when it was answered. */
    restored: Pair<ReviewGroup, SearchViewModel.Selection>?,
    canUndo: Boolean,
    assistMode: ai.dusty.finderplus.index.AssistPrefs.Mode,
    onAssistMode: (ai.dusty.finderplus.index.AssistPrefs.Mode) -> Unit,
    assistProvider: ai.dusty.finderplus.index.CloudProvider,
    onAssistProvider: (ai.dusty.finderplus.index.CloudProvider) -> Unit,
    cloudModel: String,
    onCloudModel: (String) -> Unit,
    keySaved: Boolean,
    onApiKey: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrl: (String) -> Unit,
    assistStatus: SearchViewModel.AssistStatus?,
    onRefreshStatus: () -> Unit,
    onRevertLabel: (String) -> Unit,
    onStartAuto: () -> Unit,
    askUnsure: Boolean,
    onAskUnsure: (Boolean) -> Unit,
    onAnswer: (accepted: List<Long>, declined: List<Long>) -> Unit,
    onRank: (ranked: List<String>) -> Unit,
    onNamePerson: (faceIds: List<Long>, name: String) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    var peeking by remember { mutableStateOf<GroupMember?>(null) }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
            tonalElevation = 10.dp,
        ) {
            var showAssist by remember { mutableStateOf(false) }
            if (showAssist) {
                AssistSettings(
                    mode = assistMode,
                    onMode = onAssistMode,
                    provider = assistProvider,
                    onProvider = onAssistProvider,
                    model = cloudModel,
                    onModel = onCloudModel,
                    keySaved = keySaved,
                    onApiKey = onApiKey,
                    ollamaUrl = ollamaUrl,
                    onOllamaUrl = onOllamaUrl,
                    status = assistStatus,
                    onRefresh = onRefreshStatus,
                    onRevertLabel = onRevertLabel,
                    onStart = onStartAuto,
                    askUnsure = askUnsure,
                    onAskUnsure = onAskUnsure,
                    onBack = { showAssist = false },
                )
                return@Surface
            }

            if (groups.isEmpty()) {
                Column(Modifier.padding(20.dp)) {
                    Text("🎉 Nothing to review", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Labels the classifier is confident about are applied automatically. " +
                            "Anything it is unsure about will appear here once indexing reaches it.",
                        color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        // Still reachable with an empty queue: the AI-decision log and assist config
                        // are exactly what the user wants to inspect *after* the judge has worked.
                        Text(
                            "🤖 Assist", color = Dim, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showAssist = true },
                        )
                        Text(
                            "✕ Close", color = Accent, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onClose),
                        )
                    }
                }
                return@Surface
            }

            val group = groups.first()
            // After an undo, the group comes back as it was answered rather than cleared.
            val restore = restored?.takeIf { it.first == group }?.second
            if (group.kind == GroupKind.RANK) {
                RankQuestion(group, restore?.names.orEmpty(), groups.size, canUndo, onRank, onUndo, onClose,
                    onAssist = { showAssist = true }, onPeek = { peeking = it })
                return@Surface
            }
            // Keyed on the group so answering one and advancing to the next resets the selection —
            // otherwise the previous group's rejections would silently carry over.
            val rejected = remember(group.label, group.members.size, restore) {
                mutableStateMapOf<Long, Boolean>().apply { restore?.marked?.forEach { put(it, true) } }
            }
            var name by remember(group.label) { mutableStateOf("") }

            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    // The escape hatch from manual supervision: hand the whole queue to a stronger
                    // model. Person questions always stay human — a judge cannot know names.
                    Text(
                        "🤖 Assist",
                        color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { showAssist = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    if (canUndo) {
                        Text(
                            "↩️ Undo",
                            color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onUndo)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                Text(
                    // Phrased by kind AND size, so consecutive questions read as different questions —
                    // the answer gesture differs with the phrasing, and varied wording keeps the user
                    // reading instead of rhythm-tapping.
                    when {
                        group.kind == GroupKind.PERSON -> "Who is this? (${group.members.size} photos)"
                        group.members.size == 1 -> "Is this “${group.label}”?"
                        group.members.size <= 6 -> "Which of these are “${group.label}”?"
                        else -> "All of these look like “${group.label}” — spot any that aren't?"
                    },
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    buildString {
                        val lo = group.members.minOf { it.score }
                        val hi = group.members.maxOf { it.score }
                        when (group.kind) {
                            GroupKind.PERSON -> append("grouped by face similarity %.0f%%".format(group.cohesion * 100))
                            GroupKind.OBJECT -> append("detector %s · %s".format(percentRange(lo, hi), doubtSummary(group)))
                            else -> append("model is unsure (%s)".format(percentRange(lo, hi)))
                        }
                        if (group.kind != GroupKind.PERSON) append(" · tap or drag across the ones that aren't it")
                        append(" · hold a photo to see it larger")
                        append(" · ${groups.size} left")
                    },
                    color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )

                SelectableGrid(
                    members = group.members,
                    // A person group is named as a whole, so its photos are only for looking at.
                    selectable = group.kind != GroupKind.PERSON,
                    isMarked = { rejected[it] == true },
                    setMarked = { id, marked -> rejected[id] = marked },
                    onPeek = { peeking = it },
                )

                if (group.kind == GroupKind.PERSON) {
                    // The name is the only thing about a person the pipeline will not guess. Face
                    // recognition establishes "same person"; who they are comes from here and nowhere else.
                    Surface(
                        color = Color(0x14FFFFFF), shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                            decorationBox = { inner ->
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    if (name.isEmpty()) Text("Their name", color = Dim, fontSize = 14.sp)
                                    inner()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                val kept = group.members.map { it.itemId }.filter { rejected[it] != true }
                val dropped = group.members.map { it.itemId }.filter { rejected[it] == true }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Action(
                        text = when {
                            group.kind == GroupKind.PERSON -> if (name.isBlank()) "✍️ Type a name" else "💾 Save “${name.trim()}”"
                            dropped.isEmpty() -> "✅ Yes — all ${group.members.size}"
                            kept.isEmpty() -> "❌ None of these"
                            else -> "✅ Yes — ${kept.size} of ${group.members.size}"
                        },
                        accent = true,
                        enabled = group.kind != GroupKind.PERSON || name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (group.kind == GroupKind.PERSON) onNamePerson(group.faceIds, name)
                        else onAnswer(kept, dropped)
                    }
                    if (group.kind == GroupKind.PERSON) {
                        Action(text = "❌ None", accent = false, enabled = true) { onNamePerson(emptyList(), "") }
                    } else {
                        // Only changes the selection; the answer is still given with the button beside it.
                        val allMarked = kept.isEmpty()
                        Action(text = if (allMarked) "✕ Clear" else "☑️ Select all", accent = false, enabled = true) {
                            group.members.forEach { rejected[it.itemId] = !allMarked }
                        }
                    }
                    Text(
                        "✕ Close",
                        color = Dim, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
        peeking?.let { Peek(it, onClose = { peeking = null }) }
    }
}

private fun percentRange(lo: Float, hi: Float): String =
    if ((hi - lo) < 0.005f) "%.0f%%".format(hi * 100) else "%.0f–%.0f%%".format(lo * 100, hi * 100)

/** Why an object group was asked, in the order of [Doubt], each with how many photos it applies to. */
private fun doubtSummary(group: ReviewGroup): String =
    Doubt.entries.filter { (group.reasons[it] ?: 0) > 0 }.joinToString(" · ") { d ->
        val text = when (d) {
            Doubt.WHOLE_FRAME -> "covers the whole photo"
            Doubt.LOW_CONFIDENCE -> "low confidence"
            Doubt.DISAGREE -> group.alternatives.firstOrNull()?.let { "CLIP reads “$it”" } ?: "CLIP disagrees"
            Doubt.OUTLIER -> "unlike your other “${group.label}” photos"
            Doubt.ANSWERS -> "unlike the ones you said yes to"
        }
        if (group.members.size > 1) "$text (${group.reasons[d]})" else text
    }

private val TILE = 78.dp
private val TILE_GAP = 6.dp

/**
 * Photos in a fixed grid that answers three gestures: a tap toggles one photo; a drag sets every photo
 * the finger crosses to the state the first one was given; holding still shows a photo larger until
 * released, without changing it. A held video stays open and plays until tapped away. Not lazy: a group
 * holds a few dozen photos at most, and a drag needs every tile's position.
 */
@Composable
private fun SelectableGrid(
    members: List<GroupMember>,
    selectable: Boolean,
    isMarked: (Long) -> Boolean,
    setMarked: (Long, Boolean) -> Unit,
    onPeek: (GroupMember?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val columns = maxOf(3, ((maxWidth + TILE_GAP) / (TILE + TILE_GAP)).toInt())
        val tile = (maxWidth - TILE_GAP * (columns - 1)) / columns
        val step = with(LocalDensity.current) { (tile + TILE_GAP).toPx() }
        // The gesture handler outlives recompositions; it must read the current question's state, not
        // the state of the question that was showing when it started.
        val current by rememberUpdatedState(members)
        val marked by rememberUpdatedState(isMarked)
        val mark by rememberUpdatedState(setMarked)
        val peek by rememberUpdatedState(onPeek)
        fun indexAt(p: Offset): Int? {
            val c = (p.x / step).toInt()
            val r = (p.y / step).toInt()
            if (p.x < 0 || p.y < 0 || c >= columns) return null
            return (r * columns + c).takeIf { it < current.size }
        }
        Column(
            Modifier.pointerInput(columns, step, selectable) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = indexAt(down.position) ?: return@awaitEachGesture
                    // Consumed throughout, or the sheet's backdrop would read a tap on a photo as "close".
                    down.consume()
                    // Tap, drag or hold: whichever the finger does first within the long-press timeout.
                    val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            c.consume()
                            if (!c.pressed) return@withTimeoutOrNull false
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    val first = current[start].itemId
                    when (moved) {
                        null -> {
                            val held = current[start]
                            peek(held)
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            if (!held.video) peek(null)
                        }
                        false -> if (selectable) mark(first, !marked(first))
                        true -> if (selectable) {
                            val target = !marked(first)
                            mark(first, target)
                            var last = down.position
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                // Sample the path between events so a fast swipe does not skip tiles.
                                val dist = (c.position - last).getDistance()
                                val samples = maxOf(1, (dist / (step / 3)).toInt())
                                for (k in 1..samples) {
                                    indexAt(last + (c.position - last) * (k.toFloat() / samples))
                                        ?.let { mark(current[it].itemId, target) }
                                }
                                last = c.position
                            }
                        }
                    }
                }
            },
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            for (row in members.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    for (m in row) MemberTile(m, excluded = isMarked(m.itemId), size = tile)
                }
            }
        }
    }
}

@Composable
private fun MemberTile(member: GroupMember, excluded: Boolean, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, member.itemId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { still(context, member, 256) }
    }
    Box(
        Modifier.size(size)
            .background(Color(0x14FFFFFF), RoundedCornerShape(8.dp))
            .border(
                width = if (excluded) 2.dp else 0.dp,
                color = if (excluded) Color(0xFFE5484D) else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = member.displayName,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).alpha(if (excluded) 0.3f else 1f),
                contentScale = ContentScale.Crop,
            )
        }
        if (member.video) {
            Text(
                "▶", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        if (excluded) {
            Text(
                "✕", color = Color(0xFFE5484D), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** What a tile shows: for a video, the frame where the name was seen, else its thumbnail. */
private fun still(context: android.content.Context, member: GroupMember, px: Int): Bitmap? = runCatching {
    member.atMs?.takeIf { member.video }?.let { ai.dusty.finderplus.index.MediaThumbs.frameAt(context, member.uri, it, px) }
        ?: ai.dusty.finderplus.ui.MediaImages.thumbnail(context, member.uri, px)
}.getOrNull()

/**
 * One item, large, over the whole sheet. A photo shows while its tile is held. A video plays from where
 * the name was seen on to its end, and stays until tapped away.
 */
@Composable
private fun Peek(member: GroupMember, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, member.itemId) {
        if (!member.video) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { still(context, member, 1024) }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xEE000000))
            // Also keeps the tap from reaching the sheet's backdrop, which would close the sheet.
            .clickable(onClick = onClose)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (member.video) {
            ai.dusty.finderplus.ui.InlinePlayer(
                member.uri, member.displayName, member.atMs,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            )
        } else bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = member.displayName,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            member.displayName, color = Dim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun Action(
    text: String,
    accent: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (accent && enabled) Accent.copy(alpha = 0.18f) else Color(0x14FFFFFF),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).alpha(if (enabled) 1f else 0.4f),
    ) {
        Text(
            text,
            color = if (accent && enabled) Accent else Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}




/**
 * Who answers the queue, how it is going, and what the machine has decided so far.
 *
 * Styled like a settings pane, not a wizard: flat rows, thin rules, monospace where the content is an
 * identifier or a count. The selected mode IS the persisted state — picking one arms it (the judge
 * runs whenever indexing mints new questions); "Run now" merely starts a pass immediately.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AssistSettings(
    mode: ai.dusty.finderplus.index.AssistPrefs.Mode,
    onMode: (ai.dusty.finderplus.index.AssistPrefs.Mode) -> Unit,
    provider: ai.dusty.finderplus.index.CloudProvider,
    onProvider: (ai.dusty.finderplus.index.CloudProvider) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    keySaved: Boolean,
    onApiKey: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrl: (String) -> Unit,
    status: SearchViewModel.AssistStatus?,
    onRefresh: () -> Unit,
    onRevertLabel: (String) -> Unit,
    onStart: () -> Unit,
    askUnsure: Boolean,
    onAskUnsure: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // Live while open: run progress moves without any push machinery — the worker writes prefs,
    // this polls them alongside three indexed queries.
    LaunchedEffect(Unit) {
        while (true) { onRefresh(); kotlinx.coroutines.delay(2000) }
    }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🤖 Assist", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                mode.name.lowercase(), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(Accent.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // ---- status ----
        status?.let { st ->
            SectionRule("status")
            val answered = st.run.runYes + st.run.runNo + st.run.runUnsure
            Mono("queue      ${st.pending} waiting")
            Mono("lifetime   ${st.run.totalYes} accepted / ${st.run.totalNo} rejected by AI")
            if (st.run.running && st.run.runTotal > 0) {
                Mono("this run   ${st.run.runDone}/${st.run.runTotal} items · ${st.run.runYes} yes · ${st.run.runNo} no · ${st.run.runUnsure} unsure")
                LinearProgressIndicator(
                    progress = { st.run.runDone.toFloat() / st.run.runTotal },
                    color = Accent, trackColor = Color(0x22FFFFFF),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                )
            } else if (answered > 0) {
                Mono("last run   $answered answered · ${st.run.runUnsure} left for you")
            }
        }

        // ---- which questions ----
        SectionRule("questions")
        ToggleRow(
            "uncertain names",
            "Also ask about object names the describe model was unsure of. Each answer tunes when that name is asked again.",
            askUnsure,
        ) { onAskUnsure(!askUnsure) }

        // ---- mode ----
        SectionRule("mode")
        ModeRow("manual", "You answer every question yourself.",
            mode == ai.dusty.finderplus.index.AssistPrefs.Mode.MANUAL) {
            onMode(ai.dusty.finderplus.index.AssistPrefs.Mode.MANUAL)
        }
        ModeRow("on-device", "Local 4B model. Private, free — minutes per image on this GPU, best left running plugged in.",
            mode == ai.dusty.finderplus.index.AssistPrefs.Mode.LOCAL) {
            onMode(ai.dusty.finderplus.index.AssistPrefs.Mode.LOCAL)
        }
        ModeRow("remote", "An API answers in seconds — or your own computer via ollama, free and private.",
            mode == ai.dusty.finderplus.index.AssistPrefs.Mode.CLOUD) {
            onMode(ai.dusty.finderplus.index.AssistPrefs.Mode.CLOUD)
        }

        // ---- cloud config ----
        if (mode == ai.dusty.finderplus.index.AssistPrefs.Mode.CLOUD) {
            SectionRule("provider")
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ai.dusty.finderplus.index.CloudProvider.entries.forEach { pr ->
                    val sel = pr == provider
                    Text(
                        pr.name.lowercase(),
                        color = if (sel) Accent else Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .border(1.dp, if (sel) Accent.copy(alpha = 0.6f) else Color(0x22FFFFFF), RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onProvider(pr) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            var modelText by remember(provider) { mutableStateOf(model) }
            AssistField(
                value = modelText,
                onValue = { modelText = it; onModel(it) },
                placeholder = "model id",
                mono = true,
            )
            if (provider == ai.dusty.finderplus.index.CloudProvider.OLLAMA) {
                var urlText by remember(provider) { mutableStateOf(ollamaUrl) }
                AssistField(
                    value = urlText,
                    onValue = { urlText = it; onOllamaUrl(it) },
                    placeholder = "http://127.0.0.1:11434",
                    mono = true,
                )
                Text(
                    "No key needed. 127.0.0.1 reaches a USB-connected computer via adb reverse; use its LAN address otherwise.",
                    color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                var keyText by remember(provider) { mutableStateOf("") }
                AssistField(
                    value = keyText,
                    onValue = { keyText = it; onApiKey(it) },
                    placeholder = if (keySaved) "key saved — type to replace" else "API key (stored on this device only)",
                    mono = true,
                )
            }
        }

        // ---- what the AI decided ----
        val judged = status?.judged.orEmpty()
        if (judged.isNotEmpty()) {
            SectionRule("ai decisions")
            Text(
                "Labels the judge applied. Revert takes one back everywhere; single items can be fixed from any preview.",
                color = Dim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            judged.take(8).forEach { lc ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lc.label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("x${lc.n}", color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "revert", color = Color(0xFFE5484D), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(5.dp))
                            .clickable { onRevertLabel(lc.label) }
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode != ai.dusty.finderplus.index.AssistPrefs.Mode.MANUAL) {
                Action("Run now", accent = true, enabled = status?.run?.running != true) { onStart() }
            }
            Action("Back", accent = false, enabled = true, onClick = onBack)
        }
    }
}

/** Thin-rule section header — the settings-pane idiom: a label, not a card. */
@Composable
private fun SectionRule(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color(0x1AFFFFFF)))
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, color = Color(0xFFB9B9C0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .border(1.dp, if (on) Accent.copy(alpha = 0.5f) else Color(0x14FFFFFF), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A square box rather than ModeRow's dot: this is an on/off choice, not one of several.
        Box(
            Modifier.size(12.dp).border(1.5.dp, if (on) Accent else Dim, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Box(Modifier.size(6.dp).background(Accent, RoundedCornerShape(1.dp)))
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = if (on) Accent else Color.White, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .border(1.dp, if (selected) Accent.copy(alpha = 0.5f) else Color(0x14FFFFFF), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A radio dot, drawn not imported: selection state should read at a glance in a row list.
        Box(
            Modifier.size(12.dp).border(1.5.dp, if (selected) Accent else Dim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(5.dp).background(Accent, CircleShape))
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = if (selected) Accent else Color.White, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

@Composable
private fun AssistField(value: String, onValue: (String) -> Unit, placeholder: String, mono: Boolean) {
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp)),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White, fontSize = 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = Dim, fontSize = 12.sp,
                        fontFamily = if (mono) FontFamily.Monospace else null)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One photo and its candidate names. Tapping names chooses them in order, best first; tapping a chosen
 * name takes it back out. Saving answers yes for the chosen names and no for the rest.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RankQuestion(
    group: ReviewGroup,
    restoredNames: List<String>,
    left: Int,
    canUndo: Boolean,
    onRank: (List<String>) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    onAssist: () -> Unit,
    onPeek: (GroupMember?) -> Unit,
) {
    val member = group.members.first()
    val chosen = remember(member.itemId, restoredNames) {
        androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(restoredNames.filter { it in group.candidates }) }
    }
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, member.itemId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { still(context, member, 720) }
    }
    Column(Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text(
                "🤖 Assist", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAssist).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (canUndo) {
                Text(
                    "↩️ Undo", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onUndo).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            "Which names fit? Best first.",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            buildString {
                append(doubtSummary(group))
                append(" · tap names in order of fit · unchosen names are marked wrong")
                if (member.video) append(" · hold the picture to play the video")
                append(" · $left left")
            },
            color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Box(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))
                .pointerInput(member.itemId) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                                if (e.changes.none { it.pressed }) return@withTimeoutOrNull true
                            }
                            @Suppress("UNREACHABLE_CODE") true
                        }
                        if (released == null) onPeek(member)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let {
                Image(
                    it.asImageBitmap(), contentDescription = member.displayName,
                    modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit,
                )
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (name in group.candidates) {
                val rank = chosen.indexOf(name)
                Surface(
                    color = if (rank >= 0) Accent.copy(alpha = 0.22f) else Color(0x14FFFFFF),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        if (rank >= 0) chosen.removeAt(rank) else chosen.add(name)
                    },
                ) {
                    Text(
                        if (rank >= 0) "${rank + 1}  $name" else name,
                        color = if (rank >= 0) Accent else Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Action(
                text = if (chosen.isEmpty()) "❌ None of these" else "💾 Save ${chosen.size} of ${group.candidates.size}",
                accent = true, enabled = true, modifier = Modifier.weight(1f),
            ) { onRank(chosen.toList()) }
            Action(text = "✕ Clear", accent = false, enabled = chosen.isNotEmpty()) { chosen.clear() }
            Text(
                "✕ Close", color = Dim, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
    }
}
