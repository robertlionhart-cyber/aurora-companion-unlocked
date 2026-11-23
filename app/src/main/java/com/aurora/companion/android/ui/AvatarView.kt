package com.aurora.companion.android.ui

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurora.companion.android.CompanionProfile
import com.aurora.companion.android.Companions
import com.aurora.companion.android.UiState

/** Which clip to show for the current app state. */
fun avatarState(ui: UiState, now: Long = System.currentTimeMillis()): String {
    val last = ui.messages.lastOrNull()
    val rd = ui.reading
    // 1.0.2: never the "playful" clip while a book is open (stories never pick it).
    val emo = ui.emotion?.takeUnless { rd != null && it.startsWith("playful") }
    return when {
        // 0.9.5: a short emotion clip (from the reply's [mood] tag or keywords) wins over speaking / idle.
        emo != null && !ui.listening && !ui.callHearing && !ui.transcribing -> emo
        ui.speaking -> Companions.STATE_SPEAKING
        // 1.0.2: bedtime timer ended the story -> asleep (when the look has a sleeping clip, else resting).
        rd != null && rd.sleptByTimer && !rd.playing ->
            if (ui.companion.hasClip(Companions.STATE_SLEEPING)) Companions.STATE_SLEEPING else Companions.STATE_IDLE
        // 1.0.2: the last ~2 minutes of the bedtime timer: calm (resting clip) while the voice winds down.
        rd?.playing == true && rd.sleepAt != null && rd.sleepAt - now <= BEDTIME_CALM_MS -> Companions.STATE_IDLE
        // 1.0: reading a book aloud uses the talking clip.
        rd?.playing == true -> Companions.STATE_SPEAKING
        ui.transcribing -> Companions.STATE_THINKING
        ui.generating && (last == null || last.isUser || last.text.isEmpty()) -> Companions.STATE_THINKING
        ui.generating -> Companions.STATE_SPEAKING
        ui.listening || ui.callHearing -> Companions.STATE_LISTENING
        ui.inCall && !ui.callMuted -> Companions.STATE_LISTENING
        else -> Companions.STATE_IDLE
    }
}

/**
 * 0.9.5: [avatarState] plus "sleeping" after [SLEEP_AFTER_MS] of idle, when the look has a sleeping clip.
 */
@Composable
fun rememberAvatarState(ui: UiState): String {
    // 1.0.2: ticks while a bedtime timer runs, so the calm-down at the end happens on time.
    val sleepAt = ui.reading?.sleepAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) { while (sleepAt != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(5_000) } }
    val base = avatarState(ui, now)
    val canSleep = ui.companion.hasClip(Companions.STATE_SLEEPING)
    var asleep by remember { mutableStateOf(false) }
    LaunchedEffect(base, canSleep, ui.messages.size, ui.companionId) {
        asleep = false
        if (base == Companions.STATE_IDLE && canSleep) { kotlinx.coroutines.delay(SLEEP_AFTER_MS); asleep = true }
    }
    return if (asleep && base == Companions.STATE_IDLE) Companions.STATE_SLEEPING else base
}

const val SLEEP_AFTER_MS = 90_000L
/** 1.0.2: how long before the bedtime timer ends the companion stops "talking" and rests. */
const val BEDTIME_CALM_MS = 120_000L

@Composable
fun rememberAssetBitmap(path: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(path) {
        runCatching {
            if (path.startsWith("/")) BitmapFactory.decodeFile(path)?.asImageBitmap()   // 0.9.5: avatar pack file
            else ctx.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }
}

/**
 * Round companion avatar. Shows the still poster, and on top of it a looping clip for the given state
 * (idle / listening / speaking / thinking) when [animate] is on. The classic Aurora shows the "A" logo.
 * [call] = head-and-shoulders framing for the big call-screen circle, otherwise a face close-up.
 */
@Composable
fun CompanionAvatar(
    companion: CompanionProfile,
    state: String,
    size: Dp,
    animate: Boolean,
    modifier: Modifier = Modifier,
    call: Boolean = false,
    ring: Color? = null,
    ringWidth: Dp = 3.dp,
) {
    var m = modifier.size(size).clip(CircleShape).background(Color.Black)
    if (ring != null) m = m.border(BorderStroke(ringWidth, ring), CircleShape)
    Box(m, contentAlignment = Alignment.Center) {
        if (!companion.animated()) {
            Text("A", style = TextStyle(brush = AuroraBrush, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold))
            return@Box
        }
        val fr = companion.framing()
        val zoom = if (call) fr.callZoom else fr.faceZoom
        val focus = if (call) fr.callY else fr.faceY
        val w = size * zoom
        val h = w * fr.aspect
        val top = (size / 2 - h * focus).coerceIn(size - h, 0.dp)
        val frame = Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).requiredSize(w, h).offset(y = top)
        rememberAssetBitmap(companion.still())?.let {
            Image(it, contentDescription = companion.name, contentScale = ContentScale.FillBounds, modifier = frame)
        }
        if (animate) AvatarClip(companion.clip(state), frame)
    }
}

/** Same looping clip, for the full-screen chat backdrop. */
@Composable
fun AvatarClipPublic(assetPath: String, modifier: Modifier) = AvatarClip(assetPath, modifier)

/** A muted, looping MediaPlayer on a TextureView (TextureView clips correctly inside Compose shapes). */
@Composable
private fun AvatarClip(assetPath: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember { MediaPlayer() }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var rendering by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            surface?.release()
        }
    }
    AndroidView(
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { player.setSurface(null) }
                        surface?.release(); surface = null; rendering = false
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier.alpha(if (rendering) 1f else 0f),
    )
    LaunchedEffect(assetPath, surface) {
        val s = surface ?: return@LaunchedEffect
        try {
            player.reset()
            player.setSurface(s)
            if (assetPath.startsWith("/")) player.setDataSource(assetPath)   // 0.9.5: avatar pack file
            else ctx.assets.openFd(assetPath).use { fd -> player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { it.start() }
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true
                false
            }
            player.setOnErrorListener { _, what, extra -> Log.w("AuroraAvatar", "clip error $what/$extra for $assetPath"); rendering = false; true }
            player.prepareAsync()
        } catch (e: Throwable) {
            Log.w("AuroraAvatar", "clip $assetPath failed", e)
            rendering = false
        }
    }
}
