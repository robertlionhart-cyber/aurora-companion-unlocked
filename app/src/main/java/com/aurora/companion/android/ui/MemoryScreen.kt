@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.KIND_MANUAL
import com.aurora.companion.android.KIND_SUMMARY
import com.aurora.companion.android.MemoryNote
import com.aurora.companion.android.UiState
import java.text.DateFormat
import java.util.Date

/** Running memory (0.8.3): what the current companion remembers about you. View, edit, delete, add, or wipe. */
@Composable
fun MemoryScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    LaunchedEffect(ui.companionId) { vm.refreshMemoryNotes() }
    var editing by remember { mutableStateOf<MemoryNote?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    val name = ui.companion.name

    if (adding || editing != null) {
        NoteDialog(editing, onDismiss = { adding = false; editing = null }) { text ->
            val e = editing
            if (e == null) vm.addMemoryNote(text) else vm.editMemoryNote(e.id, text)
            adding = false; editing = null
        }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Forget everything?") },
        text = { Text("$name will forget all the notes below. Your saved conversations in History are not deleted.") },
        confirmButton = { TextButton(onClick = { confirmWipe = false; vm.wipeMemory() }) { Text("Forget all", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
    )

    val facts = ui.memoryNotes.filter { it.kind != KIND_SUMMARY }.sortedByDescending { it.updated }
    val chats = ui.memoryNotes.filter { it.kind == KIND_SUMMARY }.sortedByDescending { it.updated }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            Row(Modifier.padding(start = sizing.pad, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83E\uDDE0 Memory", fontSize = (sizing.body + 8).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = sizing.pad, end = sizing.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("After each chat or call, $name writes a few short notes about you and brings them into new chats. " +
                            "They stay on this phone only. Changes apply from your next new chat.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Remember things from our chats", fontSize = 15.sp)
                                Text(if (ui.memoryOn) "On" else "Off: every chat starts fresh", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = ui.memoryOn, onCheckedChange = { vm.setMemoryOn(it) })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { adding = true }) { Text("+ Add a note") }
                            if (ui.memoryNotes.isNotEmpty()) OutlinedButton(onClick = { confirmWipe = true }) { Text("Forget everything", color = Color(0xFFFF6B6B)) }
                        }
                        if (ui.memoryBusy) Text("$name is writing notes right now...", fontSize = 13.sp, color = AuroraTeal)
                        Text("Things $name remembers (${facts.size})", fontWeight = FontWeight.Bold, color = AuroraTeal, fontSize = 15.sp)
                        if (facts.isEmpty()) Text("Nothing yet. Chat for a bit, then start a new chat or end a call.", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(facts, key = { it.id }) { n -> NoteRow(n, onEdit = { editing = n }, onDelete = { vm.deleteMemoryNote(n.id) }) }
                if (chats.isNotEmpty()) {
                    item { Text("Recent chats", fontWeight = FontWeight.Bold, color = AuroraTeal, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
                    items(chats, key = { it.id }) { n -> NoteRow(n, onEdit = { editing = n }, onDelete = { vm.deleteMemoryNote(n.id) }) }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(n: MemoryNote, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(n.text, fontSize = 15.sp)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(n.updated))
        Text((if (n.kind == KIND_MANUAL) "Added by you" else "Noted") + " \u00B7 " + date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            OutlinedButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF6B6B)) }
        }
    }
}

@Composable
private fun NoteDialog(note: MemoryNote?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(note?.text ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "Add a note" else "Edit note") },
        text = {
            OutlinedTextField(text, { text = it.take(300) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                placeholder = { Text("e.g. My dog is called Max") })
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
