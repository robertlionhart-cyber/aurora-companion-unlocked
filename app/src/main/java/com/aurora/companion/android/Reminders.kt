package com.aurora.companion.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 0.7: reminders. Same item fields as the desktop app's <Name>_reminders.json
 * ({"reminders":[{id,text,due (local ISO, no zone),created,done,source,notified}]}), plus Android extras:
 * repeat (daily | weekdays | weekly:MONDAY ...), companion (who set it) and snooze_until.
 * Times are local wall-clock, so "5 PM" stays 5 PM after a time-zone change.
 */
data class Reminder(
    val id: String,
    val text: String,
    val due: LocalDateTime?,
    val repeat: String? = null,
    val created: LocalDateTime = LocalDateTime.now().withNano(0),
    val done: Boolean = false,
    val source: String = "settings",
    val notified: Boolean = false,
    val companion: String = Companions.AURORA.id,
    val snoozeUntil: LocalDateTime? = null,
) {
    /** When the alarm should go off next (snooze wins). */
    val fireAt: LocalDateTime? get() = if (done) null else snoozeUntil ?: due.takeIf { !notified || repeat != null }
    val active: Boolean get() = !done
}

object ReminderStore {
    private val lock = Any()
    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so open screens refresh (receivers run in the same process). */
    val version: StateFlow<Int> = _version
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun file(ctx: Context) = File(ctx.filesDir, "reminders.json")

    fun load(ctx: Context): List<Reminder> = synchronized(lock) {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("reminders") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
        }.getOrElse { emptyList() }
    }

    private fun saveLocked(ctx: Context, items: List<Reminder>) {
        val arr = JSONArray(); items.forEach { arr.put(toJson(it)) }
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONObject().put("reminders", arr).toString(2))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        _version.value = _version.value + 1
    }

    /** Atomic read-modify-write. */
    fun update(ctx: Context, fn: (MutableList<Reminder>) -> Unit): List<Reminder> = synchronized(lock) {
        val items = load(ctx).toMutableList()
        fn(items)
        saveLocked(ctx, items)
        items
    }

    fun get(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }

    fun newId() = "r${System.currentTimeMillis()}${(10..99).random()}"

    private fun toJson(r: Reminder) = JSONObject().apply {
        put("id", r.id); put("text", r.text); put("due", r.due?.format(ISO) ?: JSONObject.NULL)
        put("created", r.created.format(ISO)); put("done", r.done); put("source", r.source); put("notified", r.notified)
        put("repeat", r.repeat ?: JSONObject.NULL); put("companion", r.companion)
        put("snooze_until", r.snoozeUntil?.format(ISO) ?: JSONObject.NULL)
    }

    private fun parseTime(s: String?): LocalDateTime? =
        if (s.isNullOrBlank() || s == "null") null else runCatching { LocalDateTime.parse(s.take(19)) }.getOrNull()

    private fun fromJson(o: JSONObject): Reminder? {
        val text = o.optString("text").trim(); if (text.isEmpty()) return null
        return Reminder(
            id = o.optString("id").ifEmpty { newId() }, text = text, due = parseTime(o.optString("due")),
            repeat = o.optString("repeat").takeIf { it.isNotEmpty() && it != "null" },
            created = parseTime(o.optString("created")) ?: LocalDateTime.now().withNano(0),
            done = o.optBoolean("done"), source = o.optString("source", "settings"), notified = o.optBoolean("notified"),
            companion = o.optString("companion", Companions.AURORA.id), snoozeUntil = parseTime(o.optString("snooze_until")),
        )
    }
}

/** Natural-language reminder parsing, rule-based and on-device (extends the desktop app's _parse_due_phrase). */
object ReminderParser {
    data class When(val due: LocalDateTime?, val repeat: String?, val spans: List<IntRange>)
    data class Parsed(val task: String, val due: LocalDateTime?, val repeat: String?)

    private val WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
        "couple of" to 2, "a couple of" to 2, "few" to 3, "a few" to 3,
    )
    private const val NUM = """(\d+|a couple of|couple of|a few|few|an|a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty-five|forty|fifty|sixty|ninety)"""
    private const val HOURW = """(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"""
    private const val DAYS = """(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)"""
    private const val AMPM = """(a\.?\s?m\.?|p\.?\s?m\.?)"""

    private val REL = Regex("""\b(?:in|after)\s+(?:about\s+|approx(?:imately)?\s+|like\s+|another\s+)?$NUM\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?)\b""")
    private val HALF_HOUR = Regex("""\bin\s+(?:about\s+)?half\s+an?\s+hour\b""")
    private val HOUR_HALF = Regex("""\bin\s+(?:about\s+)?(?:an|one)\s+hour\s+and\s+a\s+half\b""")
    private val REPEAT = Regex("""\b(every\s+day|everyday|daily|each\s+day|every\s+morning|every\s+evening|every\s+night|every\s+afternoon|every\s+weekday|on\s+weekdays|weekdays|every\s+week|weekly|every\s+$DAYS(?:s)?|on\s+${DAYS}s)\b""")
    private val DAYWORD = Regex("""\b(day\s+after\s+tomorrow|today|tonight|tomorrow|tmrw|tmr)\b""")
    private val WEEKDAY = Regex("""\b(?:on\s+|next\s+|this\s+|coming\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val PERIOD = Regex("""\b(?:in\s+the\s+|this\s+)?(morning|afternoon|evening|night)\b""")
    private val TIME_AMPM = Regex("""\b(?:at\s+|by\s+|around\s+|@\s*)?$HOURW(?::(\d{2})|\s+(\d{2}))?\s*$AMPM(?=$|[^a-z])""")
    private val TIME_AT = Regex("""\b(?:at|by|around)\s+$HOURW(?::(\d{2})|\s+(thirty|fifteen|forty-five|o'?clock))?\b""")
    private val TIME_COLON = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val NOON = Regex("""\b(?:at\s+)?(noon|midday|midnight)\b""")

    private fun num(s: String): Int? = s.toIntOrNull() ?: WORDS[s.trim()]
    private fun dow(s: String): DayOfWeek = when (s.take(3)) {
        "mon" -> DayOfWeek.MONDAY; "tue" -> DayOfWeek.TUESDAY; "wed" -> DayOfWeek.WEDNESDAY; "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY; "sat" -> DayOfWeek.SATURDAY; else -> DayOfWeek.SUNDAY
    }

    fun parseWhen(input: String, now: LocalDateTime = LocalDateTime.now()): When {
        val t = input.lowercase(Locale.ROOT)
        val spans = mutableListOf<IntRange>()
        // 1) relative: in 20 minutes / in an hour / in half an hour
        HOUR_HALF.find(t)?.let { spans += it.range; return When(now.plusMinutes(90).withNano(0), null, spans) }
        HALF_HOUR.find(t)?.let { spans += it.range; return When(now.plusMinutes(30).withNano(0), null, spans) }
        REL.find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: 0
            if (n > 0) {
                spans += m.range
                val u = m.groupValues[2]
                val due = when {
                    u.startsWith("s") -> now.plusSeconds(n.toLong())
                    u.startsWith("m") -> now.plusMinutes(n.toLong())
                    u.startsWith("h") -> now.plusHours(n.toLong())
                    u.startsWith("d") -> now.plusDays(n.toLong())
                    else -> now.plusWeeks(n.toLong())
                }
                return When(due.withNano(0), null, spans)
            }
        }
        // 2) repeat
        var repeat: String? = null
        var periodDefault: LocalTime? = null
        REPEAT.find(t)?.let { m ->
            spans += m.range
            val s = m.value.replace(Regex("\\s+"), " ")
            repeat = when {
                s.contains("weekday") -> "weekdays"
                s == "every week" || s == "weekly" -> "weekly:" + now.dayOfWeek.name
                Regex(DAYS).find(s.removePrefix("every ").removePrefix("on ")) != null && !s.contains("day ") && s != "every day" && s != "everyday" && s != "each day" ->
                    "weekly:" + dow(s.removePrefix("every ").removePrefix("on ")).name
                else -> "daily"
            }
            when {
                s.endsWith("morning") -> periodDefault = LocalTime.of(8, 0)
                s.endsWith("afternoon") -> periodDefault = LocalTime.of(15, 0)
                s.endsWith("evening") -> periodDefault = LocalTime.of(18, 0)
                s.endsWith("night") -> periodDefault = LocalTime.of(21, 0)
            }
        }
        // 3) day
        var date: LocalDate? = null
        var pmHint: Boolean? = null
        DAYWORD.find(t)?.let { m ->
            spans += m.range
            date = when (m.groupValues[1]) {
                "today" -> now.toLocalDate()
                "tonight" -> { pmHint = true; periodDefault = periodDefault ?: LocalTime.of(20, 0); now.toLocalDate() }
                "day after tomorrow" -> now.toLocalDate().plusDays(2)
                else -> now.toLocalDate().plusDays(1)
            }
        }
        if (date == null && repeat == null) WEEKDAY.find(t)?.let { m ->
            spans += m.range
            val d = dow(m.groupValues[1])
            date = if (m.value.trim().startsWith("next") && d == now.dayOfWeek) now.toLocalDate().plusWeeks(1)
                   else now.toLocalDate().with(TemporalAdjusters.next(d))
        }
        PERIOD.find(t)?.let { m ->
            if (spans.none { it.first <= m.range.first && m.range.last <= it.last }) spans += m.range
            when (m.groupValues[1]) {
                "morning" -> { pmHint = false; periodDefault = periodDefault ?: LocalTime.of(9, 0) }
                "afternoon" -> { pmHint = true; periodDefault = periodDefault ?: LocalTime.of(15, 0) }
                "evening" -> { pmHint = true; periodDefault = periodDefault ?: LocalTime.of(18, 0) }
                else -> { pmHint = true; periodDefault = periodDefault ?: LocalTime.of(21, 0) }
            }
        }
        // 4) clock time
        var time: LocalTime? = null
        var ambiguous = false
        val am = TIME_AMPM.find(t)
        if (am != null) {
            val h0 = num(am.groupValues[1]) ?: 0
            val mi = (am.groupValues[2].ifEmpty { am.groupValues[3] }).toIntOrNull() ?: 0
            val pm = am.groupValues[4].startsWith("p")
            var h = h0 % 12; if (pm) h += 12
            if (h0 in 0..23 && mi in 0..59) { time = LocalTime.of(if (h0 > 12) h0 else h, mi); spans += am.range }
        } else {
            NOON.find(t)?.let { m -> time = if (m.groupValues[1] == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON; spans += m.range }
            if (time == null) {
                val at = TIME_AT.find(t)
                if (at != null) {
                    val h = num(at.groupValues[1]) ?: -1
                    val tail = at.groupValues[3]
                    val mi = at.groupValues[2].toIntOrNull() ?: when (tail) { "thirty" -> 30; "fifteen" -> 15; "forty-five" -> 45; else -> 0 }
                    if (h in 0..23 && mi in 0..59) { time = LocalTime.of(h, mi); ambiguous = h in 1..11; spans += at.range }
                } else TIME_COLON.find(t)?.let { m ->
                    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                    if (h in 0..23 && mi in 0..59) { time = LocalTime.of(h, mi); ambiguous = h in 1..11; spans += m.range }
                }
            }
        }
        if (time == null && date == null && repeat == null && periodDefault == null) return When(null, null, spans)
        // 5) resolve am/pm for "at 5" without am/pm
        var tm = time ?: periodDefault ?: LocalTime.of(9, 0)
        if (time != null && ambiguous) {
            tm = when {
                pmHint == true -> tm.plusHours(12)
                pmHint == false -> tm
                date == null && repeat == null -> {   // today: the next time it comes round
                    val a = now.toLocalDate().atTime(tm); val p = a.plusHours(12)
                    if (a.isAfter(now)) tm else if (p.isAfter(now)) tm.plusHours(12) else if (tm.hour in 1..6) tm.plusHours(12) else tm
                }
                tm.hour in 1..6 -> tm.plusHours(12)
                else -> tm
            }
        }
        // 6) date
        val due: LocalDateTime = when {
            repeat != null -> nextOccurrence(repeat!!, tm, now)
            date != null -> date!!.atTime(tm)
            else -> now.toLocalDate().atTime(tm).let { if (!it.isAfter(now)) it.plusDays(1) else it }
        }
        return When(due, repeat, spans)
    }

    /** Next time a repeating reminder is due, strictly after [after]. */
    fun nextOccurrence(repeat: String, time: LocalTime, after: LocalDateTime): LocalDateTime {
        var d = after.toLocalDate().atTime(time)
        if (!d.isAfter(after)) d = d.plusDays(1)
        return when {
            repeat == "weekdays" -> { while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1); d }
            repeat.startsWith("weekly:") -> {
                val w = runCatching { DayOfWeek.valueOf(repeat.substringAfter(':')) }.getOrDefault(after.dayOfWeek)
                while (d.dayOfWeek != w) d = d.plusDays(1); d
            }
            else -> d
        }
    }

    private val COMMANDS = listOf(
        Regex("""(?:please\s+)?remind\s+me\s+to\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:please\s+)?remind\s+me\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:please\s+)?set\s+(?:a|an|up\s+a)\s+reminder\s+(?:to|for|that)\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:please\s+)?(?:add|put|create|make)\s+(?:a\s+)?reminder\s+(?:to\s+(?:my\s+)?(?:list|schedule)\s+)?(?:to|for|that)\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:please\s+)?don'?t\s+let\s+me\s+forget\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:please\s+)?don'?t\s+forget\s+to\s+(?:remind\s+me\s+(?:to\s+)?)?(.+)""", RegexOption.IGNORE_CASE),
    )
    private val QUESTION_START = Regex("""^(what|who|why|how|where|when|which|of|about|if|whether)\b""", RegexOption.IGNORE_CASE)

    /** "remind me to call mom at 5pm" -> Parsed("call mom", today 17:00, null). Null when it isn't a reminder request. */
    fun parseCommand(text: String, now: LocalDateTime = LocalDateTime.now()): Parsed? {
        val t = text.trim()
        if (t.isEmpty()) return null
        var m: MatchResult? = null
        for (re in COMMANDS) { m = re.find(t); if (m != null) break }
        val w = parseWhen(t, now)
        if (m == null) {
            // "remind me in five minutes" style, no task
            return if (w.due != null && Regex("""\bremind\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) Parsed("your reminder", w.due, w.repeat) else null
        }
        val g = m.groups[1]!!
        val start = g.range.first
        val sb = StringBuilder()
        for (i in g.range) {
            if (w.spans.none { i in it }) sb.append(t[i]) else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        var task = sb.toString().replace(Regex("\\s+"), " ").trim()
        repeat(3) {
            task = task.replace(Regex("""^(?:to|that|about|for|and)\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(?:at|on|by|in|for|and|to|this|the)$""", RegexOption.IGNORE_CASE), "")
                .trim(' ', ',', '.', '-', '!', '?')
        }
        if (w.due == null && (task.length < 2 || QUESTION_START.containsMatchIn(task))) return null
        if (task.length < 2) task = "your reminder"
        task = task.replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE), "your").replaceFirstChar { it.lowercase() }
        return Parsed(task.take(240), w.due, w.repeat)
    }

    // Desktop: try_complete_reminder_command / user_asks_schedule
    private val DONE_KEYS = listOf("i already did", "i did that", "mark done", "mark that done", "cancel the reminder", "cancel reminder",
        "cancel my reminder", "remove the reminder", "delete the reminder", "i finished", "done with", "check it off", "check that off",
        "check off", "mark it done", "mark it off", "you can check it", "cross it off")
    private val SCHEDULE_KEYS = listOf("what's on the schedule", "whats on the schedule", "what is on the schedule", "am i forgetting",
        "am i missing something", "should i be doing", "what am i supposed to", "what was i supposed to", "was i supposed to",
        "supposed to be doing", "any reminders", "my reminders", "my schedule", "what's on my list", "whats on my list",
        "what do i need to do", "anything i need to do", "anything coming up", "what's next on", "whats next on",
        "did you remind me", "were you supposed to remind", "what reminders")

    fun isDoneCommand(text: String) = text.lowercase().let { l -> DONE_KEYS.any { it in l } }
    fun asksSchedule(text: String) = text.lowercase().let { l -> SCHEDULE_KEYS.any { it in l } }

    /** Best keyword match among open reminders, or the only one. */
    fun matchOpen(text: String, open: List<Reminder>): Reminder? {
        val low = text.lowercase()
        val best = open.maxByOrNull { r -> Regex("[a-z0-9]{3,}").findAll(r.text.lowercase()).count { it.value in low } }
        val score = best?.let { r -> Regex("[a-z0-9]{3,}").findAll(r.text.lowercase()).count { it.value in low } } ?: 0
        return if (score >= 1) best else open.singleOrNull()
    }

    // ---------- formatting ----------
    private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DATE_FMT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    fun formatWhen(d: LocalDateTime?, repeat: String?, now: LocalDateTime = LocalDateTime.now()): String {
        if (d == null) return "no set time"
        val time = d.format(TIME_FMT)
        if (repeat != null) {
            val r = when {
                repeat == "daily" -> "every day"
                repeat == "weekdays" -> "weekdays"
                repeat.startsWith("weekly:") -> "every " + repeat.substringAfter(':').lowercase().replaceFirstChar { it.uppercase() }
                else -> repeat
            }
            return "$r at $time (next: ${dayLabel(d, now)})"
        }
        return "${dayLabel(d, now)} at $time"
    }

    private fun dayLabel(d: LocalDateTime, now: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), d.toLocalDate())
        return when (days) { 0L -> "today"; 1L -> "tomorrow"; -1L -> "yesterday"; else -> d.format(DATE_FMT) }
    }

    fun repeatLabel(repeat: String?) = when {
        repeat == null -> "Once"
        repeat == "daily" -> "Every day"
        repeat == "weekdays" -> "Weekdays"
        repeat.startsWith("weekly:") -> "Every " + repeat.substringAfter(':').lowercase().replaceFirstChar { it.uppercase() }
        else -> repeat
    }
}
