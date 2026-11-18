@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.Companions
import com.aurora.companion.android.Conversation
import com.aurora.companion.android.HistoryStore
import com.aurora.companion.android.UiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 0.8.2 conversation history: list grouped by day, read-only transcript, share / save .txt, delete. */
@Composable
fun HistoryScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing, onClose: () -> Unit) {
    var open by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(Unit) { vm.loadHistory() }
    open?.let { c ->
        // Keep showing the latest copy (the open one may still be growing if it is the current chat).
        val cur = ui.history.firstOrNull { it.id == c.id } ?: c
        TranscriptView(cur, ui, vm, sizing, onBack = { open = null }, onContinue = { vm.continueConversation(cur); open = null; onClose() })
        return
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val groups = ui.history.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            Row(Modifier.padding(start = sizing.pad, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDD58 History", fontSize = (sizing.body + 8).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = sizing.pad, end = sizing.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Chats and calls are saved here on this phone only, for you to read back. They are not sent anywhere " +
                        "and the AI doesn't read them (each new chat starts fresh).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ui.history.isEmpty()) item {
                    Text("No conversations yet. Chat or call, and they show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp))
                }
                groups.forEach { (day, list) ->
                    item(key = "d$day") {
                        Text(when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> HistoryStore.day(list.first().start) },
                            fontWeight = FontWeight.Bold, color = AuroraTeal, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(list, key = { it.id }) { c -> ConversationRow(c, ui) { open = c } }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, ui: UiState, onClick: () -> Unit) {
    val comp = ui.characters.firstOrNull { it.id == c.companionId } ?: Companions.byId(c.companionId)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CompanionAvatar(comp, Companions.STATE_IDLE, 44.dp, animate = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((if (c.mode == HistoryStore.MODE_CALL) "\uD83D\uDCDE Call" else "\uD83D\uDCAC Chat") +
                " with ${c.companionName}  -  ${HistoryStore.time(c.start)}  -  ${HistoryStore.duration(c.durationMs)}  -  ${c.messages.size} messages",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscriptView(c: Conversation, ui: UiState, vm: AuroraViewModel, sizing: Sizing, onBack: () -> Unit, onContinue: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.exportConversation(c, uri) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this conversation?") },
        text = { Text("It is removed from History on this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteConversation(c.id); onBack() }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            Row(Modifier.padding(start = sizing.pad, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (c.mode == HistoryStore.MODE_CALL) "Call" else "Chat") + " with ${c.companionName}", fontSize = (sizing.body + 4).sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${HistoryStore.day(c.start)}, ${HistoryStore.time(c.start)} - ${HistoryStore.time(c.end)} (${HistoryStore.duration(c.durationMs)})",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text("Back") }
            }
            FlowRow(Modifier.padding(horizontal = sizing.pad, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onContinue, enabled = ui.loadedModelName != null && !ui.generating) { Text("\u25B6 Continue") }
                OutlinedButton(onClick = {
                    vm.conversationShareFile(c)?.let { f ->
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Conversation with ${c.companionName}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share conversation"))
                    }
                }) { Text("Share .txt") }
                OutlinedButton(onClick = { saver.launch(HistoryStore.fileName(c)) }) { Text("Save .txt") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = sizing.pad, end = sizing.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(c.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.user) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.fillMaxWidth(0.86f).wrapContentWidth(if (m.user) Alignment.End else Alignment.Start)
                            .clip(RoundedCornerShape(14.dp)).background(if (m.user) UserBubble else Panel).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text((if (m.user) "You" else c.companionName) + "  " + HistoryStore.time(m.time), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(m.text, fontSize = sizing.body.sp)
                        }
                    }
                }
            }
        }
    }
}
