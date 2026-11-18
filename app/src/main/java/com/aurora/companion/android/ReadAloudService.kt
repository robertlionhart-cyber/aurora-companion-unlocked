package com.aurora.companion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 1.0: foreground "media playback" service while a book is being read aloud, so reading continues with the screen off.
 * It owns a MediaSession (lock-screen / headset / Bluetooth controls) and the media notification; the reading itself
 * happens in [ReadAloud].
 */
class ReadAloudService : Service() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        session = MediaSession(this, "AuroraReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = ReadAloud.play()
                override fun onPause() = ReadAloud.pause()
                override fun onStop() = ReadAloud.stop()
                override fun onSkipToNext() = ReadAloud.chapter(1)
                override fun onSkipToPrevious() = ReadAloud.chapter(-1)
                override fun onFastForward() = ReadAloud.skipSentence(1)
                override fun onRewind() = ReadAloud.skipSentence(-1)
            })
            setSessionActivity(openApp())
            isActive = true
        }
        scope.launch {
            ReadAloud.state
                .map { s -> s?.let { listOf(it.title, it.author, it.chapterTitle, it.playing, it.loading, it.sleepOn) } }
                .distinctUntilChanged()
                .collect { update() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { ReadAloud.stop(); return START_NOT_STICKY }
            ACTION_TOGGLE -> ReadAloud.toggle()
            ACTION_BACK -> ReadAloud.skipSentence(-1)
            ACTION_FWD -> ReadAloud.skipSentence(1)
            ACTION_QUIT -> { quit(); return START_NOT_STICKY }
        }
        update()
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(act: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, ReadAloudService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun update() {
        val s = ReadAloud.state.value
        if (s == null) { quit(); return }
        val ses = session ?: return
        ses.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.author)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.chapterTitle)
            .build())
        ses.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP)
            .setState(if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (s.playing) 1f else 0f)
            .build())
        fun act(icon: Int, label: String, a: String, code: Int) = Notification.Action.Builder(Icon.createWithResource(this, icon), label, action(a, code)).build()
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(s.title)
            .setContentText(listOf(s.chapterTitle, if (s.sleepOn) "\u23F2 timer on" else "").filter { it.isNotBlank() }.joinToString("  \u00B7  ").ifBlank { s.author })
            .setSubText(if (s.playing) "Reading aloud" else "Paused")
            .setContentIntent(openApp())
            .setDeleteIntent(action(ACTION_QUIT, 5))
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(act(android.R.drawable.ic_media_rew, "Back", ACTION_BACK, 1))
            .addAction(if (s.playing) act(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE, 2) else act(android.R.drawable.ic_media_play, "Read", ACTION_TOGGLE, 2))
            .addAction(act(android.R.drawable.ic_media_ff, "Forward", ACTION_FWD, 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(ses.sessionToken).setShowActionsInCompactView(0, 1, 2))
        val n = b.build()
        try {
            if (s.playing || !foreground) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
                foreground = true
            } else {
                // Paused: stays as a normal (dismissable) notification so Android may stop the service; Read resumes.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                foreground = false
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "foreground refused", e)
        }
    }

    private fun quit() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { session?.isActive = false; session?.release() }
        session = null
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AuroraReadAloudSvc"
        const val CHANNEL = "aurora_read_aloud"
        const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.aurora.companion.android.READ_STOP"
        const val ACTION_TOGGLE = "com.aurora.companion.android.READ_TOGGLE"
        const val ACTION_BACK = "com.aurora.companion.android.READ_BACK"
        const val ACTION_FWD = "com.aurora.companion.android.READ_FWD"
        const val ACTION_QUIT = "com.aurora.companion.android.READ_QUIT"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controls while your companion reads a book to you"
                    setShowBadge(false)
                })
        }

        fun start(ctx: Context) {
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ReadAloudService::class.java)) }
            catch (e: Exception) { Log.w(TAG, "start refused", e) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ReadAloudService::class.java)) }
        }
    }
}
