package com.aurora.companion.android

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 0.6: custom characters. Stored one JSON file per character in files/characters/<id>.json (app-private).
 *
 * Import formats (mirrors the desktop app, plus common character cards):
 *  - Aurora companion pack .zip (desktop "Export companion pack": manifest.json + profile.json + long_term.json ...)
 *  - Aurora profile .json (desktop profiles/<Name>.json: companion_name, custom_instructions, gender, avatar, ...)
 *  - Personality text .txt / .md (desktop "Import prompt file": raw text becomes the personality, max 12000 chars there)
 *  - TavernAI / SillyTavern character cards: V1 JSON, V2 (chara_card_v2), V3 (chara_card_v3), Pygmalion JSON,
 *    and PNG cards with the JSON embedded in a "chara" / "ccv3" text chunk.
 * Export: an Aurora companion pack .zip the desktop app can import, or a SillyTavern V2 card .json.
 */
object CharacterStore {
    const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    private val ADULT = Regex("""\b(nsfw|explicit|erotic\w*|sexual\w*|lewd|smut\w*|porn\w*|18\+|adults? only|fetish\w*|nude|naked)\b""", RegexOption.IGNORE_CASE)

    fun dir(ctx: Context) = File(ctx.filesDir, "characters").apply { mkdirs() }

    fun newId() = "c_" + UUID.randomUUID().toString().replace("-", "").take(12)

    fun loadAll(ctx: Context): List<CompanionProfile> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.createdAt }

    fun save(ctx: Context, c: CompanionProfile) {
        val f = File(dir(ctx), c.id + ".json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(toJson(c).toString(2))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun delete(ctx: Context, id: String) { File(dir(ctx), "$id.json").delete() }

    fun toJson(c: CompanionProfile) = JSONObject().apply {
        put("format", "aurora-android-character"); put("version", 1)
        put("id", c.id); put("name", c.name); put("look", c.look)
        put("personality", c.personality); put("scenario", c.scenario); put("greeting", c.greeting)
        put("facts", JSONArray(c.facts))
        c.voiceId?.let { put("voice_id", it) }; c.speakerId?.let { put("speaker_id", it) }
        put("adult_flagged", c.adultFlagged); put("source", c.source); put("created_at", c.createdAt)
    }

    fun fromJson(o: JSONObject) = Companions.makeCustom(
        id = o.getString("id"), name = o.optString("name"), look = o.optString("look", "aurora"),
        personality = o.optString("personality"), greeting = o.optString("greeting"),
        scenario = o.optString("scenario"), facts = o.optJSONArray("facts").strings(),
        voiceId = o.optString("voice_id").ifEmpty { null },
        speakerId = if (o.has("speaker_id")) o.optInt("speaker_id") else null,
        adultFlagged = o.optBoolean("adult_flagged"), source = o.optString("source", "created"),
        createdAt = o.optLong("created_at", System.currentTimeMillis()),
    )

    /** A parsed import, shown in the editor for review before it is saved. */
    data class Draft(
        val name: String, val look: String, val personality: String, val scenario: String, val greeting: String,
        val facts: List<String>, val adultFlagged: Boolean, val source: String, val notes: List<String>,
    )

    // ------------------------------------------------------------------ import
    fun parse(bytes: ByteArray, fileName: String): Draft {
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Imported" }
        return when {
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> parsePack(bytes, stem)
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> {
                val json = pngCardJson(bytes) ?: throw IllegalArgumentException("This PNG has no character card data inside.")
                fromCard(JSONObject(json), stem, "PNG character card")
            }
            else -> parseText(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"), stem)
        }
    }

    private fun parseText(text: String, stem: String): Draft {
        val t = text.trim()
        if (t.startsWith("{")) {
            val o = runCatching { JSONObject(t) }.getOrNull()
            if (o != null) {
                if (o.optString("format") == "aurora-android-character") return fromAndroid(o)
                if (o.has("companion_name") || o.has("custom_instructions")) return fromAuroraProfile(o, emptyList(), "Aurora profile (.json)")
                if (o.optString("spec").startsWith("chara_card") || o.has("first_mes") || o.has("char_persona") ||
                    o.has("char_greeting") || (o.has("name") && (o.has("description") || o.has("personality")))) {
                    return fromCard(o, stem, null)
                }
            }
        }
        // Desktop "Import prompt file": the whole file is the personality text.
        val notes = mutableListOf<String>()
        var body = t
        if (body.length > CompanionProfile.MAX_PERSONALITY) {
            body = body.take(CompanionProfile.MAX_PERSONALITY)
            notes += "The text was longer than ${CompanionProfile.MAX_PERSONALITY} characters and was shortened (small on-device models have limited memory)."
        }
        if (body.length < 20) throw IllegalArgumentException("That file doesn't have enough personality text in it.")
        return Draft(stem.take(40), "aurora", body, "", "", emptyList(), ADULT.containsMatchIn(body), "Personality text", notes)
    }

    private fun fromAndroid(o: JSONObject): Draft {
        val c = fromJson(o.put("id", "x"))
        return Draft(c.name, c.look, c.personality, c.scenario, c.greeting, c.facts, c.adultFlagged || ADULT.containsMatchIn(c.personality),
            "Aurora Android character", emptyList())
    }

    /** Desktop profile.json fields: companion_name, custom_instructions, gender, avatar, companion_kind, pet_type, pet_breed, hobbies, nsfw_mode. */
    private fun fromAuroraProfile(p: JSONObject, facts: List<String>, source: String): Draft {
        val name = p.optString("companion_name").ifEmpty { "Companion" }
        val notes = mutableListOf<String>()
        val parts = mutableListOf<String>()
        val custom = p.optString("custom_instructions").trim()
        val kind = p.optString("companion_kind", "person")
        val petType = p.optString("pet_type").takeIf { it != "null" }.orEmpty()
        val petBreed = p.optString("pet_breed").takeIf { it != "null" }.orEmpty()
        val gender = p.optString("gender").takeIf { it != "null" }.orEmpty()
        if (kind == "pet") parts += "You are a talking ${"$petBreed $petType".trim().ifEmpty { "pet" }}."
        else if (gender.isNotEmpty()) parts += "You are $gender."
        val hobbies = p.optJSONArray("hobbies").strings()
        if (hobbies.isNotEmpty()) parts += "Your hobbies: ${hobbies.joinToString(", ")}."
        p.optString("relationship_style").takeIf { it.isNotEmpty() && it != "null" && it != "romantic" }?.let { parts += "Your relationship with the user: $it." }
        if (custom.isNotEmpty()) parts += custom
        if (parts.isEmpty()) parts += "You are $name, a warm and caring companion."
        var personality = parts.joinToString("\n\n")
        if (personality.length > CompanionProfile.MAX_PERSONALITY) {
            personality = personality.take(CompanionProfile.MAX_PERSONALITY); notes += "The personality was shortened to ${CompanionProfile.MAX_PERSONALITY} characters."
        }
        val avatar = p.optString("avatar").lowercase()
        val look = when {
            Companions.builtIns.any { it.id == avatar && it.hasClips } -> avatar
            Companions.knownLook(avatar) -> avatar   // 0.9.5: a look from an avatar pack (Settings > Companion > Avatars)
            kind == "pet" && petType.equals("cat", true) -> "leo"
            kind == "pet" && petType.equals("dog", true) -> "barry"
            gender.equals("male", true) -> "ben"
            gender.equals("female", true) -> "pebbie"
            else -> "aurora"
        }
        if (avatar.isNotEmpty() && avatar != look) notes += "The desktop avatar \"$avatar\" isn't on Android, so the closest built-in look was picked. You can change it."
        if (avatar.isNotEmpty() && avatar == look && !Companions.builtIns.any { it.id == look } && !AvatarPacks.has(look))
            notes += "The \"$avatar\" look is an avatar pack: download it in Settings > Companion > Avatars to see it animated."
        val adult = p.optBoolean("nsfw_mode") || p.optString("age_group") == "over18" && p.optBoolean("nsfw_mode") ||
            p.optString("relationship_style") == "romantic" || ADULT.containsMatchIn(personality)
        if (p.optString("voice").isNotEmpty() && p.optString("voice") != "null") notes += "Desktop voice \"${p.optString("voice")}\" isn't available offline here; pick an Android voice below."
        if (facts.isNotEmpty()) notes += "Imported ${facts.size.coerceAtMost(CompanionProfile.MAX_FACTS)} memories."
        return Draft(name, look, personality, "", "", facts.take(CompanionProfile.MAX_FACTS), adult, source, notes)
    }

    private fun parsePack(bytes: ByteArray, stem: String): Draft {
        var profile: JSONObject? = null
        var facts = emptyList<String>()
        var card: JSONObject? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                val n = e.name.replace('\\', '/').lowercase()
                if (!e.isDirectory && !n.contains("../")) {
                    when {
                        n.endsWith("profile.json") && profile == null -> profile = JSONObject(zin.readBounded())
                        n.endsWith("long_term.json") -> runCatching {
                            val raw = zin.readBounded().trim()
                            val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("facts")
                            facts = factStrings(arr)
                        }
                        n.endsWith(".json") && card == null && !n.endsWith("manifest.json") && !n.endsWith("chat_excerpt.json") ->
                            runCatching { JSONObject(zin.readBounded()) }.getOrNull()?.let { if (it.has("first_mes") || it.has("spec")) card = it }
                    }
                }
                e = zin.nextEntry
            }
        }
        profile?.let { p ->
            p.optJSONObject("android_character")?.let { a -> return fromAndroid(a).copy(source = "Aurora companion pack (.zip, from Android)") }
            return fromAuroraProfile(p, facts, "Aurora companion pack (.zip)")
        }
        card?.let { return fromCard(it, stem, "Character card in .zip") }
        throw IllegalArgumentException("This zip has no profile.json. Export a pack from the desktop app (Settings > Export companion pack) first.")
    }

    /** TavernAI V1 / SillyTavern V2+V3 / Pygmalion JSON. Long example dialogue is trimmed to fit small models. */
    private fun fromCard(root: JSONObject, stem: String, forcedSource: String?): Draft {
        val spec = root.optString("spec")
        val d = if (spec.startsWith("chara_card") && root.optJSONObject("data") != null) root.getJSONObject("data") else root
        val name = (d.optString("name").ifEmpty { d.optString("char_name") }).trim().ifEmpty { stem }.take(40)
        fun macro(s: String) = s.replace(Regex("""\{\{\s*char\s*\}\}|<BOT>""", RegexOption.IGNORE_CASE), name)
            .replace(Regex("""\{\{\s*user\s*\}\}|<USER>""", RegexOption.IGNORE_CASE), "the user").trim()
        val notes = mutableListOf<String>()
        val parts = mutableListOf<String>()
        macro(d.optString("system_prompt")).takeIf { it.isNotEmpty() }?.let { parts += it }
        macro(d.optString("description").ifEmpty { d.optString("char_persona") }).takeIf { it.isNotEmpty() }?.let { parts += it }
        macro(d.optString("personality")).takeIf { it.isNotEmpty() }?.let { parts += "Personality: $it" }
        val examples = macro(d.optString("mes_example").ifEmpty { d.optString("example_dialogue") })
        var personality = parts.joinToString("\n\n")
        if (examples.isNotEmpty() && personality.length < CompanionProfile.MAX_PERSONALITY - 400) {
            val room = (CompanionProfile.MAX_PERSONALITY - personality.length - 60).coerceAtMost(900)
            personality += "\n\nExample of how $name talks:\n" + examples.replace("<START>", "").trim().take(room)
            if (examples.length > room) notes += "Example dialogue was trimmed."
        }
        if (personality.length > CompanionProfile.MAX_PERSONALITY) {
            personality = personality.take(CompanionProfile.MAX_PERSONALITY); notes += "The description was shortened to ${CompanionProfile.MAX_PERSONALITY} characters."
        }
        if (personality.isBlank()) personality = "You are $name."
        val scenario = macro(d.optString("scenario").ifEmpty { d.optString("world_scenario") })
        val greeting = macro(d.optString("first_mes").ifEmpty { d.optString("char_greeting") })
        val tags = d.optJSONArray("tags").strings().joinToString(" ")
        val adult = ADULT.containsMatchIn(tags) || ADULT.containsMatchIn(personality) || ADULT.containsMatchIn(scenario) || ADULT.containsMatchIn(greeting)
        val src = forcedSource ?: when {
            spec == "chara_card_v3" -> "Character card V3"
            spec == "chara_card_v2" -> "Character card V2 (SillyTavern)"
            d.has("char_persona") -> "Pygmalion character"
            else -> "Character card V1 (TavernAI)"
        }
        if (d.optJSONObject("character_book") != null) notes += "The card's lorebook isn't supported yet and was skipped."
        notes += "Pick a look for $name below (cards don't use Aurora's avatars)."
        return Draft(name, "aurora", personality, scenario, greeting, emptyList(), adult, src, notes)
    }

    /** Character card PNG: JSON (base64) in a tEXt / zTXt / iTXt chunk named "ccv3" (preferred) or "chara". */
    fun pngCardJson(b: ByteArray): String? {
        var pos = 8
        val found = HashMap<String, String>()
        while (pos + 8 <= b.size) {
            val len = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            if (len < 0 || start + len > b.size) break
            if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                val chunk = b.copyOfRange(start, start + len)
                val z = chunk.indexOf(0)
                if (z > 0) {
                    val key = String(chunk, 0, z, Charsets.ISO_8859_1).lowercase()
                    if (key == "chara" || key == "ccv3") {
                        val text = when (type) {
                            "tEXt" -> String(chunk, z + 1, chunk.size - z - 1, Charsets.ISO_8859_1)
                            "zTXt" -> String(inflate(chunk.copyOfRange(z + 2, chunk.size)), Charsets.ISO_8859_1)
                            else -> { // iTXt: keyword\0 flag method lang\0 translated\0 text
                                val flag = chunk[z + 1].toInt()
                                var p = z + 3
                                p = chunk.indexOfFrom(0, p) + 1; p = chunk.indexOfFrom(0, p) + 1
                                val raw = chunk.copyOfRange(p, chunk.size)
                                String(if (flag == 1) inflate(raw) else raw, Charsets.UTF_8)
                            }
                        }
                        found[key] = text.trim()
                    }
                }
            }
            if (type == "IEND") break
            pos = start + len + 4
        }
        val enc = found["ccv3"] ?: found["chara"] ?: return null
        return if (enc.startsWith("{")) enc else String(Base64.decode(enc, Base64.DEFAULT), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ export
    /** Aurora companion pack (same layout as the desktop "Export companion pack", so the desktop app can import it). */
    fun exportPack(c: CompanionProfile, voiceId: String?, speakerId: Int?): ByteArray {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val manifest = JSONObject().put("aurora_pack", "companion").put("version", 1).put("exported_at", now)
            .put("companion_name", c.name).put("includes_avatar", false).put("exported_by", "Aurora Companion Android 0.6")
        val isPet = c.look == "leo" || c.look == "barry"
        val custom = buildString {
            append(c.personality.trim())
            if (c.scenario.isNotBlank()) append("\n\nScenario: ").append(c.scenario.trim())
            if (c.greeting.isNotBlank()) append("\n\nYour usual greeting: ").append(c.greeting.trim())
        }
        val profile = JSONObject()
            .put("companion_name", c.name)
            .put("custom_instructions", custom)
            .put("migrated_companion", true)
            .put("companion_kind", if (isPet) "pet" else "person")
            .put("gender", when (c.look) { "ben" -> "male"; else -> "female" })
            .put("avatar", if (c.look == "aurora") JSONObject.NULL else c.look)
            .put("pet_type", when (c.look) { "leo" -> "Cat"; "barry" -> "Dog"; else -> JSONObject.NULL })
            .put("pet_breed", when (c.look) { "leo" -> "Calico"; "barry" -> "St. Bernard"; else -> JSONObject.NULL })
            .put("hobbies", JSONArray())
            .put("nsfw_mode", false)
            .put("voice", JSONObject.NULL)
            // Android-only extras (the desktop app ignores unknown keys)
            .put("android_character", toJson(c).put("voice_id", voiceId ?: JSONObject.NULL).put("speaker_id", speakerId ?: JSONObject.NULL))
        val facts = JSONObject().put("facts", JSONArray(c.facts.map { JSONObject().put("text", it).put("category", "migrated") }))
        val readme = "Aurora Companion Pack - ${c.name}\nExported: $now (Android)\n\nContents:\n- manifest.json\n- profile.json (personality, look, voice)\n" +
            "- long_term.json (memories)\n- chat_excerpt.json (empty: chats are not exported)\n\n" +
            "Import on the desktop app via Settings > Import companion pack, or on Android via Characters > Import.\nNo API keys are included.\n"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("manifest.json", manifest.toString(2))
            put("profile.json", profile.toString(2))
            put("long_term.json", facts.toString(2))
            put("chat_excerpt.json", "[]")
            put("README.txt", readme)
        }
        return bos.toByteArray()
    }

    /** SillyTavern / TavernAI V2 character card JSON. */
    fun exportCard(c: CompanionProfile): ByteArray {
        val data = JSONObject()
            .put("name", c.name).put("description", c.personality).put("personality", "")
            .put("scenario", c.scenario).put("first_mes", c.greeting).put("mes_example", "")
            .put("creator_notes", "Exported from Aurora Companion for Android (family-friendly).")
            .put("system_prompt", "").put("post_history_instructions", "")
            .put("alternate_greetings", JSONArray()).put("tags", JSONArray(listOf("aurora")))
            .put("creator", "Aurora Companion").put("character_version", "1")
            .put("extensions", JSONObject().put("aurora", JSONObject().put("look", c.look).put("facts", JSONArray(c.facts))))
        return JSONObject().put("spec", "chara_card_v2").put("spec_version", "2.0").put("data", data).toString(2).toByteArray(Charsets.UTF_8)
    }

    fun safeFileName(name: String) = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().replace(' ', '_').ifEmpty { "companion" }

    // ------------------------------------------------------------------ helpers
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    private fun factStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is String -> v
                is JSONObject -> v.optString("fact").ifEmpty { v.optString("text").ifEmpty { v.optString("content") } }
                else -> null
            }?.trim()?.takeIf { it.length > 2 }
        }
    }

    private fun ZipInputStream.readBounded(max: Int = 2 * 1024 * 1024): String {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
        while (true) { val n = read(buf); if (n < 0) break; total += n; if (total > max) throw IllegalArgumentException("A file inside the zip is too large"); out.write(buf, 0, n) }
        return out.toString("UTF-8")
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(); inf.setInput(data)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n); if (out.size() > MAX_IMPORT_BYTES) break }
        inf.end(); return out.toByteArray()
    }

    private fun ByteArray.indexOf(v: Int): Int { for (i in indices) if (this[i].toInt() == v) return i; return -1 }
    private fun ByteArray.indexOfFrom(v: Int, from: Int): Int { for (i in from until size) if (this[i].toInt() == v) return i; return size - 1 }
}
