package com.aurora.companion.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One remembered note about the user, per companion (0.8.3 running memory). */
data class MemoryNote(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    /** fact (written by the companion after a chat), summary (what a chat was about) or manual (added by you). */
    val kind: String = KIND_FACT,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
)

const val KIND_FACT = "fact"
const val KIND_SUMMARY = "summary"
const val KIND_MANUAL = "manual"

/**
 * Running memory, stored on the device only: files/memory/<companionId>.json.
 * A compact block of the newest notes (about 300 tokens) is added to the companion's system prompt.
 */
object MemoryStore {
    private const val FORMAT = "aurora-android-memory"
    const val MAX_FACTS = 40
    const val MAX_SUMMARIES = 6
    /** ~300 tokens for Llama 3.2 (about 3.7 characters per token in English). */
    const val PROMPT_CHAR_BUDGET = 1100

    private fun dir(ctx: Context) = File(ctx.filesDir, "memory").apply { mkdirs() }
    private fun file(ctx: Context, companionId: String) = File(dir(ctx), companionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    @Synchronized
    fun load(ctx: Context, companionId: String): List<MemoryNote> {
        val f = file(ctx, companionId)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("notes") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("text").trim()
                if (t.isEmpty()) null else MemoryNote(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() }, text = t,
                    kind = o.optString("kind", KIND_FACT), created = o.optLong("created"), updated = o.optLong("updated", o.optLong("created")),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(ctx: Context, companionId: String, notes: List<MemoryNote>) {
        val f = file(ctx, companionId)
        if (notes.isEmpty()) { f.delete(); return }
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().put("id", n.id).put("text", n.text).put("kind", n.kind).put("created", n.created).put("updated", n.updated))
        }
        val o = JSONObject().put("format", FORMAT).put("version", 1).put("companion", companionId).put("notes", arr)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString(1))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun update(ctx: Context, companionId: String, change: (MutableList<MemoryNote>) -> Unit): List<MemoryNote> = synchronized(this) {
        val list = load(ctx, companionId).toMutableList()
        change(list)
        save(ctx, companionId, list)
        list
    }

    fun wipe(ctx: Context, companionId: String) = synchronized(this) { file(ctx, companionId).delete() }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 2 }.toSet()

    /** True if [text] says roughly the same thing as an existing note. */
    fun isDuplicate(existing: List<MemoryNote>, text: String): Boolean {
        val w = words(text)
        if (w.isEmpty()) return true
        return existing.any { n ->
            val o = words(n.text)
            if (o.isEmpty()) false else {
                val inter = w.intersect(o).size.toDouble()
                inter / w.union(o).size >= 0.6 || (inter >= w.size && w.size >= 2)
            }
        }
    }

    /** Adds new notes from a memory pass, dropping duplicates and keeping the lists short. */
    fun merge(ctx: Context, companionId: String, facts: List<String>, summary: String?): Int {
        var added = 0
        update(ctx, companionId) { list ->
            val now = System.currentTimeMillis()
            for (f in facts) {
                if (!isDuplicate(list, f)) { list.add(MemoryNote(text = f, kind = KIND_FACT, created = now)); added++ }
            }
            if (!summary.isNullOrBlank() && !isDuplicate(list.filter { it.kind == KIND_SUMMARY }, summary)) {
                list.add(MemoryNote(text = summary, kind = KIND_SUMMARY, created = now)); added++
            }
            // Keep the newest automatic notes (manual notes are never dropped automatically).
            val facts2 = list.filter { it.kind == KIND_FACT }.sortedByDescending { it.updated }
            if (facts2.size > MAX_FACTS) list.removeAll(facts2.drop(MAX_FACTS).toSet())
            val sums = list.filter { it.kind == KIND_SUMMARY }.sortedByDescending { it.updated }
            if (sums.size > MAX_SUMMARIES) list.removeAll(sums.drop(MAX_SUMMARIES).toSet())
        }
        return added
    }

    /** The compact memory block for the system prompt, or "" if there's nothing to remember. */
    fun promptBlock(notes: List<MemoryNote>, userName: String = "the user"): String {
        if (notes.isEmpty()) return ""
        val manual = notes.filter { it.kind == KIND_MANUAL }.sortedByDescending { it.updated }
        val facts = notes.filter { it.kind == KIND_FACT }.sortedByDescending { it.updated }
        val sums = notes.filter { it.kind == KIND_SUMMARY }.sortedByDescending { it.updated }.take(2)
        val sb = StringBuilder()
        var used = 0
        val factLines = mutableListOf<String>()
        for (n in manual + facts) {
            val line = "- " + n.text.take(160)
            if (used + line.length > PROMPT_CHAR_BUDGET - 200) break
            factLines += line; used += line.length + 1
        }
        val sumLines = mutableListOf<String>()
        for (n in sums) {
            val line = "- " + n.text.take(200)
            if (used + line.length > PROMPT_CHAR_BUDGET) break
            sumLines += line; used += line.length + 1
        }
        if (factLines.isEmpty() && sumLines.isEmpty()) return ""
        sb.append("\n\nWhat you remember about $userName from earlier chats (use it naturally when it fits; don't list it or mention these notes):\n")
        factLines.forEach { sb.append(it).append('\n') }
        if (sumLines.isNotEmpty()) {
            sb.append("Recent chats:\n")
            sumLines.forEach { sb.append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    // ---------- the memory pass (the model writes the notes) ----------
    const val EXTRACT_SYSTEM =
        "You are a careful note-taker. You read a chat between a user and their companion and write very short memory notes " +
            "about the user, so the companion remembers them next time. Only write things the user clearly said. Output only the notes."

    fun extractPrompt(companionName: String, lines: List<Pair<Boolean, String>>, known: List<MemoryNote>): String {
        val sb = StringBuilder("Chat between the user and $companionName:\n")
        lines.forEach { (user, text) -> sb.append(if (user) "User: " else "$companionName: ").append(text.replace('\n', ' ').take(400)).append('\n') }
        val k = known.filter { it.kind != KIND_SUMMARY }.sortedByDescending { it.updated }.take(15)
        if (k.isNotEmpty()) {
            sb.append("\nAlready saved (don't repeat these):\n")
            k.forEach { sb.append("- ").append(it.text).append('\n') }
        }
        sb.append(
            "\nWrite the notes in exactly this format:\n" +
                "SUMMARY: <one short sentence about what you talked about>\n" +
                "FACT: <one short fact about the user>\n" +
                "Write 0 to 5 FACT lines: the user's name, family, friends, pets, likes, dislikes, plans, job, school, health or feelings. " +
                "Skip anything already saved, anything $companionName said about itself, and small talk. If there's nothing new, write no FACT lines."
        )
        return sb.toString()
    }

    private val BAD = Regex("""(?i)^(none|n/?a|nothing|no new|unknown|the user didn't|no facts?)\b""")

    /** Parses the model's output into (facts, summary). */
    fun parse(out: String): Pair<List<String>, String?> {
        val facts = mutableListOf<String>()
        var summary: String? = null
        for (raw in out.lines()) {
            val line = raw.trim().trimStart('*', '-', '•', ' ').trim()
            val m = Regex("""(?i)^(summary|fact)\s*\d*\s*[:\-]\s*(.+)$""").find(line) ?: continue
            val kind = m.groupValues[1].lowercase()
            val text = m.groupValues[2].trim().trim('"', '<', '>').trim()
            if (text.length < 6 || text.length > 180 || BAD.containsMatchIn(text) || text.contains("<one short")) continue
            if (kind == "summary") { if (summary == null) summary = text }
            else if (facts.size < 5 && facts.none { it.equals(text, true) }) facts += text
        }
        return facts to summary
    }
}
