package com.aurora.companion.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HistMsg(val user: Boolean, val text: String, val time: Long)

/** One saved conversation (typed chat or a call), for review only. It is never fed back into the model. */
data class Conversation(
    val id: String,
    val companionId: String,
    val companionName: String,
    val mode: String,          // MODE_CHAT or MODE_CALL
    val start: Long,
    val end: Long,
    val messages: List<HistMsg>,
) {
    val title: String get() = messages.firstOrNull { it.user }?.text?.replace(Regex("\\s+"), " ")?.trim()?.take(80) ?: "(no messages)"
    val durationMs: Long get() = (end - start).coerceAtLeast(0)
}

/**
 * 0.8.2 conversation history: app-private files/history/<id>.json, one file per conversation, rewritten after
 * every completed reply so nothing is lost if the app is closed.
 */
object HistoryStore {
    const val MODE_CHAT = "chat"
    const val MODE_CALL = "call"
    private const val FORMAT = "aurora-android-conversation"

    private fun dir(ctx: Context) = File(ctx.filesDir, "history").apply { mkdirs() }

    fun newId(): String = "c" + System.currentTimeMillis().toString(36) + (1000..9999).random()

    fun save(ctx: Context, c: Conversation) {
        val o = JSONObject()
            .put("format", FORMAT).put("version", 1)
            .put("id", c.id).put("companion_id", c.companionId).put("companion_name", c.companionName)
            .put("mode", c.mode).put("start", c.start).put("end", c.end)
        val arr = JSONArray()
        c.messages.forEach { arr.put(JSONObject().put("role", if (it.user) "user" else "assistant").put("text", it.text).put("time", it.time)) }
        o.put("messages", arr)
        val f = File(dir(ctx), "${c.id}.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun loadAll(ctx: Context): List<Conversation> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { parse(JSONObject(f.readText())) }.onFailure { Log.w("AuroraHistory", "bad ${f.name}", it) }.getOrNull()
        }.sortedByDescending { it.start }

    fun delete(ctx: Context, id: String) { File(dir(ctx), "$id.json").delete() }

    private fun parse(o: JSONObject): Conversation {
        val arr = o.getJSONArray("messages")
        return Conversation(
            id = o.getString("id"), companionId = o.optString("companion_id"), companionName = o.optString("companion_name"),
            mode = o.optString("mode", MODE_CHAT), start = o.getLong("start"), end = o.optLong("end", o.getLong("start")),
            messages = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                HistMsg(m.optString("role") == "user", m.optString("text"), m.optLong("time"))
            },
        )
    }

    private val DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
    private val TIME = DateTimeFormatter.ofPattern("h:mm a")
    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)
    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DAY)
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60} min"; else -> "${s / 3600} h ${(s % 3600) / 60} min" }
    }

    fun fileName(c: Conversation): String {
        val d = Instant.ofEpochMilli(c.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))
        val n = c.companionName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Aurora" }
        return "Aurora_${n}_${c.mode}_$d.txt"
    }

    fun toTxt(c: Conversation): String = buildString {
        append("Aurora Companion - ").append(if (c.mode == MODE_CALL) "call" else "chat").append(" with ").append(c.companionName).append('\n')
        append(day(c.start)).append(", ").append(time(c.start)).append(" - ").append(time(c.end))
            .append(" (").append(duration(c.durationMs)).append(")\n\n")
        c.messages.forEach { m ->
            append('[').append(time(m.time)).append("] ").append(if (m.user) "You" else c.companionName).append(": ").append(m.text.trim()).append("\n\n")
        }
    }
}
