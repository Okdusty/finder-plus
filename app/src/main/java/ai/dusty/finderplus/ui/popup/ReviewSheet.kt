package ai.rightone.finderplus.ui.popup

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import ai.rightone.finderplus.index.GroupKind
import ai.rightone.finderplus.index.GroupMember
import ai.rightone.finderplus.index.ReviewGroup

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
    canUndo: Boolean,
    assistMode: ai.rightone.finderplus.index.AssistPrefs.Mode,
    onAssistMode: (ai.rightone.finderplus.index.AssistPrefs.Mode) -> Unit,
    assistProvider: ai.rightone.finderplus.index.CloudProvider,
    onAssistProvider: (ai.rightone.finderplus.index.CloudProvider) -> Unit,
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
    onAnswerConcept: (label: String, accepted: List<Long>, declined: List<Long>) -> Unit,
    onNamePerson: (faceIds: List<Long>, name: String) -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
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
                    onBack = { showAssist = false },
                )
                return@Surface
            }

            if (groups.isEmpty()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Nothing to review", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Labels the classifier is confident about are applied automatically. " +
                            "Anything it is unsure about will appear here once indexing reaches it.",
                        color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        // Still reachable with an empty queue: the AI-decision log and assist config
                        // are exactly what the user wants to inspect *after* the judge has worked.
                        Text(
                            "Assist", color = Dim, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showAssist = true },
                        )
                        Text(
                            "Close", color = Accent, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onClose),
                        )
                    }
                }
                return@Surface
            }

            val group = groups.first()
            // Keyed on the group so answering one and advancing to the next resets the selection —
            // otherwise the previous group's rejections would silently carry over.
            val rejected = remember(group.label, group.members.size) { mutableStateMapOf<Long, Boolean>() }
            var name by remember(group.label) { mutableStateOf("") }

            // Person questions wear their own colour. After a hundred near-identical cards the eye
            // stops reading and starts pattern-tapping — which is precisely when wrong answers happen
            // (and why undo exists). A hue shift per question *kind* is the cheapest possible "this one
            // is different, look again" signal.
            val kindAccent = if (group.kind == GroupKind.PERSON) PersonAccent else Accent

            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = kindAccent.copy(alpha = 0.16f), shape = RoundedCornerShape(7.dp)) {
                        Text(
                            if (group.kind == GroupKind.PERSON) "WHO?" else "WHAT?",
                            color = kindAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // The escape hatch from manual supervision: hand the whole queue to a stronger
                    // model. Person questions always stay human — a judge cannot know names.
                    Text(
                        "Assist",
                        color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { showAssist = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    if (canUndo) {
                        Text(
                            "Undo",
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
                        if (group.kind == GroupKind.PERSON) {
                            append("grouped by face similarity %.0f%%".format(group.cohesion * 100))
                        } else {
                            val lo = group.members.minOf { it.score }
                            val hi = group.members.maxOf { it.score }
                            append("model is unsure (%.0f–%.0f%%)".format(lo * 100, hi * 100))
                        }
                        append(" · tap a photo to exclude it · ${groups.size} left")
                    },
                    color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(78.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(group.members, key = { it.itemId }) { m ->
                        MemberTile(
                            member = m,
                            excluded = rejected[m.itemId] == true,
                            onToggle = { rejected[m.itemId] = rejected[m.itemId] != true },
                        )
                    }
                }

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
                        text = if (group.kind == GroupKind.PERSON) {
                            if (name.isBlank()) "Type a name" else "Save “${name.trim()}”"
                        } else "Yes — ${kept.size} of ${group.members.size}",
                        accent = true,
                        enabled = if (group.kind == GroupKind.PERSON) name.isNotBlank() else kept.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (group.kind == GroupKind.PERSON) {
                            onNamePerson(group.faceIds, name)
                        } else {
                            onAnswerConcept(group.label, kept, dropped)
                        }
                    }
                    Action(text = "None", accent = false, enabled = true) {
                        if (group.kind == GroupKind.PERSON) onNamePerson(emptyList(), "")
                        else onAnswerConcept(group.label, emptyList(), group.members.map { it.itemId })
                    }
                    // Deliberately the smallest thing on the row. Skip is the non-answer; giving it
                    // equal visual weight taught the thumb to reach for it.
                    Text(
                        "Skip",
                        color = Dim, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSkip)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberTile(member: GroupMember, excluded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, member.itemId) {
        value = runCatching {
            ai.rightone.finderplus.ui.MediaImages.thumbnail(context, member.uri, 256)
        }.getOrNull()
    }
    Box(
        Modifier.size(78.dp)
            .background(Color(0x14FFFFFF), RoundedCornerShape(8.dp))
            .border(
                width = if (excluded) 2.dp else 0.dp,
                color = if (excluded) Color(0xFFE5484D) else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onToggle),
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = member.displayName,
                modifier = Modifier.fillMaxSize().alpha(if (excluded) 0.3f else 1f),
                contentScale = ContentScale.Crop,
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


/** Identity questions wear the scheme's tertiary — a different job deserves a different colour. */
internal val PersonAccent: Color @Composable get() = MaterialTheme.colorScheme.tertiary


/**
 * Who answers the queue, how it is going, and what the machine has decided so far.
 *
 * Styled like a settings pane, not a wizard: flat rows, thin rules, monospace where the content is an
 * identifier or a count. The selected mode IS the persisted state — picking one arms it (the judge
 * runs whenever indexing mints new questions); "Run now" merely starts a pass immediately.
 */
@Composable
private fun AssistSettings(
    mode: ai.rightone.finderplus.index.AssistPrefs.Mode,
    onMode: (ai.rightone.finderplus.index.AssistPrefs.Mode) -> Unit,
    provider: ai.rightone.finderplus.index.CloudProvider,
    onProvider: (ai.rightone.finderplus.index.CloudProvider) -> Unit,
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
            Text("Assist", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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

        // ---- mode ----
        SectionRule("mode")
        ModeRow("manual", "You answer every question yourself.",
            mode == ai.rightone.finderplus.index.AssistPrefs.Mode.MANUAL) {
            onMode(ai.rightone.finderplus.index.AssistPrefs.Mode.MANUAL)
        }
        ModeRow("on-device", "Local 4B model. Private, free — minutes per image on this GPU, best left running plugged in.",
            mode == ai.rightone.finderplus.index.AssistPrefs.Mode.LOCAL) {
            onMode(ai.rightone.finderplus.index.AssistPrefs.Mode.LOCAL)
        }
        ModeRow("remote", "An API answers in seconds — or your own computer via ollama, free and private.",
            mode == ai.rightone.finderplus.index.AssistPrefs.Mode.CLOUD) {
            onMode(ai.rightone.finderplus.index.AssistPrefs.Mode.CLOUD)
        }

        // ---- cloud config ----
        if (mode == ai.rightone.finderplus.index.AssistPrefs.Mode.CLOUD) {
            SectionRule("provider")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ai.rightone.finderplus.index.CloudProvider.entries.forEach { pr ->
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
            if (provider == ai.rightone.finderplus.index.CloudProvider.OLLAMA) {
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
            if (mode != ai.rightone.finderplus.index.AssistPrefs.Mode.MANUAL) {
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
