package com.aurora.companion.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDateTime
import java.time.ZoneId

/** Fired reminders, for the open app to speak them in the companion's voice. */
object ReminderBus {
    data class Fired(val reminder: Reminder)
    private val _fired = MutableSharedFlow<Fired>(extraBufferCapacity = 8)
    val fired: SharedFlow<Fired> = _fired
    /** Set by MainActivity onResume / onPause. */
    @Volatile var appVisible = false
    fun emit(r: Reminder) { _fired.tryEmit(Fired(r)) }
}

object ReminderAlarms {
    private const val TAG = "AuroraReminders"
    const val CHANNEL = "reminders"
    const val ACTION_FIRE = "com.aurora.companion.android.REMINDER_FIRE"
    const val ACTION_SNOOZE = "com.aurora.companion.android.REMINDER_SNOOZE"
    const val ACTION_DONE = "com.aurora.companion.android.REMINDER_DONE"
    const val EXTRA_ID = "reminder_id"
    const val SNOOZE_MINUTES = 10L

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you set with your companion"
                enableVibration(true)
            })
        }
    }

    private fun pi(ctx: Context, action: String, id: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
            .setData(android.net.Uri.parse("aurora-reminder://$action/$id"))   // unique per reminder + action
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Arms (or re-arms) the alarm for one reminder. Exact when allowed, otherwise inexact (may be a few minutes late). */
    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = pi(ctx, ACTION_FIRE, r.id)
        am.cancel(p)
        val at = r.fireAt ?: return
        val whenMs = millis(at).coerceAtLeast(System.currentTimeMillis() + 1000)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, p)
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, p)
        }
        Log.i(TAG, "armed ${r.id} at $at exact=${canExact(ctx)}")
    }

    fun cancel(ctx: Context, id: String) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx, ACTION_FIRE, id))
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }

    /** After boot, app update, time or time-zone change: re-arm everything; deliver anything missed while off. */
    fun rescheduleAll(ctx: Context) {
        val now = LocalDateTime.now()
        val missed = mutableListOf<String>()
        ReminderStore.load(ctx).filter { it.active }.forEach { r ->
            val at = r.fireAt
            if (at != null && at.isBefore(now.minusSeconds(30))) missed += r.id else if (at != null) schedule(ctx, r)
        }
        missed.forEach { fire(ctx, it, late = true) }
        Log.i(TAG, "rescheduled; missed=${missed.size}")
    }

    /** The alarm went off. */
    fun fire(ctx: Context, id: String, late: Boolean = false) {
        val now = LocalDateTime.now()
        var fired: Reminder? = null
        var next: Reminder? = null
        ReminderStore.update(ctx) { items ->
            val i = items.indexOfFirst { it.id == id }
            if (i < 0 || items[i].done) return@update
            val r = items[i]
            fired = r
            next = when {
                r.snoozeUntil != null && r.repeat == null -> r.copy(snoozeUntil = null, notified = true)
                r.repeat != null && r.due != null -> {
                    // keep the next regular occurrence (skip any that passed while the phone was off)
                    var n: LocalDateTime = r.due
                    while (!n.isAfter(now)) n = ReminderParser.nextOccurrence(r.repeat, r.due.toLocalTime(), n)
                    r.copy(due = n, snoozeUntil = null, notified = false)
                }
                else -> r.copy(notified = true)
            }
            items[i] = next!!
        }
        val r = fired ?: return
        next?.let { if (it.fireAt != null) schedule(ctx, it) }
        notify(ctx, r, late)
        ReminderBus.emit(r)
    }

    fun snooze(ctx: Context, id: String, minutes: Long = SNOOZE_MINUTES) {
        var upd: Reminder? = null
        ReminderStore.update(ctx) { items ->
            val i = items.indexOfFirst { it.id == id }; if (i < 0) return@update
            upd = items[i].copy(snoozeUntil = LocalDateTime.now().plusMinutes(minutes).withNano(0), done = false); items[i] = upd!!
        }
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
        upd?.let { schedule(ctx, it) }
    }

    /** Done: a one-off is checked off; a repeating one is just dismissed for this time. */
    fun done(ctx: Context, id: String) {
        var upd: Reminder? = null
        ReminderStore.update(ctx) { items ->
            val i = items.indexOfFirst { it.id == id }; if (i < 0) return@update
            val r = items[i]
            upd = if (r.repeat != null) r.copy(snoozeUntil = null) else r.copy(done = true, snoozeUntil = null)
            items[i] = upd!!
        }
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
        upd?.let { if (it.done) cancel(ctx, id) else schedule(ctx, it) }
    }

    private fun companionFor(ctx: Context, id: String): CompanionProfile {
        if (Companions.custom.isEmpty()) runCatching { Companions.custom = CharacterStore.loadAll(ctx) }
        return Companions.byId(id)
    }

    fun notify(ctx: Context, r: Reminder, late: Boolean) {
        if (!canNotify(ctx)) { Log.w(TAG, "notifications are off; reminder ${r.id} not shown"); return }
        ensureChannel(ctx)
        val c = companionFor(ctx, r.companion)
        val open = PendingIntent.getActivity(ctx, r.id.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = (if (late) "Missed while your phone was off: " else "") + r.text.replaceFirstChar { it.uppercase() }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("${c.name}: reminder")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + if (r.repeat != null) "\n(${ReminderParser.repeatLabel(r.repeat)})" else ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Snooze ${SNOOZE_MINUTES} min", pi(ctx, ACTION_SNOOZE, r.id))
            .addAction(0, "Done", pi(ctx, ACTION_DONE, r.id))
        avatarBitmap(ctx, c)?.let { b.setLargeIcon(it) }
        try { NotificationManagerCompat.from(ctx).notify(r.id.hashCode(), b.build()) } catch (e: SecurityException) { Log.w(TAG, "notify refused", e) }
    }

    /** Round face crop of the companion's still (bundled webp), for the notification's large icon. */
    fun avatarBitmap(ctx: Context, c: CompanionProfile, size: Int = 192): Bitmap? = runCatching {
        AvatarPacks.ensure(ctx)
        if (!c.animated()) return@runCatching null
        val path = c.still()
        val src = (if (path.startsWith("/")) BitmapFactory.decodeFile(path) else ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }) ?: return@runCatching null
        val fr = c.framing()
        val side = (src.width / fr.faceZoom).toInt().coerceIn(32, minOf(src.width, src.height))
        val cy = (src.height * fr.faceY).toInt()
        val top = (cy - side / 2).coerceIn(0, src.height - side)
        val left = (src.width - side) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        cv.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(src, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), paint)
        src.recycle()
        out
    }.getOrNull()
}

/** Alarm + notification-action receiver (not exported). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderAlarms.EXTRA_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ReminderAlarms.ACTION_FIRE -> ReminderAlarms.fire(context.applicationContext, id)
                    ReminderAlarms.ACTION_SNOOZE -> ReminderAlarms.snooze(context.applicationContext, id)
                    ReminderAlarms.ACTION_DONE -> ReminderAlarms.done(context.applicationContext, id)
                }
            } catch (e: Throwable) { Log.e("AuroraReminders", "receiver failed", e) } finally { pending.finish() }
        }.start()
    }
}

/** Re-arms alarms after reboot, app update, clock / time-zone change, or exact-alarm permission change. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { ReminderAlarms.rescheduleAll(context.applicationContext) }
            catch (e: Throwable) { Log.e("AuroraReminders", "reschedule failed", e) } finally { pending.finish() }
        }.start()
    }
}
