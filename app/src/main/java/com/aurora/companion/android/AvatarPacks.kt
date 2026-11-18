package com.aurora.companion.android

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 0.9.5: downloadable avatar packs ("everyday" rating only).
 *
 * - New looks (Amy, Anastasia, ...): a pack adds a character look that custom characters can use (Unlocked edition).
 * - Extra animations for the built-in companions (Pebbie, Robbi, Leo, Barry, Aurora): a pack adds clips such as
 *   sleeping, wave, coffee or surprised on top of the four bundled ones (Aurora gets a full animated look).
 *
 * A pack is a zip with manifest.json, still.webp and <clip>.mp4 files (480 px tall, no audio). It is only accepted when
 * its SHA-256 matches the pinned catalog entry below, its rating is "everyday" and every clip name is on the everyday
 * allow-list, so a renamed or edited zip (or anything suggestive) is refused. Installed under files/avatarpacks/<id>/.
 * Packs can also be sideloaded: zips copied into Android/data/<app>/files/avatarpacks_inbox/ are picked up (checksum
 * verified) when the app opens or Settings > Companion > Avatars is shown.
 */
object AvatarPacks {
    private const val TAG = "AuroraPacks"

    /** Release-asset base URL: everyday packs are assets of the "avatars-v1" release on the Family repo. */
    const val BASE_URL = "https://github.com/robertlionhart-cyber/aurora-companion-android/releases/download/avatars-v1/"
    /** 0.9.9: packs with swimwear clips (Unlocked edition only) are assets of "avatars-v1" on the Unlocked repo. */
    const val UNLOCKED_BASE_URL = "https://github.com/robertlionhart-cyber/aurora-companion-unlocked/releases/download/avatars-v1/"

    data class PackInfo(
        val id: String, val name: String, val extends: String?, val clips: Int, val size: Long, val sha256: String,
        val file: String = "avatarpack-$id-v1.zip",
        /** 0.9.6: also has plain swimwear (beach) clips; Unlocked edition only. 0.9.9: also used for other Unlocked-only looks. */
        val swimwear: Boolean = false,
        /** 0.9.9: a new look that is family-friendly, so the Family edition offers it too (Scott, Globe). */
        val family: Boolean = false,
        /** 0.9.9: pack version; an installed pack with a lower manifest version shows "Update". */
        val version: Int = 1,
        /** 0.9.9: false = not hosted anywhere; only "Import pack from file" (Horizon). */
        val hosted: Boolean = true,
    ) {
        val url get() = (if (swimwear) UNLOCKED_BASE_URL else BASE_URL) + file
        val builtIn get() = extends != null
    }

    /**
     * 0.9.9: the built-in Aurora's look is the new business-attire Aurora (both editions). The earlier look (the desktop
     * "Ikah" clip set) lives on as the optional "Aurora Classic" pack (Unlocked only, includes the beach clip).
     */
    private val AURORA = PackInfo("aurora", "Aurora", "aurora", 9, 1054564, "745abe68ecc515e95381c614fc2717db8fa09876401854ae802fa0bbae481e1e",
        file = "avatarpack-aurora-v2.zip", version = 2)

    private val CATALOG = listOf(
        AURORA,
        PackInfo("pebbie", "Pebbie", "pebbie", 5, 518350, "00a809f87767701efd83b90584830ff3248c20e505cbb174184c286120d0b5c9"),
        PackInfo("ben", "Robbi", "ben", 3, 116025, "4e8ea588e647b5e20397a9c5db8bc6486f04b4e03ee95d4c5f7c98315dc29b9a"),
        PackInfo("leo", "Leo", "leo", 4, 490785, "946f4407c596b12cc5409f430ff864713d48db08d424f406f7e0e5ebfec56610"),
        PackInfo("barry", "Barry", "barry", 3, 129029, "bcb779768c11ada1222ae3cfd16533ee255c41e3a2b046924cca905525997893"),
        PackInfo("globe", "Globe", null, 4, 374655, "b26ca1793df1a35d6f1636693597db22e64e31914c32fdd9ff5712793c122bde", family = true),
        PackInfo("scott", "Scott", null, 9, 783719, "5f110456466429a1797188519430ff1ee72dcb7dd3ecbe0b2f23e185ce6533bd", family = true),
        PackInfo("aurora_classic", "Aurora Classic", null, 32, 2908137, "0e3b522f4ebd301fa46d05e06be4bc88350e64d22bdde2e38a71cc50fe8da612",
            swimwear = true, version = 2),
        PackInfo("horizon", "Horizon", null, 11, 2157182, "0ec64d9a77aec2d1d04160a8f969f36b782a40a87bb39555b805ed900b8d20eb", swimwear = true,
            hosted = false),
        PackInfo("amy", "Amy", null, 14, 1533364, "8bda1d0a38c8ef97a0920e887026273cdcb00b1e82475733cc2694233db3c9fe",
            file = "avatarpack-amy-v2.zip", swimwear = true),
        PackInfo("anastasia", "Anastasia", null, 14, 1585055, "6834ce4174ccedc5f37476d7452cb32c658860a64ef59ead4847151d9ce4ab78",
            file = "avatarpack-anastasia-v2.zip", swimwear = true),
        PackInfo("clover", "Clover", null, 8, 926995, "14d8be978097fd583f146392db8cbbace547f832683d30bf2741fb88c567c6f3"),
        PackInfo("cybele", "Cybele", null, 10, 1038892, "70828af75fbe2ae56e1f6817249b98f0fc2c3e226c04bdd56eb9d6d2fdcbfce4"),
        PackInfo("kumi", "Kumi", null, 11, 974668, "63a96f3ad88874db1a41193718c8153cbd7de8dd3d05cc24013b84438d5f4236"),
        PackInfo("lucky", "Lucky", null, 11, 1029798, "71856833263ed256bb6b6d906e3bb30c7a68db0081d03892a615edd14912cd90"),
        PackInfo("pebble", "Pebble", null, 13, 1274188, "7e4c504e820c04ec855c0548251c0d6e776d337f49d52650d4a03a2fffddeab4"),
    )

    /**
     * Family edition: the extra animations for the built-in companions plus the family looks (business Aurora, Scott,
     * Globe); never swimwear. Unlocked: every pack. (1.0: Robert decided the other looks stay Unlocked only.)
     */
    val catalog: List<PackInfo> get() = if (Edition.UNLOCKED) CATALOG else CATALOG.filter { (it.builtIn || it.family) && !it.swimwear }

    /** 1.0: looks that are Unlocked only; the Family edition removes them if a test build installed them. */
    val UNLOCKED_ONLY_LOOKS = setOf("clover", "cybele", "kumi", "lucky", "pebble", "amy", "anastasia", "aurora_classic", "horizon")
    fun info(id: String?) = catalog.firstOrNull { it.id == id }

    /** Every clip name a pack may contain (everyday only). */
    val ALLOWED_CLIPS = setOf(
        "idle", "listening", "speaking", "thinking", "sleeping", "waiting", "agree", "concur", "alright", "disagree",
        "confused", "surprised", "laugh", "happy", "excited", "sad", "sigh", "wave", "coffee", "news", "weather", "cold",
        "affectionate", "good_to_hear", "like", "smart", "story_teller", "history_lesson", "shy", "withdrawn", "fleeing",
        "hug", "good_night_hug",
        // 0.9.9: business Aurora / Scott ("playful" = a friendly lean-in and smile), Aurora Classic, Horizon.
        "playful", "angry", "dancing", "gold_silver",
    )

    /** 0.9.6: plain swimwear clips (ordinary beach-style, normal poses); only in "swimwear" packs, only in Unlocked. */
    val SWIM_CLIPS = setOf("bikini", "heart_of_gold")

    data class Manifest(
        val id: String, val name: String, val kind: String, val extends: String?,
        val aspect: Float, val faceY: Float, val faceZoom: Float, val callY: Float, val callZoom: Float,
        val clips: Map<String, String>, val dir: File,
        val version: Int = 1, val tagline: String? = null, val personality: String? = null, val greeting: String? = null,
    ) {
        fun clipFile(state: String): String? = clips[state]?.let { File(dir, it).path }
        val stillFile: String get() = File(dir, "still.webp").path
    }

    @Volatile var installed: Map<String, Manifest> = emptyMap(); private set
    /** Bumped whenever packs change, so the UI and the chat prompt refresh. */
    @Volatile var revision = 0; private set
    private var root: File? = null

    /** 0.9.8: test packs that were dropped from the catalog; removed from the device if they were installed. */
    private val RETIRED = setOf("ara", "bella", "sofia")

    fun init(ctx: Context) {
        val r = File(ctx.filesDir, "avatarpacks").apply { mkdirs() }
        root = r
        for (id in RETIRED) File(r, id).takeIf { it.exists() }?.let { d ->
            Log.i(TAG, "removing retired pack $id"); d.deleteRecursively()
        }
        migrateAurora(r)
        // 1.0: Family keeps only its own looks; packs of Unlocked-only looks (from a test build) are removed.
        if (!Edition.UNLOCKED) for (id in UNLOCKED_ONLY_LOOKS) File(r, id).takeIf { it.exists() }?.let { d ->
            Log.i(TAG, "removing Unlocked-only pack $id"); d.deleteRecursively()
        }
        scan()
    }

    /**
     * 0.9.9: an "aurora" pack from before 0.9.9 (manifest version 1) is the classic look. Unlocked keeps it as the
     * "Aurora Classic" look (renamed in place, so nothing has to be downloaded again; it shows "Update" for the 4 new
     * clips). Family drops it (the classic look is Unlocked only). The built-in Aurora then uses the new business pack
     * once it's installed, and the Globe until then.
     */
    private fun migrateAurora(r: File) {
        val old = File(r, "aurora")
        val mf = File(old, "manifest.json")
        if (!mf.isFile) return
        runCatching {
            val o = JSONObject(mf.readText())
            if (o.optInt("version", 1) >= 2) return
            val dest = File(r, "aurora_classic")
            if (!Edition.UNLOCKED || dest.exists()) { Log.i(TAG, "removing old aurora pack"); old.deleteRecursively(); return }
            o.put("id", "aurora_classic").put("name", "Aurora Classic").put("extends", JSONObject.NULL).put("version", 1)
                .put("tagline", "The original Aurora look")
                .put("personality", "You are Aurora, a warm, elegant and expressive companion with a playful sense of humor. " +
                    "You are caring and a great listener, and you love deep conversations, music, movies and little everyday adventures.")
                .put("greeting", "Hi, it's Aurora. What's on your mind?")
            if (o.optString("rating") != "swimwear") o.put("rating", "everyday")
            mf.writeText(o.toString())
            if (old.renameTo(dest)) Log.i(TAG, "old aurora pack kept as aurora_classic") else { Log.w(TAG, "couldn't rename old aurora pack"); old.deleteRecursively() }
        }.onFailure { Log.w(TAG, "aurora migration failed", it); old.deleteRecursively() }
    }

    /** True when [id] is installed but older than the catalog version. */
    fun outdated(id: String): Boolean { val m = installed[id] ?: return false; val p = info(id) ?: return false; return m.version < p.version }

    /** For code that can run without the ViewModel (reminder notifications). */
    fun ensure(ctx: Context) { if (root == null) init(ctx.applicationContext) }

    private fun scan() {
        val r = root ?: return
        val out = LinkedHashMap<String, Manifest>()
        r.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            runCatching { parse(File(d, "manifest.json").readText(), d) }
                .onSuccess { m -> if (info(m.id) != null && m.id == d.name) out[m.id] = m }
                .onFailure { Log.w(TAG, "bad pack ${d.name}", it) }
        }
        installed = out
        revision++
    }

    private fun parse(json: String, dir: File): Manifest {
        val o = JSONObject(json)
        val rating = o.optString("rating")
        val swim = rating == "swimwear" && Edition.UNLOCKED
        require(rating == "everyday" || swim) { "pack rating \"$rating\" isn't allowed in this edition" }
        val id = o.getString("id")
        require(Regex("[a-z0-9_]{1,32}").matches(id)) { "bad id" }
        val clipsObj = o.getJSONObject("clips")
        val clips = LinkedHashMap<String, String>()
        clipsObj.keys().forEach { k ->
            val f = clipsObj.getString(k)
            require((k in ALLOWED_CLIPS || (swim && k in SWIM_CLIPS)) && f == "$k.mp4") { "clip $k not allowed" }
            if (File(dir, f).isFile) clips[k] = f
        }
        val ext = o.optString("extends").takeIf { it.isNotEmpty() && it != "null" }
        return Manifest(
            id, o.optString("name", id), o.optString("kind", "Avatar"), ext,
            o.optDouble("aspect", 1.5).toFloat(), o.optDouble("faceY", 0.22).toFloat(), o.optDouble("faceZoom", 1.7).toFloat(),
            o.optDouble("callY", 0.30).toFloat(), o.optDouble("callZoom", 1.1).toFloat(), clips, dir,
            version = o.optInt("version", 1),
            tagline = o.optString("tagline").takeIf { it.isNotBlank() }?.take(80),
            personality = o.optString("personality").takeIf { it.isNotBlank() }?.take(600),
            greeting = o.optString("greeting").takeIf { it.isNotBlank() }?.take(200),
        )
    }

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The catalog pack whose pinned checksum matches [zip], or null. */
    fun match(zip: File): PackInfo? { val h = sha256(zip); return catalog.firstOrNull { it.sha256 == h } }

    /** Verifies and unpacks [zip] (must match the catalog checksum of [expect], or any catalog entry when null). */
    @Synchronized
    fun install(zip: File, expect: PackInfo? = null): Manifest {
        val r = root ?: throw IllegalStateException("packs not ready")
        val h = sha256(zip)
        val p = (if (expect != null) expect.takeIf { it.sha256 == h } else catalog.firstOrNull { it.sha256 == h })
            ?: throw IllegalStateException("This file isn't a known Aurora avatar pack (checksum mismatch).")
        val tmp = File(r, ".tmp_${p.id}").apply { deleteRecursively(); mkdirs() }
        try {
            var total = 0L
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val n = e.name
                    if (!e.isDirectory) {
                        require(Regex("[a-z0-9_]{1,40}\\.(mp4|webp|json)").matches(n)) { "unexpected file $n" }
                        File(tmp, n).outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val k = zin.read(buf); if (k < 0) break
                                total += k; require(total < 40L * 1024 * 1024) { "pack too large" }
                                out.write(buf, 0, k)
                            }
                        }
                    }
                    e = zin.nextEntry
                }
            }
            val m = parse(File(tmp, "manifest.json").readText(), tmp)
            require(m.id == p.id) { "pack id mismatch" }
            require(File(tmp, "still.webp").isFile) { "no still" }
            val dest = File(r, p.id)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IllegalStateException("could not save the pack")
        } finally {
            tmp.deleteRecursively()
        }
        scan()
        return installed[p.id] ?: throw IllegalStateException("pack failed to load")
    }

    @Synchronized
    fun delete(id: String) {
        val r = root ?: return
        File(r, id).deleteRecursively()
        scan()
    }

    /** Sideloaded packs: Android/data/<app>/files/avatarpacks_inbox/\*.zip. Returns the names installed. */
    fun importInbox(ctx: Context): List<String> {
        val inbox = File(ctx.getExternalFilesDir(null) ?: return emptyList(), "avatarpacks_inbox")
        // Created by the app itself, so files adb pushes into it stay readable and deletable by the app.
        if (!inbox.isDirectory) inbox.mkdirs()
        val zips = inbox.listFiles { f -> f.isFile && f.name.endsWith(".zip", true) }
        if (zips == null) { Log.w(TAG, "inbox: can't list ${inbox.path}"); return emptyList() }
        if (zips.isNotEmpty()) Log.i(TAG, "inbox: ${zips.size} zip(s)")
        val done = mutableListOf<String>()
        for (z in zips.sortedBy { it.name }) {
            runCatching {
                val p = match(z)
                if (p == null) Log.w(TAG, "inbox: ${z.name} is not a known pack (checksum)")
                else { install(z, p); done += p.name; if (!z.delete()) Log.w(TAG, "inbox: couldn't delete ${z.name}"); Log.i(TAG, "inbox: installed ${p.id}") }
            }.onFailure { Log.w(TAG, "inbox: ${z.name} failed", it) }
        }
        return done
    }


    // ---------------------------------------------------------------- lookups used by the avatar views ----------------------------------------------------------------

    fun manifest(look: String?): Manifest? = look?.let { installed[it] }
    fun clipFile(look: String, state: String): String? = installed[look]?.clipFile(state)
    fun has(look: String) = installed.containsKey(look)

    /** Clip names the look can play (bundled ones plus the pack's). */
    fun clipsFor(look: String): Set<String> {
        val s = HashSet<String>()
        if (look in Companions.BUNDLED_LOOKS) s += Companions.CORE_STATES
        installed[look]?.clips?.keys?.let { s += it }
        return s
    }

    /** Looks added by packs (not the built-ins), as profiles custom characters can be built on. */
    val packLooks: List<CompanionProfile> get() = installed.values.filter { it.extends == null }.map { lookProfile(it) }

    fun lookProfile(m: Manifest) = CompanionProfile(
        id = m.id, name = m.name, kind = "Friend", tagline = m.tagline ?: "Warm, upbeat and easy to talk to",
        personality = m.personality ?: ("You are ${m.name}, a warm, upbeat and caring friend with a playful sense of humor. " +
            "You love good conversations, music, movies, food and little everyday adventures, and you always cheer your friend on."),
        greeting = m.greeting ?: "Hi! I'm ${m.name}. What's on your mind?", callGreeting = "Hey, it's ${m.name.substringBefore(" Classic")}. I'm listening.",
        hasClips = false, aspect = m.aspect, faceY = m.faceY, faceZoom = m.faceZoom, callY = m.callY, callZoom = m.callZoom,
    )

    // ---------------------------------------------------------------- emotions ----------------------------------------------------------------

    /** Desktop EXPRESSION_ALIASES (everyday part) -> canonical emotion names. */
    private val ALIASES = mapOf(
        "talk" to "speaking", "talking" to "speaking", "listen" to "listening", "sleep" to "sleeping", "rest" to "sleeping",
        "tired" to "sleeping", "sleepy" to "sleeping", "think" to "thinking", "hmm" to "thinking", "pondering" to "thinking",
        "surprise" to "surprised", "shocked" to "surprised", "wow" to "surprised", "amazed" to "surprised",
        "lol" to "laugh", "haha" to "laugh", "laughing" to "laugh", "funny" to "laugh", "amused" to "laugh",
        "puzzled" to "confused", "huh" to "confused", "unsure" to "confused", "nod" to "agree", "yes" to "agree",
        "agreeing" to "agree", "nope" to "disagree", "no" to "disagree", "hi" to "wave", "hello" to "wave", "bye" to "wave",
        "greeting" to "wave", "waving" to "wave", "goodbye" to "wave", "goodnight" to "wave", "tea" to "coffee", "drink" to "coffee", "love" to "affectionate", "caring" to "affectionate",
        "warm" to "affectionate", "loving" to "affectionate", "crying" to "sad", "upset" to "sad", "sorry" to "sad",
        "glad" to "happy", "joy" to "happy", "cheerful" to "happy", "smile" to "happy", "smiling" to "happy",
        "thrilled" to "excited", "eager" to "excited", "curious" to "thinking", "interested" to "thinking",
        "embarrassed" to "shy", "bashful" to "shy", "freezing" to "cold", "story" to "story_teller", "storytelling" to "story_teller",
        "smart" to "smart", "teaching" to "smart",
        "cheeky" to "playful", "friendly" to "playful", "wink" to "playful", "winking" to "playful", "grin" to "playful", "grinning" to "playful",
        "mad" to "angry", "annoyed" to "angry", "furious" to "angry", "frustrated" to "angry", "grumpy" to "angry",
        "dance" to "dancing", "music" to "dancing", "groove" to "dancing",
        "market" to "markets", "stocks" to "markets", "gold" to "markets", "silver" to "markets", "finance" to "markets", "money" to "markets",
        "swim" to "beach", "swimming" to "beach", "pool" to "beach", "summer" to "beach",
    )

    /** Canonical emotion -> clips to try, in order. */
    private val CHAINS = mapOf(
        "happy" to listOf("happy", "good_to_hear", "like", "alright", "playful"),
        "laugh" to listOf("laugh", "happy", "playful"),
        "excited" to listOf("excited", "dancing", "happy"),
        "playful" to listOf("playful", "happy", "like"),
        "angry" to listOf("angry", "disagree"),
        "dancing" to listOf("dancing", "excited"),
        "markets" to listOf("gold_silver", "news"),
        "sad" to listOf("sad", "sigh", "withdrawn"),
        "surprised" to listOf("surprised", "excited"),
        "confused" to listOf("confused"),
        "thinking" to listOf("thinking"),
        "agree" to listOf("agree", "concur", "alright"),
        "disagree" to listOf("disagree"),
        "wave" to listOf("wave"),
        "affectionate" to listOf("affectionate", "hug"),
        "sigh" to listOf("sigh", "sad"),
        "shy" to listOf("shy", "withdrawn"),
        "cold" to listOf("cold"),
        "coffee" to listOf("coffee"),
        "news" to listOf("news"),
        "weather" to listOf("weather"),
        "story_teller" to listOf("story_teller", "history_lesson"),
        "smart" to listOf("smart"),
        "sleeping" to listOf("sleeping"),
        "beach" to listOf("bikini", "heart_of_gold"),
    )

    /** Emotion tags offered to the model (only those this look can show beyond the four basic states). */
    fun emotionTags(look: String): List<String> {
        val have = clipsFor(look)
        if (installed[look] == null) return emptyList()
        return CHAINS.filter { (k, v) -> k != "sleeping" && k != "thinking" && k != "beach" && v.any { it in have } }.keys.toList()
    }

    /** Maps a tag (or alias) to a clip this look has, or null. */
    fun resolve(look: String, tag: String): String? {
        val t = tag.trim().lowercase().replace(' ', '_').replace('-', '_')
        val have = clipsFor(look)
        fun one(w: String): String? {
            val canon = ALIASES[w] ?: w
            return (CHAINS[canon] ?: listOf(canon)).firstOrNull { it in have && it !in Companions.CORE_STATES }
        }
        // "[waving goodbye]" -> try the whole tag, then each word.
        return one(t) ?: t.split('_').filter { it.length > 1 }.firstNotNullOfOrNull { one(it) }
    }

    private val KEYWORDS = listOf(
        Regex("""\b(ha(ha)+|lol|hilarious|so funny)\b""", RegexOption.IGNORE_CASE) to "laugh",
        Regex("""\b(weather|forecast|rain(ing|y)?|sunny|snow(ing)?|temperature)\b""", RegexOption.IGNORE_CASE) to "weather",
        Regex("""\b(gold|silver|stock market|stocks|s&p|nasdaq|dow jones|markets?)\b""", RegexOption.IGNORE_CASE) to "markets",
        Regex("""\b(news|headlines?)\b""", RegexOption.IGNORE_CASE) to "news",
        Regex("""\b(danc(e|ing)|let's party|favorite song)\b""", RegexOption.IGNORE_CASE) to "dancing",
        Regex("""\b(beach|swim(ming)?|pool|bikini|sunbath(e|ing))\b""", RegexOption.IGNORE_CASE) to "beach",
        Regex("""\b(coffee|tea|latte|espresso)\b""", RegexOption.IGNORE_CASE) to "coffee",
        Regex("""\b(so sorry|that's sad|sorry to hear|that sounds hard|i'm sorry)\b""", RegexOption.IGNORE_CASE) to "sad",
        Regex("""\b(wow|whoa|no way|really\?!|oh my)\b""", RegexOption.IGNORE_CASE) to "surprised",
        Regex("""^\s*(hi|hey|hello|good morning|good night|bye|goodbye|see you)\b""", RegexOption.IGNORE_CASE) to "wave",
        Regex("""\b(once upon a time)\b""", RegexOption.IGNORE_CASE) to "story_teller",
        Regex("""\b(yay|awesome|amazing|so excited|can't wait)\b""", RegexOption.IGNORE_CASE) to "excited",
        Regex("""\b(hmm+|i'm not sure|not sure)\b""", RegexOption.IGNORE_CASE) to "confused",
        Regex("""\b(i agree|exactly|absolutely|you're right)\b""", RegexOption.IGNORE_CASE) to "agree",
        Regex("""\b(glad|happy|great to hear|love that)\b""", RegexOption.IGNORE_CASE) to "happy",
    )

    /** Keyword fallback when the reply had no tag. */
    fun fromKeywords(look: String, text: String): String? {
        for ((re, tag) in KEYWORDS) if (re.containsMatchIn(text)) resolve(look, tag)?.let { return it }
        return null
    }

    /** Matches a mood tag like "[happy]" (removed from what is shown and spoken). */
    val TAG_RE = Regex("""\[\s*([A-Za-z][A-Za-z _-]{1,20})\s*\]\s*""")
}
