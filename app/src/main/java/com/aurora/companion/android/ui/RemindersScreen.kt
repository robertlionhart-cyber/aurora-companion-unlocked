@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.Companions
import com.aurora.companion.android.Reminder
import com.aurora.companion.android.ReminderAlarms
import com.aurora.companion.android.ReminderParser
import com.aurora.companion.android.UiState
import java.time.LocalDateTime

/** Reminders screen: list, add, edit, delete, snooze, done. Opened from Settings or the tablet side panel. */
@Composable
fun RemindersScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Re-check permissions when coming back from system settings.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val canNotify = remember(tick, ui.reminders) { ReminderAlarms.canNotify(ctx) }
    val canExact = remember(tick) { ReminderAlarms.canExact(ctx) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    if (adding || editing != null) {
        ReminderDialog(vm, editing, onClose = { adding = false; editing = null })
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            Row(Modifier.padding(start = sizing.pad, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\u23F0 Reminders", fontSize = (sizing.body + 8).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            val now = LocalDateTime.now()
            // One scrolling list (header + reminders), so nothing is cut off with big fonts.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = sizing.pad, end = sizing.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add here, or just say it in chat or by voice: \"remind me to call mom at 5pm\", \"in 20 minutes\", " +
                            "\"tomorrow at 9\", \"every day at 8am\". Ask \"what's on my schedule?\" any time.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!canNotify) WarnCard("Notifications are off, so reminders can't pop up.", "Turn on") {
                            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                        }
                        if (!canExact && Build.VERSION.SDK_INT >= 31) WarnCard("Exact alarms are not allowed, so reminders may arrive a few minutes late.", "Allow") {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { adding = true }) { Text("+ Add reminder") }
                            if (ui.reminders.any { it.done }) OutlinedButton(onClick = vm::clearCompletedReminders) { Text("Clear completed") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Speak reminders when the app is open", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = ui.speakReminders, onCheckedChange = vm::setSpeakReminders)
                        }
                        if (ui.reminders.isEmpty()) {
                            Text("No reminders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                        }
                    }
                }
                items(ui.reminders, key = { it.id }) { r -> ReminderRow(r, now, vm, onEdit = { editing = r }) }
            }
        }
    }
}

@Composable
private fun WarnCard(text: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A2A12)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, color = Color(0xFFFFD58A), modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun ReminderRow(r: Reminder, now: LocalDateTime, vm: AuroraViewModel, onEdit: () -> Unit) {
    val at = r.fireAt ?: r.due
    val overdue = !r.done && r.repeat == null && r.due != null && r.notified && r.snoozeUntil == null
    val c = Companions.byId(r.companion)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable(enabled = !r.done) { onEdit() }.padding(10.dp).alpha(if (r.done) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompanionAvatar(c, Companions.STATE_IDLE, 40.dp, animate = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.text.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                when {
                    r.done -> "Done"
                    overdue -> "Due ${ReminderParser.formatWhen(r.due, null, now)}"
                    r.snoozeUntil != null -> "Snoozed until ${ReminderParser.formatWhen(r.snoozeUntil, null, now)}"
                    else -> ReminderParser.formatWhen(at, r.repeat, now)
                },
                fontSize = 12.sp, color = if (overdue) AuroraPink else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!r.done) Row {
                TextButton(onClick = { vm.snoozeReminder(r.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Snooze", fontSize = 12.sp) }
                TextButton(onClick = { vm.doneReminder(r.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Done", fontSize = 12.sp) }
            }
        }
        TextButton(onClick = { vm.deleteReminder(r.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("\u2715", color = Color(0xFFFF6B6B)) }
    }
}

@Composable
private fun ReminderDialog(vm: AuroraViewModel, existing: Reminder?, onClose: () -> Unit) {
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var whenText by remember { mutableStateOf("") }
    val startRepeat = existing?.repeat?.let { if (it.startsWith("weekly")) "weekly" else it }
    var repeat by remember { mutableStateOf(startRepeat) }
    val preview = vm.previewWhen(whenText)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "New reminder" else "Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(240) }, label = { Text("What") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(whenText, { whenText = it.take(80) }, label = { Text("When") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("in 20 minutes / at 5pm / tomorrow at 9") })
                Text(
                    when {
                        whenText.isNotBlank() -> preview
                        existing?.due != null -> "Keeps: ${ReminderParser.formatWhen(existing.due, existing.repeat)}"
                        else -> "Leave empty for no set time"
                    }, fontSize = 12.sp, color = AuroraTeal,
                )
                Text("Repeat", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(null to "Once", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly").forEach { (k, label) ->
                        if (repeat == k) Button(onClick = { repeat = k }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontSize = 12.sp) }
                        else OutlinedButton(onClick = { repeat = k }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val keepWeekly = existing?.repeat?.takeIf { it.startsWith("weekly") && repeat == "weekly" && whenText.isBlank() }
                if (vm.saveReminder(existing?.id, text, whenText, keepWeekly ?: repeat)) onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
