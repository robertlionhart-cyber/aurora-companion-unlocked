package com.aurora.companion.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 0.8.9: keeps the app process alive while an AI model is loaded or a call is on. Without it, Samsung's app freezer
 * froze Aurora as soon as you switched apps and then killed it ("Sync transaction while frozen"), so the model had to
 * reload every time. A foreground service (with a quiet, low-importance notification) is the supported way to say
 * "this app is still doing something for the user". It does no work itself; the model stays in the app process.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val inCall = intent?.getBooleanExtra(EXTRA_CALL, false) == true
        val model = intent?.getStringExtra(EXTRA_MODEL)
        ensureChannel(this)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (inCall) "Call in progress" else "Your companion is ready")
            .setContentText(if (inCall) "Aurora is listening on this device." else
                "The AI model${if (model != null) " ($model)" else ""} stays loaded, so replies are instant when you come back.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(if (inCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (inCall && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            // e.g. the microphone type isn't allowed right now: keep the plain keep-alive.
            Log.w(TAG, "startForeground(type=$type) failed, retrying without microphone", e)
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            } catch (e2: Exception) {
                Log.e(TAG, "keep-alive could not start", e2)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "AuroraKeepAlive"
        const val CHANNEL = "aurora_keepalive"
        private const val NOTIFICATION_ID = 4242
        private const val ACTION_STOP = "com.aurora.companion.android.KEEPALIVE_STOP"
        private const val EXTRA_CALL = "call"
        private const val EXTRA_MODEL = "model"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Keep companion loaded", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while an AI model is loaded or a call is on, so Android doesn't close the app in the background"
                    setShowBadge(false)
                })
            }
        }

        /** Starts (or updates) the keep-alive while [on]; stops it otherwise. Safe to call repeatedly. */
        fun update(ctx: Context, on: Boolean, inCall: Boolean, model: String?) {
            val app = ctx.applicationContext
            try {
                if (on) {
                    val i = Intent(app, KeepAliveService::class.java).putExtra(EXTRA_CALL, inCall).putExtra(EXTRA_MODEL, model)
                    androidx.core.content.ContextCompat.startForegroundService(app, i)
                } else {
                    app.stopService(Intent(app, KeepAliveService::class.java))
                }
            } catch (e: Exception) {
                // Android 12+ refuses to start a foreground service from the background; it's retried next time the
                // state changes while the app is open.
                Log.w(TAG, "keep-alive update(on=$on) refused", e)
            }
        }
    }
}
