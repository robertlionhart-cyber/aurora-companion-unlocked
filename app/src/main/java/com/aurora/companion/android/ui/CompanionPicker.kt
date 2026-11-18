@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import android.content.Intent
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.Companions
import com.aurora.companion.android.UiState

/**
 * Companion picker: shown on first run, and from Settings / the header avatar.
 * Tapping a card previews that companion's animated idle clip; "Chat with X" confirms.
 */
@Composable
fun CompanionPicker(ui: UiState, vm: AuroraViewModel, sizing: Sizing, firstRun: Boolean, onClose: () -> Unit) {
    var pick by remember { mutableStateOf(ui.companionId) }
    if (!firstRun) androidx.activity.compose.BackHandler(onBack = onClose)
    // A freshly saved character becomes the highlighted pick.
    LaunchedEffect(ui.lastSavedCharacter) { ui.lastSavedCharacter?.let { id -> if (ui.characters.any { it.id == id }) pick = id } }
    val packComps = remember(ui.packs.revision, ui.packs.installed) { Companions.packCompanions }
    if (ui.characters.none { it.id == pick } && Companions.builtIns.none { it.id == pick } && packComps.none { it.id == pick }) pick = Companions.AURORA.id
    val ctx = LocalContext.current
    var exportId by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCharacter(uri) }
    val savePack = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId; if (uri != null && id != null) vm.exportCharacter(id, uri, card = false)
    }
    val saveCard = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId; if (uri != null && id != null) vm.exportCharacter(id, uri, card = true)
    }
    // 0.9.7: built-ins, then the looks from downloaded avatar packs, then your own characters.
    val everyone = Companions.builtIns + packComps + ui.characters
    val current = everyone.firstOrNull { it.id == pick } ?: Companions.AURORA
    if (showExport && current.custom) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            confirmButton = { TextButton(onClick = { showExport = false }) { Text("Close") } },
            title = { Text("Export ${current.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aurora companion pack (.zip) works with the desktop app (Settings > Import companion pack) and this app. " +
                        "A character card (.json) works with SillyTavern-style apps. Chats are never included.", fontSize = 13.sp)
                    Button(onClick = { showExport = false; exportId = current.id; savePack.launch(vm.exportFileName(current.id, false)) }, modifier = Modifier.fillMaxWidth()) { Text("Save Aurora pack (.zip)") }
                    OutlinedButton(onClick = { showExport = false; exportId = current.id; saveCard.launch(vm.exportFileName(current.id, true)) }, modifier = Modifier.fillMaxWidth()) { Text("Save character card (.json)") }
                    OutlinedButton(onClick = {
                        showExport = false
                        vm.shareFile(current.id)?.let { f ->
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "${current.name} - Aurora companion pack").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, "Share ${current.name}"))
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Share...") }
                }
            },
        )
    }
    ui.editor?.let { CharacterEditor(ui, vm, sizing, it); return }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(sizing.pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Brand(sizing, if (firstRun) "Who would you like to talk to?" else "Change companion")
            val sel = current
            CompanionAvatar(sel, Companions.STATE_IDLE, if (sizing.wide) 200.dp else 150.dp, animate = ui.animateAvatar,
                call = true, ring = AuroraTeal, ringWidth = 4.dp)
            Text(sel.name, fontSize = (sizing.body + 6).sp, fontWeight = FontWeight.Bold)
            Text(sel.tagline, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
            val cols = if (sizing.wide) 5 else 3
            everyone.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    row.forEach { c ->
                        val on = c.id == pick
                        Column(
                            Modifier.width(if (sizing.wide) 130.dp else 100.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (on) PanelHi else Panel)
                                .border(BorderStroke(2.dp, if (on) AuroraTeal else Color.Transparent), RoundedCornerShape(16.dp))
                                .clickable { pick = c.id }
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CompanionAvatar(c, Companions.STATE_IDLE, if (sizing.wide) 84.dp else 64.dp, animate = false)
                            Spacer(Modifier.height(6.dp))
                            Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (c.custom) "Yours" else c.kind, fontSize = 12.sp, color = if (c.custom) AuroraTeal else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // Character actions
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = vm::newCharacter) { Text("+ Create") }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import file") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                if (sel.custom) TextButton(onClick = { vm.editCharacter(sel.id) }) { Text("Edit") }
                TextButton(onClick = { vm.duplicateCharacter(sel.id) }) { Text(if (sel.custom) "Duplicate" else "Duplicate & customize") }
                if (sel.custom) TextButton(onClick = { showExport = true }) { Text("Export / share") }
            }
            Text("Import: Aurora companion pack (.zip) or profile (.json) from the desktop app, character cards (TavernAI / SillyTavern .json or .png), " +
                "or personality text (.txt). " + if (ui.adultMode) "Adult mode is on: imports marked as adult can use mature themes."
                else if (!com.aurora.companion.android.Edition.UNLOCKED) "Imports are always kept family-friendly."
                else "Imports are kept family-friendly unless 18+ adult mode is on (Settings).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            val switching = !firstRun && pick != ui.companionId
            if (switching && ui.loadedModelName != null) {
                Text("Switching companion starts a new chat (the AI reloads with ${sel.name}'s personality).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Text("Voice: Robert's voice by default. You can give each companion its own voice in Settings.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = { vm.selectCompanion(pick); onClose() }, modifier = Modifier.fillMaxWidth(if (sizing.wide) 0.5f else 1f).height(52.dp)) {
                Text("Chat with ${sel.name}")
            }
            if (!firstRun) OutlinedButton(onClick = onClose) { Text("Cancel") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
