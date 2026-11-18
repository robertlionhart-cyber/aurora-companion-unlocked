@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aurora.companion.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aurora.companion.android.AuroraViewModel
import com.aurora.companion.android.ChatMessage
import com.aurora.companion.android.CompanionProfile
import com.aurora.companion.android.MIC_AUTO
import com.aurora.companion.android.MIC_HOLD
import com.aurora.companion.android.UiState
import com.aurora.companion.android.VoiceCatalog
import com.aurora.companion.android.toNiceSize

/** Font size in sp that ignores the system font scale (for icon glyphs that must fit fixed-size buttons). */
@Composable
fun fixedSp(v: Float): TextUnit = with(LocalDensity.current) { v.dp.toSp() }

private val Glass = Color(0xA6000000)        // translucent black for bubbles / input
private val GlassUser = Color(0xB33A2F7A)    // translucent violet for the user's bubbles

/** Full-screen screens opened from the chat (they bring their own background and keep clear of the system bars). */
@Composable
private fun FullScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) { content() }
}

/**
 * 0.8.1 chat screen. Phone: the companion fills the screen (animated clip or still), messages float over
 * the bottom part in small translucent bubbles, and a slim input sits at the bottom with the call button
 * inside its right end. Tablet (two-pane): the companion fills the left panel with the controls, chat on the right.
 */
@Composable
fun ChatScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing) {
    var showSettings by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showReminders by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    // Android 13+: ask for notifications the first time a reminder is set.
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.notifPermissionAsked() }
    LaunchedEffect(ui.askNotifPermission) {
        if (ui.askNotifPermission) {
            if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.notifPermissionAsked()
        }
    }
    if (showPicker) { FullScreen { CompanionPicker(ui, vm, sizing, firstRun = false, onClose = { showPicker = false }) }; return }
    if (showReminders) { FullScreen { RemindersScreen(ui, vm, sizing, onClose = { showReminders = false }) }; return }
    // 1.0: chat can ask to open the Library (e.g. "read me <a book you don't have>" opens Find a book).
    LaunchedEffect(ui.libraryOpen?.id) { if (ui.libraryOpen != null) { showSettings = false; showLibrary = true } }
    if (showLibrary) { FullScreen { LibraryScreen(ui, vm, sizing, onClose = { showLibrary = false }) }; return }
    if (showHistory) { FullScreen { HistoryScreen(ui, vm, sizing, onClose = { showHistory = false }) }; return }
    if (showMemory) { FullScreen { MemoryScreen(ui, vm, sizing, onClose = { showMemory = false }) }; return }
    if (showSettings) {
        FullScreen {
            SettingsScreen(ui, vm, sizing, onClose = { showSettings = false },
                onPickCompanion = { showSettings = false; showPicker = true },
                onReminders = { showSettings = false; showReminders = true },
                onLibrary = { showSettings = false; showLibrary = true },
                onHistory = { showSettings = false; showHistory = true },
                onMemory = { showSettings = false; showMemory = true })
        }
        return
    }
    val comp = ui.companion
    val avState = rememberAvatarState(ui)
    val cctx = LocalContext.current
    val callPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startCall() }
    val startCall: () -> Unit = {
        if (ContextCompat.checkSelfPermission(cctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startCall()
        else callPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    // 0.8.2: the call runs right here on the chat screen; Back ends it.
    androidx.activity.compose.BackHandler(enabled = ui.inCall) { vm.endCall() }

    if (sizing.twoPane) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            // Left: the companion fills the panel, controls float at the bottom.
            Box(Modifier.weight(0.42f).fillMaxHeight().clipToBounds()) {
                CompanionBackdrop(comp, avState, ui.animateAvatar, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(comp.name, style = TextStyle(brush = AuroraBrush, fontSize = 30.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clickable { showPicker = true })
                    Text(comp.tagline, fontSize = 13.sp, color = Color(0xCCFFFFFF))
                    ModeChip(ui, vm, comp)
                    if (!ui.voiceReady) Text(ui.voiceStatus, fontSize = 12.sp, color = Color(0xAAFFFFFF))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.inCall) Button(onClick = vm::endCall, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White)) { Text("End call") }
                        else Button(onClick = startCall, enabled = ui.micReady && ui.voiceReady) { Text("\uD83D\uDCDE Call ${comp.name}") }
                        GlassButton(if (ui.autoSpeak) "\uD83D\uDD0A Voice on" else "\uD83D\uDD07 Voice off", enabled = ui.voiceReady, onClick = vm::toggleAutoSpeak)
                        GlassButton(if (ui.online) "\uD83C\uDF10 Online" else "\uD83C\uDF10 Offline") { vm.setOnline(!ui.online) }
                        GlassButton("Change companion") { showPicker = true }
                        GlassButton("\u23F0 Reminders" + ui.reminders.count { it.active }.let { if (it > 0) " ($it)" else "" }) { showReminders = true }
                        GlassButton("\uD83D\uDCDA Library") { showLibrary = true }
                        GlassButton("\uD83D\uDD58 History") { showHistory = true }
                        GlassButton("\uD83E\uDDE0 Memory") { showMemory = true }
                        GlassButton("New chat", onClick = vm::newChat)
                        GlassButton("\u2699 Settings") { showSettings = true }
                    }
                }
            }
            Box(Modifier.weight(0.58f).fillMaxHeight().background(Color(0xFF07080A)).safeDrawingPadding()) {
                ChatColumn(ui, vm, sizing, comp, overlay = false, startCall = startCall, modifier = Modifier.fillMaxSize(), screenHeight = null)
            }
        }
        return
    }

    // Phone: full-screen companion with the chat over its lower part.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenH = maxHeight
        CompanionBackdrop(comp, avState, ui.animateAvatar, Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.22f).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x8C000000), 1f to Color(0xE6000000))))
        // 0.9.8: the top bar is drawn as an overlay (above a tap-outside scrim when its quick menu is open);
        // the chat column reserves the same height under it.
        val density = LocalDensity.current
        var topBarH by remember { mutableStateOf(0.dp) }
        var menuOpen by remember { mutableStateOf(false) }
        androidx.activity.compose.BackHandler(enabled = menuOpen) { menuOpen = false }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Spacer(Modifier.height(topBarH))
            ChatColumn(ui, vm, sizing, comp, overlay = true, startCall = startCall, modifier = Modifier.weight(1f).fillMaxWidth(), screenHeight = screenH)
        }
        if (menuOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { menuOpen = false } })
        }
        Box(Modifier.fillMaxWidth().safeDrawingPadding().onSizeChanged { topBarH = with(density) { it.height.toDp() } }) {
            TopBar(ui, vm, comp, menuOpen = menuOpen, onMenu = { menuOpen = it },
                onPicker = { showPicker = true }, onSettings = { showSettings = true }, onHistory = { showHistory = true })
        }
    }
}

@Composable
private fun GlassButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Glass, contentColor = Color.White),
        border = BorderStroke(1.dp, Color(0x40FFFFFF))) { Text(label, maxLines = 1) }
}

@Composable
private fun IconGlyph(glyph: String, size: Dp = 40.dp, glyphSize: Float = 20f, bg: Color = Color.Transparent, enabled: Boolean = true,
                      modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = fixedSp(glyphSize), color = if (enabled) Color.White else Color(0x66FFFFFF)) }
}

/**
 * 0.9.8 top bar: name (tap = change companion) + mode chip on the left; on the right only a person-silhouette button and
 * Settings. The silhouette opens a quick toolbar that slides out to the left (companions, history, new chat, voice,
 * online). It closes on a second tap, a tap outside, Back, or after picking an item.
 */
@Composable
private fun TopBar(ui: UiState, vm: AuroraViewModel, comp: CompanionProfile, menuOpen: Boolean, onMenu: (Boolean) -> Unit,
                   onPicker: () -> Unit, onSettings: () -> Unit, onHistory: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(comp.name, style = TextStyle(brush = AuroraBrush, fontSize = fixedSp(24f), fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onPicker))
                ModeChip(ui, vm, comp)
                if (!ui.voiceReady || ui.switchingVoice) Text(ui.voiceStatus, fontSize = fixedSp(11f), color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            // Small silhouette = quick menu. Solid glass circle with an outline (teal while open).
            IconGlyph("\uD83D\uDC64", size = 42.dp, glyphSize = 20f, bg = if (menuOpen) Color(0xCC1FB5A8) else Glass,
                modifier = Modifier.border(1.dp, if (menuOpen) AuroraTeal else Color(0x40FFFFFF), CircleShape),
                onClick = { onMenu(!menuOpen) })
            Spacer(Modifier.width(4.dp))
            IconGlyph("\u2699", size = 42.dp, glyphSize = 22f, onClick = { onMenu(false); onSettings() })
        }
        // The toolbar overlays the name area while open (end offset = gear + silhouette + gaps + padding).
        AnimatedVisibility(
            visible = menuOpen,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp + 42.dp + 4.dp + 42.dp + 6.dp, top = 2.dp, start = 8.dp),
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xD9000000))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(26.dp))
                    .pointerInput(Unit) { detectTapGestures { } }   // taps on the gaps don't fall through to the name below
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pick: (() -> Unit) -> Unit = { action -> onMenu(false); action() }
                ToolItem("\uD83D\uDC65", "People") { pick(onPicker) }
                ToolItem("\uD83D\uDD58", "History") { pick(onHistory) }
                ToolItem("\u270F\uFE0F", "New chat") { pick(vm::newChat) }
                ToolItem(if (ui.autoSpeak) "\uD83D\uDD0A" else "\uD83D\uDD07", if (ui.autoSpeak) "Voice on" else "Voice off",
                    enabled = ui.voiceReady) { pick(vm::toggleAutoSpeak) }
                ToolItem("\uD83C\uDF10", if (ui.online) "Online" else "Offline", active = ui.online) { pick { vm.setOnline(!ui.online) } }
            }
        }
    }
}

/** One quick-toolbar item: a glass circle with a small caption (fixed sizes, so large system fonts still fit). */
@Composable
private fun ToolItem(glyph: String, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(54.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (active) Color(0xCC1FB5A8) else Glass)
                .border(1.dp, if (active) AuroraTeal else Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = fixedSp(19f), color = if (enabled) Color.White else Color(0x66FFFFFF)) }
        Text(label, fontSize = fixedSp(10f), color = if (enabled) Color.White else Color(0x88FFFFFF), maxLines = 1,
            overflow = TextOverflow.Clip, modifier = Modifier.padding(top = 1.dp))
    }
}

/** 0.9.3: compact mode chip under the name; opens the list of modes (18+ ones only while adult mode is on). */
@Composable
private fun ModeChip(ui: UiState, vm: AuroraViewModel, comp: CompanionProfile) {
    var open by remember { mutableStateOf(false) }
    val modes = com.aurora.companion.android.Modes.available(ui.adultMode, comp)
    val cur = if (ui.mode in modes) ui.mode else com.aurora.companion.android.Modes.DEFAULT
    Box {
        Row(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(14.dp)).background(Glass)
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(14.dp)).clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${cur.emoji} ${cur.label} \u25BE", fontSize = fixedSp(13f), color = Color.White, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            modes.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${m.emoji}  ${m.label}", fontSize = fixedSp(15f), fontWeight = if (m == cur) FontWeight.Bold else FontWeight.Normal)
                            Text(m.hint, fontSize = fixedSp(12f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = if (m == cur) ({ Text("\u2713", color = AuroraTeal, fontSize = fixedSp(16f)) }) else null,
                    onClick = { open = false; vm.setMode(m) },
                )
            }
        }
    }
}

/** Messages + listening banner + slim input. [overlay] = floating over the avatar (phone). */
@Composable
private fun ChatColumn(ui: UiState, vm: AuroraViewModel, sizing: Sizing, comp: CompanionProfile, overlay: Boolean,
                       startCall: () -> Unit, modifier: Modifier, screenHeight: Dp?) {
    val speakFn: (String) -> Unit = { if (ui.voiceReady) vm.speakMessage(it) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Typed chat: the message area never reaches above the middle of the screen (the face stays visible).
        val density = LocalDensity.current
        var areaBottomPx by remember { mutableIntStateOf(0) }
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { areaBottomPx = (it.positionInWindow().y + it.size.height).toInt() },
            contentAlignment = Alignment.BottomCenter) {
            if (ui.inCall) {
                // In a call the transcript is hidden (it is still recorded in the chat log and history).
                CallStatus(ui, comp)
                return@Box
            }
            val listState = rememberLazyListState()
            val last = ui.messages.lastOrNull()
            LaunchedEffect(ui.messages.size, last?.text?.length) {
                if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.size - 1, Int.MAX_VALUE / 2)
            }
            // Phone: only the bottom ~40% of the screen holds messages (scroll up for history); the top edge fades out.
            val capDp = if (screenHeight == null || areaBottomPx == 0) null else with(density) {
                (areaBottomPx.toDp() - screenHeight / 2).coerceAtLeast(120.dp)
            }
            val listMod = if (overlay) Modifier.fillMaxWidth().then(if (capDp != null) Modifier.heightIn(max = capDp) else Modifier.fillMaxHeight(0.5f))
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black), blendMode = BlendMode.DstIn)
                }
            else Modifier.fillMaxSize()
            LazyColumn(
                state = listState,
                modifier = listMod.widthIn(max = 860.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (overlay) 36.dp else 12.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                items(ui.messages, key = { it.id }) { m ->
                    Bubble(m, sizing, thinking = ui.generating && m === last && m.text.isEmpty(), name = comp.name, onSpeak = speakFn)
                }
            }
        }
        if (ui.speaking && !ui.inCall) {
            Pill("\u25A0  Stop speaking", Glass, AuroraTeal, onClick = vm::stopSpeaking)
        }
        val busyNote = ui.preparing ?: if (ui.memoryBusy && !ui.inCall) "\uD83E\uDDE0 ${comp.name} is jotting down memories..." else null
        if (busyNote != null) {
            Pill(busyNote, Glass, Color(0xFFCFD8DC), onClick = {})
        }
        if ((ui.listening || ui.transcribing) && !ui.inCall) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).widthIn(max = 860.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(if (ui.listening) AuroraPink.copy(alpha = 0.9f) else Glass)
                    .clickable(enabled = ui.listening) { vm.stopListening() }.padding(vertical = 8.dp, horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when {
                        ui.transcribing -> "Turning your voice into text..."
                        ui.micMode == MIC_AUTO && !ui.heardSpeech -> "\uD83D\uDD34 Listening... start talking"
                        ui.micMode == MIC_AUTO -> "\uD83D\uDD34 Listening... tap here to send now"
                        ui.listenLatched -> "\uD83D\uDD34 Listening... tap here or the mic to send"
                        else -> "\uD83D\uDD34 Listening... let go to send"
                    },
                    color = if (ui.listening) Color.Black else AuroraTeal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                )
                if (ui.listening && ui.micMode == MIC_AUTO) {
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.8f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33000000))) {
                        Box(Modifier.fillMaxWidth(ui.micLevel.coerceIn(0.02f, 1f)).fillMaxHeight().background(Color.Black))
                    }
                }
            }
        }
        // 1.0: the book player sits above the message bar while a book is open (the face stays visible above it).
        val reading = ui.reading
        if (reading != null && !ui.inCall) ReadAloudPlayer(reading, compact = true, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        // 0.9.8: the speaker toggle moved into the top-bar quick menu (silhouette button).
        if (ui.inCall) CallBar(ui, comp, onMute = vm::toggleMute, onEnd = vm::endCall)
        else InputBar(ui, onSend = vm::send, onStop = vm::stop, onCall = startCall,
            onMicDown = vm::startListening, onMicUp = vm::stopListening, onLatch = vm::latchListening, onAutoStart = vm::startAutoListening)
    }
}

/** Minimal in-call status: a small pill with the state and the mic level. */
@Composable
private fun CallStatus(ui: UiState, comp: CompanionProfile) {
    val status = when {
        ui.callMuted -> "Muted"
        ui.speaking -> "Speaking..."
        ui.transcribing || ui.generating -> "Thinking..."
        ui.callHearing -> "Hearing you..."
        else -> "Listening..."
    }
    val color = when {
        ui.callMuted -> Color(0xFFBDBDBD)
        ui.speaking -> AuroraViolet
        ui.transcribing || ui.generating -> AuroraTeal.copy(alpha = 0.75f)
        else -> AuroraTeal
    }
    Column(
        Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(20.dp)).background(Glass).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("\uD83D\uDCDE  $status", color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.padding(top = 5.dp).width(140.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(if (ui.callMuted) 0.01f else ui.callLevel.coerceIn(0.01f, 1f)).fillMaxHeight().background(color))
        }
        if (!ui.bargeIn) Text("Barge-in is off: let ${comp.name} finish first.", fontSize = 11.sp, color = Color(0xCCFFFFFF))
    }
}

/** The message box during a call: Mute at the left, who you're talking to, and the red phone to end. */
@Composable
private fun CallBar(ui: UiState, comp: CompanionProfile, onMute: () -> Unit, onEnd: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 860.dp).fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)).background(Color(0xB3000000)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconGlyph(if (ui.callMuted) "\uD83D\uDD07" else "\uD83C\uDFA4", size = 38.dp, glyphSize = 18f,
            bg = if (ui.callMuted) Color(0xCCFFFFFF) else Color(0x22FFFFFF), onClick = onMute)
        Text(if (ui.callMuted) "Muted - tap the mic to unmute" else "On a call with ${comp.name}", color = Color(0xCCFFFFFF), fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 9.dp))
        IconGlyph("\uD83D\uDCDE", size = 38.dp, glyphSize = 18f, bg = Color(0xFFE53935), onClick = onEnd)
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Small translucent chat bubble. Tap a companion bubble to hear it again. */
@Composable
fun Bubble(m: ChatMessage, sizing: Sizing, thinking: Boolean, name: String, onSpeak: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.isUser) Arrangement.End else Arrangement.Start) {
        Box(Modifier.fillMaxWidth(if (sizing.wide) 0.75f else 0.84f).wrapContentWidth(if (m.isUser) Alignment.End else Alignment.Start)) {
            Text(
                if (thinking) "$name is thinking..." else m.text,
                fontSize = (sizing.body - 1).sp,
                lineHeight = ((sizing.body - 1) * 1.3f).sp,
                color = if (thinking) Color(0xCCFFFFFF) else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (m.isUser) GlassUser else Glass)
                    .clickable(enabled = !m.isUser && m.text.isNotBlank()) { onSpeak(m.text) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Slim input: small mic at the left (hands-free tap, or hold-to-talk, per Settings), a 1-3 line text box,
 * and at the right end the call button, which turns into a send arrow while there is text (Stop while replying).
 */
@Composable
fun InputBar(
    ui: UiState,
    onSend: (String) -> Unit, onStop: () -> Unit, onCall: () -> Unit,
    onMicDown: () -> Unit, onMicUp: () -> Unit, onLatch: () -> Unit, onAutoStart: () -> Unit,
) {
    val generating = ui.generating
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasMic = granted }
    val micEnabled = ui.micReady && !generating && !ui.transcribing
    val currentUi by rememberUpdatedState(ui)
    val send = { if (!generating && text.isNotBlank()) { onSend(text); text = "" } }

    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 860.dp).fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)).background(Color(0xB3000000)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mic: hands-free = tap once (sends when you pause); hold-to-talk = hold, or quick tap to latch.
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(if (ui.listening) AuroraPink else Color.Transparent)
                .pointerInput(micEnabled, hasMic) {
                    detectTapGestures(onPress = {
                        if (!micEnabled) return@detectTapGestures
                        if (!hasMic) { permLauncher.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                        if (currentUi.listening) { onMicUp(); return@detectTapGestures }
                        if (currentUi.micMode == MIC_AUTO) { onAutoStart(); return@detectTapGestures }
                        val t0 = System.currentTimeMillis()
                        onMicDown()
                        val released = tryAwaitRelease()
                        val held = System.currentTimeMillis() - t0
                        if (released && held < 400 && currentUi.listening) onLatch() else onMicUp()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ui.transcribing) "\u2026" else if (ui.listening) "\u25A0" else "\uD83C\uDFA4", fontSize = fixedSp(18f),
                color = if (micEnabled) Color.White else Color(0x66FFFFFF))
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("Message ${ui.companion.name}...", color = Color(0x99FFFFFF), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(AuroraTeal),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            generating -> IconGlyph("\u25A0", size = 38.dp, glyphSize = 16f, bg = AuroraPink, onClick = onStop)
            text.isNotBlank() -> IconGlyph("\u2191", size = 38.dp, glyphSize = 20f, bg = AuroraTeal, onClick = { send() })
            else -> IconGlyph("\uD83D\uDCDE", size = 38.dp, glyphSize = 18f, bg = Color(0x3329C38F), enabled = ui.micReady && ui.voiceReady, onClick = onCall)
        }
    }
}

// ---------------------------------------------------------------- Settings (full screen) ----------------------------------------------------------------

@Composable
private fun SectionTitle(t: String) = Text(t, fontWeight = FontWeight.Bold, color = AuroraTeal, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Text on the left (always gets the space), a small control on the right. */
@Composable
private fun SettingRow(title: String, hint: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (hint != null) Hint(hint)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Chevron() = Text("\u203A", fontSize = fixedSp(26f), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SettingsScreen(ui: UiState, vm: AuroraViewModel, sizing: Sizing, onClose: () -> Unit, onPickCompanion: () -> Unit,
                   onReminders: () -> Unit, onLibrary: () -> Unit, onHistory: () -> Unit, onMemory: () -> Unit) {
    // 0.9.3: a short main list; details live on sub-pages.
    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler { if (page == "avatars") page = "companion" else if (page != null) page = null else onClose() }
    var askAdult by remember { mutableStateOf(false) }
    if (askAdult) AdultModeDialog(onConfirm = { askAdult = false; vm.setAdultMode(true) }, onDismiss = { askAdult = false })
    val title = when (page) {
        "companion" -> "Companion"; "avatars" -> "Avatars"; "voice" -> "Voice & calls"; "chat" -> "Chat & memory"; "model" -> "AI model"
        "privacy" -> "Privacy & online"; "adult" -> "18+"; "updates" -> "Updates"; "about" -> "About"; else -> "Settings"
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = if (page != null) 0.dp else sizing.pad, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != null) TextButton(onClick = { page = if (page == "avatars") "companion" else null }) { Text("\u2039", fontSize = fixedSp(26f)) }
                Text(title, fontSize = (sizing.body + 7).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onClose) { Text("Done") }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = sizing.pad),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (page) {
                    null -> SettingsMain(ui, onPickCompanion, onReminders, onLibrary) { page = it }
                    "companion" -> CompanionPage(ui, vm) { page = "avatars" }
                    "avatars" -> AvatarsPage(ui, vm)
                    "voice" -> VoicePage(ui, vm)
                    "chat" -> {
                        SettingRow("\uD83D\uDD58 History", "Past chats and calls", onClick = onHistory) { Chevron() }
                        SettingRow("\uD83E\uDDE0 Memory", if (ui.memoryOn) "What ${ui.companion.name} remembers" else "Off", onClick = onMemory) { Chevron() }
                        SettingRow("Knowledge from books", "Adds short book passages to answers") {
                            Switch(checked = ui.bookKnowledge, onCheckedChange = { vm.setBookKnowledge(it) })
                        }
                    }
                    "model" -> {
                        Text(com.aurora.companion.android.ModelCatalog.byFileName(ui.loadedModelName)?.title ?: ui.loadedModelName ?: "-",
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        com.aurora.companion.android.ModelCatalog.byFileName(ui.loadedModelName)?.techName?.takeIf { it.isNotEmpty() }?.let { Hint(it) }
                        Hint("This phone: %.1f GB RAM".format(ui.ramGb))
                        Button(onClick = { onClose(); vm.backToSetup() }) { Text("Change AI model") }
                    }
                    "privacy" -> PrivacyPage(ui, vm)
                    "adult" -> {
                        SettingRow("\uD83D\uDD1E Adult mode", if (ui.adultMode) "On" else "Off (family-friendly)") {
                            Switch(checked = ui.adultMode, onCheckedChange = { if (it) askAdult = true else vm.setAdultMode(false) })
                        }
                        Hint("For adults only. Unlocks adult-marked characters (and Romantic mode for them), Unhinged mode, the adult book and the uncensored models. Asks you to confirm your age.")
                    }
                    "updates" -> UpdatesSection(ui, vm)
                    "about" -> AboutPage()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One row of the main Settings list: icon, short label, one short hint, chevron. */
@Composable
private fun NavRow(icon: String, title: String, hint: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = fixedSp(20f), modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}

@Composable
private fun SettingsMain(ui: UiState, onPickCompanion: () -> Unit, onReminders: () -> Unit, onLibrary: () -> Unit, go: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable(onClick = onPickCompanion).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CompanionAvatar(ui.companion, "idle", 48.dp, animate = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.companion.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Change / create \u203A", fontSize = 13.sp, color = AuroraTeal)
        }
    }
    NavRow("\uD83D\uDE0A", "Companion", "Mode: ${ui.mode.label}", onClick = { go("companion") })
    NavRow("\uD83C\uDFA4", "Voice & calls", VoiceCatalog.byId(ui.voiceId).title, onClick = { go("voice") })
    NavRow("\uD83D\uDCAC", "Chat & memory", "History, memory, books", onClick = { go("chat") })
    NavRow("\uD83E\uDDE9", "AI model", com.aurora.companion.android.ModelCatalog.byFileName(ui.loadedModelName)?.title ?: ui.loadedModelName, onClick = { go("model") })
    NavRow("\uD83D\uDCDA", "Library", if (ui.library.indexedBooks > 0) "${ui.library.indexedBooks} books" else "Free books", onClick = onLibrary)
    val n = ui.reminders.count { it.active }
    NavRow("\u23F0", "Reminders", if (n > 0) "$n open" else null, onClick = onReminders)
    NavRow("\uD83D\uDD12", "Privacy & online", if (ui.online) "Online" else "Offline", onClick = { go("privacy") })
    if (com.aurora.companion.android.Edition.UNLOCKED)
        NavRow("\uD83D\uDD1E", "18+", if (ui.adultMode) "On" else "Off", onClick = { go("adult") })
    NavRow("\u2B07\uFE0F", "Updates", if (ui.update.info != null) "Update available" else null, onClick = { go("updates") })
    NavRow("\u2139\uFE0F", "About", null, onClick = { go("about") })
}

@Composable
private fun CompanionPage(ui: UiState, vm: AuroraViewModel, onAvatars: () -> Unit) {
    val modes = com.aurora.companion.android.Modes.available(ui.adultMode, ui.companion)
    SectionTitle("${ui.companion.name}'s mode")
    modes.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.setMode(m) }) {
            RadioButton(selected = ui.mode == m, onClick = { vm.setMode(m) })
            Column(Modifier.weight(1f)) { Text("${m.emoji} ${m.label}", fontSize = 15.sp); Hint(m.hint) }
        }
    }
    Hint("Also the chip under the name in the chat.")
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SectionTitle("Default mode")
    Hint("For companions you haven't set a mode for.")
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("${ui.defaultMode.emoji} ${ui.defaultMode.label} \u25BE") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.aurora.companion.android.Modes.base.forEach { m ->
                DropdownMenuItem(text = { Text("${m.emoji}  ${m.label}") }, onClick = { open = false; vm.setDefaultMode(m) })
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SettingRow("Animated avatar", "Off saves a little battery") {
        Switch(checked = ui.animateAvatar, onCheckedChange = { vm.setAnimateAvatar(it) })
    }
    val n = ui.packs.installed.size
    SettingRow("\uD83C\uDFAD Avatars", if (n > 0) "$n pack${if (n == 1) "" else "s"} installed" else "Extra animations and new looks", onClick = onAvatars) { Chevron() }
}

/** 0.9.5: Settings > Companion > Avatars: download / delete everyday avatar packs, with a preview. */
@Composable
private fun AvatarsPage(ui: UiState, vm: AuroraViewModel) {
    LaunchedEffect(Unit) { vm.importPackInbox() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importPackFile(uri) }
    Hint("Extra animations for the companions, and new looks (companions you can chat with or use for your own characters)" +
        (if (com.aurora.companion.android.Edition.UNLOCKED) ". Everyday clips, plus a few beach (swimwear) clips for some adult looks." else ". Everyday clips only.") +
        " Small downloads; Wi-Fi recommended.")
    ui.packs.status?.let { Text(it, fontSize = 12.sp, color = AuroraTeal) }
    val (builtIn, looks) = com.aurora.companion.android.AvatarPacks.catalog.partition { it.builtIn }
    SectionTitle("Extra animations")
    val importFile = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
    builtIn.forEach { PackRow(it, ui, vm, importFile) }
    if (looks.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SectionTitle("New looks")
        Hint("Use one for your own character: Change / create > Create (or Edit) > Look.")
        looks.forEach { PackRow(it, ui, vm, importFile) }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
        enabled = ui.packs.downloading == null) { Text("Import pack from file") }
    Hint("Only Aurora's own avatar packs are accepted (each file's checksum is checked).")
}

@Composable
private fun PackRow(info: com.aurora.companion.android.AvatarPacks.PackInfo, ui: UiState, vm: AuroraViewModel, onImport: () -> Unit) {
    val packs = ui.packs
    val installed = info.id in packs.installed
    var preview by remember(info.id) { mutableStateOf(false) }
    val profile: CompanionProfile? = remember(info.id, packs.revision) {
        if (info.builtIn) com.aurora.companion.android.Companions.byId(info.extends)
        else com.aurora.companion.android.AvatarPacks.manifest(info.id)?.let { com.aurora.companion.android.AvatarPacks.lookProfile(it) }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable(enabled = installed) { preview = !preview }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (profile != null) CompanionAvatar(profile, "idle", 52.dp, animate = false)
            else Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF22252E)), contentAlignment = Alignment.Center) {
                Text(info.name.take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Hint("${info.clips} clips \u00B7 ${info.size.toNiceSize()}" + (if (info.swimwear) " \u00B7 incl. swimwear" else "") + if (installed) " \u00B7 installed (tap to preview)" else "")
            }
            when {
                packs.downloading == info.id -> TextButton(onClick = vm::cancelPackDownload) { Text("Cancel") }
                installed && com.aurora.companion.android.AvatarPacks.outdated(info.id) ->   // 0.9.9: newer clips available
                    Button(onClick = { vm.downloadPack(info.id) }, enabled = packs.downloading == null) { Text("Update") }
                installed -> TextButton(onClick = { preview = false; vm.deletePack(info.id) }) { Text("Delete") }
                // 0.9.9: packs that aren't hosted online (Horizon) come only from a file you already have.
                !info.hosted -> OutlinedButton(onClick = onImport, enabled = packs.downloading == null) { Text("Import from file") }
                else -> Button(onClick = { vm.downloadPack(info.id) }, enabled = packs.downloading == null) { Text("Download") }
            }
        }
        if (packs.downloading == info.id) {
            val p = packs.progress
            if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (preview && installed && profile != null) {
            val clips = remember(info.id, packs.revision) { com.aurora.companion.android.AvatarPacks.manifest(info.id)?.clips?.keys?.toList().orEmpty() }
            var i by remember(info.id) { mutableStateOf(0) }
            LaunchedEffect(clips) { while (clips.isNotEmpty()) { kotlinx.coroutines.delay(4_000); i = (i + 1) % clips.size } }
            val state = clips.getOrNull(i) ?: "idle"
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompanionAvatar(profile, state, 132.dp, animate = true, call = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.replace('_', ' '), fontSize = 14.sp, color = AuroraTeal)
                    Hint(clips.joinToString(", ") { it.replace('_', ' ') })
                }
            }
        }
    }
}

@Composable
private fun VoicePage(ui: UiState, vm: AuroraViewModel) {
    var agreed by remember { mutableStateOf(false) }
    SectionTitle("${ui.companion.name}'s voice")
    Hint("Each companion remembers its own voice. Robert's voice is the default.")
    VoiceCatalog.all.forEach { v ->
        val installed = v.id in ui.installedVoices
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(enabled = installed && !ui.switchingVoice) { vm.selectVoice(v.id) }) {
            RadioButton(selected = ui.voiceId == v.id, enabled = installed && !ui.switchingVoice, onClick = { vm.selectVoice(v.id) })
            Column(Modifier.weight(1f)) {
                Text(v.title, fontSize = 15.sp)
                Hint(if (v.builtIn) "Built in" else if (installed) "Downloaded (${v.modelBytes.toNiceSize()})" else "Optional download: ${v.modelBytes.toNiceSize()}")
            }
            if (!v.builtIn && installed) TextButton(onClick = { vm.deleteVoice(v.id) }) { Text("Delete") }
        }
        if (!v.builtIn && !installed) {
            Hint(v.note + " Downloaded from Hugging Face (piper-voices v1.0.0, checksum-verified). " +
                "We don't own the rights to third-party voices; downloading is optional and at your own discretion. Wi-Fi recommended.")
            val p = ui.voiceDownload
            if (p != null) {
                LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(p * 100).toInt()}%  - keep this open", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::cancelVoiceDownload) { Text("Cancel") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { agreed = !agreed }) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text("I understand", fontSize = 13.sp)
                }
                Button(onClick = { vm.downloadVoice(v.id) }, enabled = agreed) { Text("Download ${v.modelBytes.toNiceSize()}") }
            }
        }
    }
    if (ui.voiceId == VoiceCatalog.LIBRITTS.id && ui.numSpeakers > 1) {
        Text("LibriTTS speaker: #${ui.speakerId}", fontWeight = FontWeight.SemiBold)
        var pos by remember(ui.speakerId) { mutableStateOf(ui.speakerId.toFloat()) }
        Slider(value = pos, onValueChange = { pos = it }, onValueChangeFinished = { vm.setSpeaker(pos.toInt()) },
            valueRange = 0f..(ui.numSpeakers - 1).toFloat())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.setSpeaker((ui.speakerId - 1).coerceAtLeast(0)); vm.testVoice() }) { Text("\u25C0") }
            OutlinedButton(onClick = { vm.setSpeaker((ui.speakerId + 1).coerceAtMost(ui.numSpeakers - 1)); vm.testVoice() }) { Text("\u25B6") }
            OutlinedButton(onClick = { vm.setSpeaker((0 until ui.numSpeakers).random()); vm.testVoice() }) { Text("Random") }
        }
    }
    if (ui.switchingVoice) Text("Loading voice...", fontSize = 12.sp, color = AuroraTeal)
    SettingRow("Speak replies", "Read replies out loud") {
        Switch(checked = ui.autoSpeak, enabled = ui.voiceReady, onCheckedChange = { vm.toggleAutoSpeak() })
    }
    OutlinedButton(onClick = vm::testVoice, enabled = ui.voiceReady && !ui.switchingVoice) { Text("\u25B6 Test voice") }
    Hint(ui.voiceStatus)

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SectionTitle("Microphone")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.setMicMode(MIC_AUTO) }) {
        RadioButton(selected = ui.micMode == MIC_AUTO, onClick = { vm.setMicMode(MIC_AUTO) })
        Column(Modifier.weight(1f)) { Text("Hands-free (tap once)", fontSize = 15.sp); Hint("Stops by itself when you stop talking, then sends.") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.setMicMode(MIC_HOLD) }) {
        RadioButton(selected = ui.micMode == MIC_HOLD, onClick = { vm.setMicMode(MIC_HOLD) })
        Column(Modifier.weight(1f)) { Text("Hold to talk", fontSize = 15.sp); Hint("Hold the mic while talking and let go to send (quick tap = tap to start/stop).") }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SectionTitle("Calls")
    SettingRow("Barge-in", "Talk over ${ui.companion.name} to interrupt.") {
        Switch(checked = ui.bargeIn, onCheckedChange = { vm.setBargeIn(it) })
    }
    if (ui.bargeIn) {
        Text("Barge-in sensitivity", fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("low" to "Low", "medium" to "Medium", "high" to "High").forEach { (k, label) ->
                if (ui.bargeSensitivity == k) Button(onClick = { vm.setBargeSensitivity(k) }) { Text(label) }
                else OutlinedButton(onClick = { vm.setBargeSensitivity(k) }) { Text(label) }
            }
        }
        Hint("Low = fewer false interruptions on loudspeaker. High = interrupts more easily (best with headphones).")
    }
    Text("Tip: headphones give the best calls, because ${ui.companion.name} can't hear their own voice.", fontSize = 12.sp, color = AuroraTeal)
}

@Composable
private fun PrivacyPage(ui: UiState, vm: AuroraViewModel) {
    val ctx = LocalContext.current
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.setUseLocation(ok) }
    SettingRow("\uD83C\uDF10 Online", if (ui.online) "On: weather, news and look-ups" else "Off: nothing leaves this phone") {
        Switch(checked = ui.online, onCheckedChange = { vm.setOnline(it) })
    }
    Hint("Also in the person-button quick menu at the top of the chat. When Online, only the words of a weather, news or look-up question " +
        "(or your city) are sent to Open-Meteo, BBC News, Google News, Wikipedia or DuckDuckGo. Your chats never are.")
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SectionTitle("Weather city")
    var city by remember(ui.homeCity) { mutableStateOf(ui.homeCity) }
    OutlinedTextField(value = city, onValueChange = { city = it.take(60) }, singleLine = true, label = { Text("City") }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.setHomeCity(city) }, enabled = city.trim() != ui.homeCity) { Text("Save") }
        if (ui.homeCity.isNotEmpty()) OutlinedButton(onClick = { city = ""; vm.setHomeCity("") }) { Text("Clear") }
    }
    SettingRow("Use approximate location", "Only for weather, if no city is saved") {
        Switch(checked = ui.useLocation, onCheckedChange = { on ->
            if (!on) vm.setUseLocation(false)
            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.setUseLocation(true)
            else locPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        })
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Hint("Chats, memory, voices and the AI model stay on this phone. Downloads and update checks use the internet only for the files themselves.")
}

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    Text("Aurora Companion \u00B7 ${com.aurora.companion.android.Edition.NAME}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Hint("Version ${com.aurora.companion.android.Updater.currentVersionName(ctx)}")
    Text("A private AI companion that runs on this phone.", fontSize = 14.sp)
    Hint("Built with llama.cpp and sherpa-onnx (Piper voices). AI models and voices are third-party; each license is shown before download.")
    Hint("github.com/${com.aurora.companion.android.Edition.UPDATE_REPO}")
}

// ---------------------------------------------------------------- full-screen avatar ----------------------------------------------------------------

/**
 * The companion filling [modifier]'s box: the looping state clip (or the still when animation is off),
 * scaled to cover the box (crop), centred horizontally, with the face kept in the upper third.
 */
@Composable
fun CompanionBackdrop(companion: CompanionProfile, state: String, animate: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier.clipToBounds().background(Color.Black)) {
        if (!companion.animated()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10131C), Color(0xFF1B1433), Color.Black))))
            Text("A", style = TextStyle(brush = AuroraBrush, fontSize = fixedSp(maxWidth.value * 0.5f), fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.12f))
            return@BoxWithConstraints
        }
        val bw = maxWidth; val bh = maxHeight
        // Clip size: width w, height w * aspect. Cover the box.
        val fr = companion.framing()
        val scale = maxOf(bw.value, bh.value / fr.aspect)
        val w = scale.dp
        val h = (scale * fr.aspect).dp
        val left = (bw - w) / 2
        val top = (bh * 0.30f - h * fr.faceY).coerceIn(bh - h, 0.dp)
        val frame = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(w, h).offset(x = left, y = top)
        rememberAssetBitmap(companion.still())?.let {
            androidx.compose.foundation.Image(it, contentDescription = companion.name, contentScale = ContentScale.FillBounds, modifier = frame)
        }
        if (animate) AvatarClipPublic(companion.clip(state), frame)
    }
}

/** 0.8.4: the 18+ confirmation (disclaimer + "I am 18 or older" checkbox) shown before adult mode turns on. */
@Composable
fun AdultModeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.aurora.companion.android.AdultMode.TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(com.aurora.companion.android.AdultMode.DISCLAIMER, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sure = !sure }) {
                    Checkbox(checked = sure, onCheckedChange = { sure = it })
                    Text(com.aurora.companion.android.AdultMode.CONFIRM, fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = sure) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** 0.8.6: Settings > Updates (GitHub Releases, started by the user; optional once-a-day check). */
@Composable
private fun UpdatesSection(ui: UiState, vm: AuroraViewModel) {
    val u = ui.update
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = remember { com.aurora.companion.android.Updater.currentVersionName(ctx) }
    SectionTitle("Updates")
    Hint("${com.aurora.companion.android.Edition.NAME} edition, version $current. Updates install over this app and keep your chats, " +
        "characters and settings. The internet is only used when you check or download.")
    SettingRow("Check once a day", "Quietly looks for a new version when the app opens (at most once a day).") {
        Switch(checked = u.autoCheck, onCheckedChange = { vm.setAutoUpdateCheck(it) })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.checkForUpdates(true) }, enabled = !u.checking && !u.downloading) {
            Text(if (u.checking) "Checking..." else "Check for updates")
        }
        val info = u.info
        if (info != null && u.readyPath == null && !u.downloading)
            Button(onClick = vm::downloadUpdate) { Text("Download ${info.versionName} (${info.apk.size.toNiceSize()})") }
        if (u.readyPath != null) Button(onClick = vm::installUpdate) { Text("Install") }
    }
    val p = u.progress
    if (u.downloading) {
        if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (p != null) "${(p * 100).toInt()}%  - Wi-Fi recommended" else "Downloading...", fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::cancelUpdateDownload) { Text("Cancel") }
        }
    }
    u.status?.let { Text(it, fontSize = 12.sp, color = AuroraTeal) }
    if (u.needsPermission) OutlinedButton(onClick = vm::installUpdate) { Text("I allowed it - Install") }
    u.info?.let { info ->
        Text(info.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        val notes = info.notes.lines().filter { it.isNotBlank() }.take(14).joinToString("\n")
        if (notes.isNotBlank()) Text(notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Hint(if (info.apk.sha256 != null) "Checksum published: it is verified after download." else "No checksum published: size and signature are still checked.")
    }
}
