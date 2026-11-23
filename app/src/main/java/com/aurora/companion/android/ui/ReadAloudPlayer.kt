@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.companion.android.ReadAloud
import kotlinx.coroutines.delay

private val PlayerBg = Color(0xE6101418)

/**
 * 1.0 read-aloud player. Big, simple buttons (easy for kids and at bedtime): previous chapter, back, play / pause,
 * forward, next chapter. Tap back / forward = one sentence, hold = one paragraph. Below: the sentence being read,
 * chapter progress, speed, the bedtime (sleep) timer and Close. [compact] is the slimmer version over the chat.
 */
@Composable
fun ReadAloudPlayer(
    st: ReadAloud.State, compact: Boolean, modifier: Modifier = Modifier,
    /** 1.0.2: Close (default: stop reading; the place in the book is kept). */
    onClose: () -> Unit = { ReadAloud.stop() },
    /** 1.0.2: chat screen only - shrink the big player to the small bar / grow it back. */
    onMinimize: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
) {
    var showChapters by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    if (showChapters) ChaptersDialog(st) { showChapters = false }
    Column(
        modifier.widthIn(max = 860.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PlayerBg)
            .border(1.dp, AuroraTeal.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\uD83D\uDCD6", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).clickable(enabled = st.chapterTitles.size > 1) { showChapters = true }) {
                Text(st.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                val sub = when {
                    st.loading -> "Opening the book..."
                    st.error != null -> st.error
                    st.finished -> "The end! Tap \u25B6 to start again."
                    else -> st.chapterTitle.ifBlank { st.author } + if (st.chapterTitles.size > 1) "  \u25BE" else ""
                }
                Text(sub, fontSize = 12.sp, color = if (st.error != null) Color(0xFFFF8A80) else AuroraTeal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onExpand != null) TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "Show the book controls" }) { Text("\u25B2", fontSize = 18.sp) }
            if (onMinimize != null) TextButton(onClick = onMinimize, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "Make the book controls smaller" }) { Text("\u25BC", fontSize = 18.sp) }
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (onMinimize != null) "\u2715 Close" else "Close", fontSize = if (onMinimize != null) 16.sp else 14.sp)
            }
        }
        if (!compact && st.current.isNotBlank() && !st.loading) {
            Text(st.current, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp, color = Color(0xFFE8E4DA),
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FFFFFF)).padding(10.dp))
        }
        LinearProgressIndicator(progress = { st.chapterProgress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = AuroraTeal, trackColor = Color(0x33FFFFFF))
        // Big transport buttons.
        val big: Dp = if (compact) 58.dp else 72.dp
        val small: Dp = if (compact) 46.dp else 54.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("\u23EE", small, "Previous chapter", enabled = !st.loading) { ReadAloud.chapter(-1) }
            RoundBtn("\u23EA", small, "Back a sentence (hold: a paragraph)", enabled = !st.loading, onLong = { ReadAloud.skipParagraph(-1) }) { ReadAloud.skipSentence(-1) }
            RoundBtn(if (st.playing) "\u23F8" else "\u25B6", big, if (st.playing) "Pause" else "Read", enabled = !st.loading, accent = true) { ReadAloud.toggle() }
            RoundBtn("\u23E9", small, "Forward a sentence (hold: a paragraph)", enabled = !st.loading, onLong = { ReadAloud.skipParagraph(1) }) { ReadAloud.skipSentence(1) }
            RoundBtn("\u23ED", small, "Next chapter", enabled = !st.loading) { ReadAloud.chapter(1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box {
                Chip(speedLabel(st.speed)) { speedMenu = true }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    ReadAloud.SPEEDS.forEach { sp ->
                        DropdownMenuItem(text = { Text(speedLabel(sp) + if (sp == st.speed) "  \u2713" else "", fontSize = 16.sp) },
                            onClick = { speedMenu = false; ReadAloud.setSpeed(sp) })
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box {
                Chip(sleepLabel(st), on = st.sleepOn) { sleepMenu = true }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    Text("Bedtime timer", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    ReadAloud.SLEEP_CHOICES.forEach { m ->
                        DropdownMenuItem(text = { Text("\uD83C\uDF19  Stop in $m minutes", fontSize = 16.sp) }, onClick = { sleepMenu = false; ReadAloud.setSleep(m) })
                    }
                    DropdownMenuItem(text = { Text("\uD83D\uDCD5  Stop at the end of this chapter", fontSize = 16.sp) },
                        onClick = { sleepMenu = false; ReadAloud.setSleep(ReadAloud.SLEEP_END_OF_CHAPTER) })
                    if (st.sleepOn) DropdownMenuItem(text = { Text("Timer off", fontSize = 16.sp) }, onClick = { sleepMenu = false; ReadAloud.setSleep(0) })
                }
            }
            if (!compact) {
                Spacer(Modifier.width(10.dp))
                Text("${(st.bookProgress * 100).toInt()}% of the book", fontSize = 12.sp, color = Color(0xAAFFFFFF))
            }
        }
    }
}

private fun speedLabel(sp: Float) = if (sp == 1.0f) "Speed: normal" else "Speed: ${"%.2f".format(java.util.Locale.US, sp).trimEnd('0').trimEnd('.')}x"

@Composable
private fun sleepLabel(st: ReadAloud.State): String {
    val at = st.sleepAt
    if (st.sleepEndOfChapter) return "\uD83C\uDF19 End of chapter"
    if (at == null) return "\uD83C\uDF19 Bedtime timer"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val min = ((at - now) / 60_000L + 1).coerceAtLeast(1)
    return "\uD83C\uDF19 $min min left"
}

@Composable
private fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
    Text(label, fontSize = 14.sp, color = if (on) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) AuroraTeal else Color(0x26FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun RoundBtn(glyph: String, size: Dp, label: String, enabled: Boolean = true, accent: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (accent) AuroraTeal else Color(0x26FFFFFF))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLong)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = (size.value * 0.42f).sp, color = if (accent) Color.Black else if (enabled) Color.White else Color(0x66FFFFFF), textAlign = TextAlign.Center) }
}

@Composable
private fun ChaptersDialog(st: ReadAloud.State, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Chapters") },
        text = {
            val ls = rememberLazyListState(initialFirstVisibleItemIndex = (st.chapter - 2).coerceAtLeast(0))
            LazyColumn(state = ls, modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(st.chapterTitles) { i, t ->
                    Text((if (i == st.chapter) "\u25B6  " else "") + t, fontSize = 16.sp, fontWeight = if (i == st.chapter) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == st.chapter) AuroraTeal else Color.Unspecified,
                        modifier = Modifier.fillMaxWidth().clickable { ReadAloud.goToChapter(i); onClose() }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
