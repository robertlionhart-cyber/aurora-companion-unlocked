package com.aurora.companion.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.ChatMessage
import com.aurora.companion.android.ModelCatalog
import com.aurora.companion.android.ModelOption
import com.aurora.companion.android.Screen
import com.aurora.companion.android.UiState
import com.aurora.companion.android.toNiceSize
import com.aurora.companion.android.VoiceCatalog
import com.aurora.companion.android.MIC_AUTO
import com.aurora.companion.android.MIC_HOLD

/** Phone vs tablet sizing, decided from the actual window width (works in split-screen too). */
data class Sizing(val wide: Boolean, val twoPane: Boolean, val body: Float, val pad: Dp)

@Composable
fun AuroraApp(vm: AuroraViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy = ui.screen is Screen.Busy || ui.generating || ui.listening || ui.speaking || ui.inCall
    val actx = LocalContext.current
    LaunchedEffect(ui.inCall) {
        (actx as? android.app.Activity)?.volumeControlStream =
            if (ui.inCall) android.media.AudioManager.STREAM_VOICE_CALL else android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
    }
    LocalView.current.keepScreenOn = busy

    // Default text colour for every screen: full-screen pages aren't inside a Surface, so without this plain Text would be black on black.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val sizing = Sizing(
            wide = maxWidth >= 600.dp,
            twoPane = maxWidth >= 840.dp,
            body = if (maxWidth >= 600.dp) 17f else 15f,
            pad = if (maxWidth >= 600.dp) 24.dp else 12.dp,
        )
        val firstPick = !ui.companionChosen && ui.screen !is Screen.Busy
        // The chat screen draws the companion edge to edge and handles the system bars / keyboard itself;
        // every other screen is kept inside the safe area.
        val padded = Modifier.fillMaxSize().safeDrawingPadding()
        if (firstPick) Box(padded) { CompanionPicker(ui, vm, sizing, firstRun = true, onClose = {}) }
        else when (val s = ui.screen) {
            is Screen.Setup -> Box(padded) { SetupScreen(ui, vm, sizing) }
            is Screen.Busy -> Box(padded) { BusyScreen(s, sizing, onCancel = vm::cancelWork) }
            is Screen.Chat -> ChatScreen(ui, vm, sizing)   // calls run inline on the chat screen (0.8.2)
        }
        ui.notice?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(3500); vm.dismissNotice() }
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
                Text(msg, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(PanelHi).clickable { vm.dismissNotice() }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
        ui.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                title = { Text("Heads up") },
                text = { Text(msg) },
            )
        }
    }
    }
}

@Composable
fun Brand(sizing: Sizing, subtitle: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Aurora Companion",
            style = TextStyle(brush = AuroraBrush, fontSize = if (sizing.wide) 34.sp else 26.sp, fontWeight = FontWeight.Bold),
        )
        if (subtitle != null) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
fun SetupScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing) {
    // 0.9.0: no inline disclaimer or 18+ switch here. Download -> (18+ model with adult mode off: age confirmation)
    // -> a short download confirmation -> download.
    var askAdult by remember { mutableStateOf(false) }
    var askDownload by remember { mutableStateOf(false) }
    if (askAdult) AdultModeDialog(onConfirm = { askAdult = false; vm.setAdultMode(true); askDownload = true }, onDismiss = { askAdult = false })
    if (askDownload) DownloadDialog(ui.selected, ui.ramGb, onConfirm = { askDownload = false; vm.downloadSelected() }, onDismiss = { askDownload = false })
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGguf(uri)
    }
    // First run: light models only (bigger ones are under Settings > Change AI model once you're chatting).
    val fullList = ui.setupFromSettings || ui.downloaded.isNotEmpty()
    val shown = if (fullList) ui.options else ModelCatalog.firstRun.filter { it in ui.options }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(sizing.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Brand(sizing, "Private AI that runs on this device - Android preview")
            Text("This device: %.1f GB RAM, %s free".format(ui.ramGb, ui.freeStorageBytes.toNiceSize()),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(if (fullList) "Choose an AI model" else "Choose a brain for Aurora", fontWeight = FontWeight.SemiBold, fontSize = (sizing.body + 1).sp)
            if (!fullList) Text("Bigger models can be added later in Settings > Change AI model.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            shown.forEach { opt ->
                ModelCard(opt, selected = opt == ui.selected, downloaded = opt.id in ui.downloaded,
                    recommended = opt == ModelCatalog.recommendedTag(ui.ramGb),
                    onClick = { vm.select(opt) }, onDelete = { vm.deleteModel(opt) })
            }
            // Downloaded files that aren't offered any more (e.g. Qwen2.5 1.5B) can still be deleted.
            val hidden = (ModelCatalog.all + ModelCatalog.retired).filter { it.id in ui.downloaded && it !in shown }
            if (hidden.isNotEmpty()) InfoCard {
                Text("Other downloaded models", fontWeight = FontWeight.SemiBold)
                hidden.forEach { opt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.title, fontSize = 14.sp)
                            if (opt.techName.isNotEmpty()) Text(opt.techName, fontSize = 11.sp, color = Color.Gray)
                            Text("${opt.sizeBytes.toNiceSize()} on device" + (if (opt in ModelCatalog.retired) " - no longer offered" else ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { vm.deleteModel(opt) }) { Text("Delete") }
                    }
                }
            }
            val isDownloaded = ui.selected.id in ui.downloaded
            Button(
                onClick = {
                    when {
                        isDownloaded && ui.selected.adultOnly && !ui.adultMode -> askAdult = true
                        isDownloaded -> vm.downloadSelected()
                        ui.selected.adultOnly && !ui.adultMode -> askAdult = true
                        else -> askDownload = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (isDownloaded) "Start chatting" else "Download ${ui.selected.sizeBytes.toNiceSize()} and start")
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("I already have a .gguf file - import it")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 0.9.0: the third-party download notice, shown only when a model is picked for download. */
@Composable
fun DownloadDialog(opt: ModelOption, ramGb: Double, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // 0.9.2: heavy models on phones under ~7 GB get a one-line warning and "Download anyway".
    val warn = ModelCatalog.isHeavy(opt) && ramGb < ModelCatalog.HEAVY_SAFE_RAM_GB
    val light = ModelCatalog.recommendedTag(ramGb)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download ${opt.title}?") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (warn) Text("Your phone has %.1f GB; this model may be slow or close. ${light.title} is recommended for your phone.".format(ramGb),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(
                "${opt.sizeBytes.toNiceSize()} from Hugging Face (Wi-Fi recommended). It's a third-party model" +
                    (if (opt.techName.isNotEmpty()) " (${opt.techName}, ${opt.license}); " else " (${opt.license}); ") +
                    "Aurora's author doesn't own it. After download, chatting is fully offline - nothing you type leaves this device.",
                fontSize = 14.sp,
            )
          }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (warn) "Download anyway" else "Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ModelCard(opt: ModelOption, selected: Boolean, downloaded: Boolean, recommended: Boolean = false, onClick: () -> Unit, onDelete: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, AuroraBrush) else BorderStroke(1.dp, PanelHi)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(border, RoundedCornerShape(16.dp))
            .background(Panel).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(opt.title, fontWeight = FontWeight.SemiBold)
                if (opt.adultOnly || recommended || ModelCatalog.isHeavy(opt) || opt.tag != null)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (opt.adultOnly) ModelTag("18+")
                    if (recommended) ModelTag("Recommended")
                    if (ModelCatalog.isHeavy(opt)) ModelTag("Powerful phones")
                    opt.tag?.let { ModelTag(it) }
                }
                Text(opt.note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ModelCatalog.isHeavy(opt)) Text(ModelCatalog.POWERFUL_LINE, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (opt.techName.isNotEmpty()) Text(opt.techName, fontSize = 11.sp, color = Color.Gray)
                Text("${opt.sizeBytes.toNiceSize()}  -  ${opt.ram}" + if (downloaded) "  -  on device" else "",
                    fontSize = 12.sp, color = if (downloaded) AuroraTeal else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (downloaded) TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun ModelTag(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AuroraTeal,
        modifier = Modifier.border(1.dp, AuroraTeal, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

@Composable
fun BusyScreen(s: Screen.Busy, sizing: Sizing, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(sizing.pad), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Brand(sizing)
            Text(s.title, fontSize = sizing.body.sp)
            if (s.progress != null) {
                LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                Text("${(s.progress * 100).toInt()}%", color = AuroraTeal)
            } else {
                CircularProgressIndicator()
            }
            if (s.detail.isNotEmpty()) Text(s.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.progress != null) OutlinedButton(onClick = onCancel) { Text("Pause / cancel") }
        }
    }
}

private fun Brush_ring(c: Color) = androidx.compose.ui.graphics.SolidColor(c)

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
