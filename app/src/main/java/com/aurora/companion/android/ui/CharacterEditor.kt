@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import com.aurora.companion.android.CharacterEdit
import com.aurora.companion.android.CompanionProfile
import com.aurora.companion.android.Companions
import com.aurora.companion.android.UiState
import com.aurora.companion.android.VoiceCatalog

private const val LIBRITTS_SPEAKERS = 904

/** Create / edit a character: name, look, personality, scenario, greeting, memories, voice. */
@Composable
fun CharacterEditor(ui: UiState, vm: AuroraViewModel, sizing: Sizing, start: CharacterEdit) {
    var name by remember(start) { mutableStateOf(start.name) }
    var look by remember(start) { mutableStateOf(start.look) }
    var personality by remember(start) { mutableStateOf(start.personality) }
    var scenario by remember(start) { mutableStateOf(start.scenario) }
    var greeting by remember(start) { mutableStateOf(start.greeting) }
    var facts by remember(start) { mutableStateOf(start.facts) }
    var voiceId by remember(start) { mutableStateOf(start.voiceId) }
    var speaker by remember(start) { mutableStateOf(start.speakerId.coerceIn(0, LIBRITTS_SPEAKERS - 1)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var adult by remember(start) { mutableStateOf(start.adultFlagged) }
    androidx.activity.compose.BackHandler(onBack = vm::closeEditor)
    val isNew = start.id == null
    val canSave = name.isNotBlank() && personality.trim().length >= 10

    if (confirmDelete && start.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCharacter(start.id) }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Delete $name?") },
            text = { Text("This removes the character from this device. Export it first if you want to keep a copy.") },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(sizing.pad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (isNew) "New character" else "Edit character", fontSize = (sizing.body + 7).sp, fontWeight = FontWeight.Bold)
            // Family-friendly notice; adult characters only lose the safety rule while 18+ adult mode is on (0.8.4).
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelHi).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (ui.adultMode && adult) "Adult character (18+)" else "Family-friendly", fontWeight = FontWeight.SemiBold,
                    color = if (ui.adultMode && adult) AuroraPink else AuroraTeal, fontSize = 14.sp)
                Text(if (ui.adultMode) "Adult mode is on. Characters marked as adult can talk about mature themes; all others keep " +
                        "a safety rule that keeps replies age-appropriate."
                    else if (!com.aurora.companion.android.Edition.UNLOCKED) "Every custom or imported character gets a safety rule that keeps replies age-appropriate."
                    else "Every custom or imported character gets a safety rule that keeps replies age-appropriate. " +
                        "Adult content needs 18+ adult mode (Settings).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (adult && !ui.adultMode) Text(if (com.aurora.companion.android.Edition.UNLOCKED) "This one is marked as adult content, so it will be kept family-friendly while adult mode is off."
                    else "This one looks like it was written for adult content; it is kept family-friendly.",
                    fontSize = 12.sp, color = AuroraPink)
                if (ui.adultMode) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Adult character (18+)", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = adult, onCheckedChange = { adult = it })
                }
                start.notes.forEach { Text("- $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Look", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Companions.looks.forEach { l ->
                    val on = l.id == look
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (sizing.wide) 84.dp else 62.dp).clickable { look = l.id }) {
                        CompanionAvatar(l, Companions.STATE_IDLE, if (sizing.wide) 72.dp else 52.dp, animate = false,
                            ring = if (on) AuroraTeal else null, ringWidth = 3.dp)
                        Text(l.name, fontSize = 12.sp, color = if (on) AuroraTeal else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            OutlinedTextField(personality, { personality = it.take(CompanionProfile.MAX_PERSONALITY) },
                label = { Text("Personality / instructions") },
                placeholder = { Text("Who are they? How do they talk? What do they like? (This becomes the AI's system prompt.)") },
                minLines = 5, maxLines = 14, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${personality.length} / ${CompanionProfile.MAX_PERSONALITY}  -  shorter works better on small on-device models") })
            OutlinedTextField(scenario, { scenario = it.take(1500) }, label = { Text("Scenario (optional)") },
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(greeting, { greeting = it.take(600) }, label = { Text("Greeting (first message)") },
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(facts, { facts = it.take(4000) }, label = { Text("Memories (optional, one per line)") },
                placeholder = { Text("e.g. Robert likes camping\nRobert's dog is called Max") },
                minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())

            Text("Voice", fontWeight = FontWeight.SemiBold)
            VoiceCatalog.all.forEach { v ->
                val installed = v.id in ui.installedVoices
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = installed) { voiceId = v.id }) {
                    RadioButton(selected = voiceId == v.id, enabled = installed, onClick = { voiceId = v.id })
                    Text(v.title + if (!installed) "  (download it in Settings first)" else "", fontSize = 14.sp,
                        color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (voiceId == VoiceCatalog.LIBRITTS.id) {
                Text("LibriTTS speaker: #$speaker", fontSize = 13.sp)
                var pos by remember(speaker) { mutableStateOf(speaker.toFloat()) }
                Slider(value = pos, onValueChange = { pos = it }, onValueChangeFinished = { speaker = pos.toInt() },
                    valueRange = 0f..(LIBRITTS_SPEAKERS - 1).toFloat())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { speaker = (speaker - 1).coerceAtLeast(0) }) { Text("\u25C0") }
                    OutlinedButton(onClick = { speaker = (speaker + 1).coerceAtMost(LIBRITTS_SPEAKERS - 1) }) { Text("\u25B6") }
                    OutlinedButton(onClick = { speaker = (0 until LIBRITTS_SPEAKERS).random() }) { Text("Random") }
                }
                Text("Tip: to hear speakers, open Settings with this character selected and use Test voice.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!canSave) Text("Give it a name and at least a sentence of personality.", fontSize = 12.sp, color = AuroraPink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(enabled = canSave, modifier = Modifier.heightIn(min = 50.dp), onClick = {
                    vm.saveCharacter(start.copy(name = name.trim(), look = look, personality = personality, scenario = scenario,
                        greeting = greeting, facts = facts, voiceId = voiceId, speakerId = speaker, adultFlagged = adult))
                }) { Text(if (isNew) "Save character" else "Save changes") }
                OutlinedButton(onClick = vm::closeEditor, modifier = Modifier.heightIn(min = 50.dp)) { Text("Cancel") }
            }
            if (!isNew && start.id != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.duplicateCharacter(start.id) }) { Text("Duplicate") }
                    OutlinedButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B)),
                        border = BorderStroke(1.dp, Color(0xFFFF6B6B))) { Text("Delete") }
                }
                if (ui.companionId == start.id) Text("Saving changes to the current companion restarts the chat with the new personality.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
