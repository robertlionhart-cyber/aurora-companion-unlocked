package com.aurora.companion.android

import android.util.Log
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.9.3: Online mode (globe at the top of the chat; Offline by default). Only when Online is on, a message that looks
 * like a weather, news or "look this up" question is answered with a small, free, no-key lookup, and a compact
 * result (< 600 chars, with source and date) rides along with that one user turn as an app note.
 * Only the search words (or a city / approximate coordinates for weather) leave the phone, never the chat.
 * Sources: Open-Meteo (weather + geocoding), BBC News RSS and Google News RSS (headlines), Wikipedia REST
 * (summaries) with the DuckDuckGo Instant Answer API as a fallback.
 */
object OnlineTools {
    private const val TAG = "OnlineTools"
    private val UA = "AuroraCompanion/1.0 (Android; github.com/" + Edition.UPDATE_REPO + ")"
    const val MAX_NOTE = 600

    sealed class Intent {
        /** [city] = named in the message ("weather in Paris"), else null. */
        data class Weather(val city: String?, val tomorrow: Boolean) : Intent()
        data class News(val topic: String?) : Intent()
        data class Lookup(val query: String) : Intent()
    }

    /** What happened, for the note and the small notice under the chat. */
    data class Result(val note: String, val source: String, val ok: Boolean, val needsCity: Boolean = false, val city: String? = null)

    private val weatherRe = Regex("""\b(weather|forecast|temperature outside|how (hot|cold|warm) is it|(is|will) it (rain|snow|be (hot|cold|sunny))|umbrella|raining|snowing)\b""", RegexOption.IGNORE_CASE)
    private val cityRe = Regex("""\b(?:in|for|at|near)\s+([A-Za-z][A-Za-z .,'-]{1,40}?)(?:\s+(?:today|tomorrow|tonight|right now|now|this week(?:end)?))?\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    private val newsRe = Regex("""\b(news|headlines|current events|what'?s happening in the world)\b""", RegexOption.IGNORE_CASE)
    private val newsTopicRe = Regex("""\b(?:news|headlines)\s+(?:about|on|for|from|regarding)\s+(.{2,60}?)\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    private val explicitRe = Regex("""^(?:can you |could you |please )?(?:look up|search(?: for)?|google|find out about|check online(?: for)?)\s+(.{2,80}?)\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    private val askRe = Regex("""^(?:hey[, ]+\w+[, ]+)?(?:who (?:is|was|are|were)|what (?:is|was|are|were)|what's|whats|tell me about|when (?:is|was|did)|where (?:is|was)|how (?:tall|old|big|far|long) (?:is|was))\s+(.{2,80}?)\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    /** Questions about us or the chat ("what's your favorite...", "what is my name") aren't lookups. */
    private val personalRe = Regex("""\b(you|your|yours|yourself|me|my|mine|i|we|our|us|it|that|this|up|going on|wrong|new|the matter|the time|today|tonight|tomorrow|happening|for dinner)\b""", RegexOption.IGNORE_CASE)

    fun detect(msg: String): Intent? {
        val m = msg.trim()
        if (m.length > 300) return null
        if (weatherRe.containsMatchIn(m)) {
            val city = cityRe.find(m)?.groupValues?.get(1)?.trim()?.takeIf { c ->
                c.split(' ').size <= 4 && !personalRe.matches(c) && c.lowercase() !in setOf("general", "the morning", "the evening", "the afternoon", "all", "home", "work", "school", "the moment", "the weekend", "night", "noon", "lunch", "this weekend", "this week", "the week", "today", "tomorrow", "tonight")
            }
            return Intent.Weather(city, Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE).containsMatchIn(m))
        }
        if (newsRe.containsMatchIn(m)) return Intent.News(newsTopicRe.find(m)?.groupValues?.get(1)?.trim())
        explicitRe.find(m)?.let { return Intent.Lookup(it.groupValues[1].trim()) }
        askRe.find(m)?.let {
            val q = it.groupValues[1].trim()
            if (!personalRe.containsMatchIn(q) && q.split(' ').size <= 8) return Intent.Lookup(q)
        }
        return null
    }

    /** A short reply to "which city?" ("Nashville", "I'm in Nashville TN"). */
    fun cityFromReply(msg: String): String? {
        val m = msg.trim().trimEnd('.', '!', '?')
        val c = Regex("""^(?:i'?m in|i am in|i live in|in|it'?s)\s+(.+)$""", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1) ?: m
        return c.trim().takeIf { it.length in 2..40 && it.split(' ').size <= 4 && it.all { ch -> ch.isLetter() || ch in " .,'-" } }
    }

    private fun today(): String = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText().take(400_000) }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n - 3).trimEnd() + "..."
    private fun note(source: String, body: String) = clip("Online result - $source, ${today()}: $body", MAX_NOTE)

    /**
     * Runs the lookup (network, call off the main thread). [city] = remembered city; [coords] = approximate location
     * if the user allowed it. Never throws: no network gives a note telling the companion to say so briefly.
     */
    fun run(intent: Intent, city: String?, coords: Pair<Double, Double>?): Result = try {
        when (intent) {
            is Intent.Weather -> weather(intent, city, coords)
            is Intent.News -> news(intent.topic)
            is Intent.Lookup -> lookup(intent.query)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "lookup failed: ${e.javaClass.simpleName}: ${e.message}")
        Result("Online lookup failed (no connection or the service didn't answer). Briefly tell the user you couldn't get online just now, then help as best you can without it.",
            "offline", ok = false)
    }

    private fun weather(w: Intent.Weather, saved: String?, coords: Pair<Double, Double>?): Result {
        val wanted = w.city ?: saved
        var lat: Double; var lon: Double; var place: String
        if (wanted != null) {
            val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(wanted.substringBefore(','))}&count=1&language=en&format=json"))
            val r = g.optJSONArray("results")?.optJSONObject(0)
                ?: return Result("The user asked about the weather in \"$wanted\", but that place wasn't found. Ask them to say the city name again.", "Open-Meteo", ok = false, needsCity = true)
            lat = r.getDouble("latitude"); lon = r.getDouble("longitude")
            place = listOf(r.optString("name"), r.optString("admin1"), r.optString("country_code")).filter { it.isNotBlank() }.joinToString(", ")
        } else if (coords != null) {
            lat = coords.first; lon = coords.second; place = "your area"
        } else {
            return Result("The user asked about the weather but you don't know their city yet. Ask which city they're in (you'll remember it).", "", ok = true, needsCity = true)
        }
        val us = Locale.getDefault().country in setOf("US", "LR", "MM")
        val unit = if (us) "°F" else "°C"
        val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            (if (us) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "") + "&timezone=auto&forecast_days=2"))
        val cur = j.getJSONObject("current"); val d = j.getJSONObject("daily")
        fun day(i: Int) = "${wmo(d.getJSONArray("weather_code").optInt(i))}, high ${d.getJSONArray("temperature_2m_max").optDouble(i).toInt()}$unit, " +
            "low ${d.getJSONArray("temperature_2m_min").optDouble(i).toInt()}$unit, ${d.getJSONArray("precipitation_probability_max").optInt(i)}% chance of rain"
        val body = "$place: now ${cur.optDouble("temperature_2m").toInt()}$unit (feels like ${cur.optDouble("apparent_temperature").toInt()}$unit), " +
            "${wmo(cur.optInt("weather_code"))}, wind ${cur.optDouble("wind_speed_10m").toInt()} ${if (us) "mph" else "km/h"}. " +
            "Today: ${day(0)}. Tomorrow: ${day(1)}."
        return Result(note("Open-Meteo", body), "Open-Meteo", ok = true, city = wanted?.takeIf { w.city != null || saved == null })
    }

    private fun wmo(c: Int) = when (c) {
        0 -> "clear"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzle"; 61, 63, 65, 66, 67 -> "rain"; 71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"; 85, 86 -> "snow showers"; 95, 96, 99 -> "thunderstorms"; else -> "mixed"
    }

    private fun news(topic: String?): Result {
        val (src, url) = if (topic == null) "BBC News" to "https://feeds.bbci.co.uk/news/rss.xml"
            else "Google News" to "https://news.google.com/rss/search?q=${enc(topic)}&hl=en-US&gl=US&ceid=US:en"
        val titles = rssTitles(get(url), 5)
        if (titles.isEmpty()) return Result("No headlines came back${if (topic != null) " for \"$topic\"" else ""}. Tell the user briefly.", src, ok = false)
        val body = (if (topic != null) "Headlines about $topic: " else "Top headlines: ") + titles.joinToString(" | ")
        return Result(note(src, body) + " Mention two or three of these in your own words.", src, ok = true)
    }

    private fun rssTitles(xml: String, max: Int): List<String> {
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        val out = ArrayList<String>()
        var inItem = false
        while (p.next() != XmlPullParser.END_DOCUMENT && out.size < max) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "item") inItem = true
            if (p.eventType == XmlPullParser.START_TAG && p.name == "title" && inItem)
                p.nextText().trim().takeIf { it.isNotEmpty() }?.let { out += clip(it.replace(Regex("\\s+"), " "), 110) }
        }
        return out
    }

    private fun lookup(query: String): Result {
        // Wikipedia: best page for the words, then its summary.
        val s = JSONObject(get("https://en.wikipedia.org/w/rest.php/v1/search/page?q=${enc(query)}&limit=1"))
        val key = s.optJSONArray("pages")?.optJSONObject(0)?.optString("key").orEmpty()
        if (key.isNotEmpty()) {
            val sum = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(key).replace("+", "%20")}"))
            val text = sum.optString("extract").trim()
            if (text.isNotEmpty() && sum.optString("type") != "disambiguation")
                return Result(note("Wikipedia (${sum.optString("title")})", text), "Wikipedia", ok = true)
        }
        val d = JSONObject(get("https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"))
        val ans = d.optString("Answer").ifBlank { d.optString("AbstractText") }.ifBlank { d.optString("Definition") }.trim()
        if (ans.isNotEmpty()) return Result(note("DuckDuckGo", ans), "DuckDuckGo", ok = true)
        return Result("An online lookup for \"$query\" found nothing useful. Say so briefly and answer from what you know, saying you're not sure.", "Wikipedia", ok = false)
    }
}
