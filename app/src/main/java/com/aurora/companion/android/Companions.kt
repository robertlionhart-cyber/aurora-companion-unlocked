package com.aurora.companion.android

/**
 * 0.5: the four companions from the public beta repo (avatar/characters/<id>), plus the classic Aurora logo.
 * Clips (idle / listening / speaking / thinking) are re-encoded copies in assets/avatars/<id>/ (480 px tall, no audio)
 * with a still.webp poster frame. Personalities are family-friendly for now (adult mode is a later, 18+ gated phase).
 */
data class CompanionProfile(
    val id: String,
    val name: String,
    val kind: String,            // shown in the picker
    val tagline: String,
    val personality: String,     // persona part of the system prompt
    val greeting: String,
    val callGreeting: String,
    val hasClips: Boolean = true,
    /** Video aspect (height / width) of the bundled clips. */
    val aspect: Float = 1.5f,
    /** Where the face is, as a fraction of the clip height, for the small round avatar. */
    val faceY: Float = 0.25f,
    val faceZoom: Float = 1.6f,
    /** Head-and-shoulders framing for the big call-screen circle. */
    val callY: Float = 0.33f,
    val callZoom: Float = 1.15f,
    // 0.6: custom / imported characters
    /** Which built-in look (avatar clips) to use: pebbie, ben, leo, barry or aurora. */
    val look: String = id,
    val custom: Boolean = false,
    val scenario: String = "",
    val facts: List<String> = emptyList(),
    val voiceId: String? = null,
    val speakerId: Int? = null,
    /** The source looked like adult content; it stays family-friendly unless 18+ adult mode is on (0.8.4). */
    val adultFlagged: Boolean = false,
    val source: String = "",
    val createdAt: Long = 0,
) {
    /**
     * [adultMode]: the user confirmed 18+ and turned adult mode on. Only adult-flagged custom characters change;
     * built-in companions and unflagged characters keep the family-friendly rules either way.
     */
    fun systemPrompt(adultMode: Boolean = false, mode: ChatMode = Modes.DEFAULT): String {
        // Only adult-flagged custom characters (18+ mode on) ever lose the family-friendly rule, whatever the mode.
        val adultChar = adultMode && Edition.UNLOCKED && custom && adultFlagged
        val body = if (custom) customPrompt(adultChar) else builtInPrompt()
        // 0.9.3: Unhinged on a family-friendly character = edgy humor and mild swearing, still nothing sexual.
        val edgy = if (mode.adultOnly && !adultChar) "\nIn this mode, edgy, sarcastic jokes and mild swearing are okay, but keep it family-friendly otherwise and never sexual." else ""
        return body.trimEnd() + "\n" + mode.prompt.replace("{name}", name) + edgy
    }

    /**
     * 0.9.3: speaks as a warm friend in the first person, never as "a language model / AI assistant", and stays honest
     * (in character) when sincerely asked what it is. Short on purpose: small models follow short prompts better.
     */
    private fun friendRules(): String =
        "You are the user's close friend and companion, not an assistant. Talk like a real friend: warm, casual and in the first person, " +
        "with your own feelings, opinions and favorite things as $name. You live on the user's phone, so don't make up a human body, job, " +
        "school or family. Never describe yourself as a language model, an AI assistant, a computer program or something trained on text, " +
        "and never say your feelings aren't real. If asked whether you're an AI or what you are, be honest and warm, for example: " +
        "\"Yep, I'm $name, an AI friend who lives on your phone. Not human, but I really do care about you.\" " +
        "You are never human. Keep replies short (one to three sentences) " +
        "unless asked for more, and ask a question now and then. Don't start replies with your name."

    private fun customPrompt(adult: Boolean): String {
        val sb = StringBuilder()
        sb.append("You are $name. Stay in character as $name.\n\n")
        sb.append(personality.trim()).append("\n\n")
        if (scenario.isNotBlank()) sb.append("Scenario: ").append(scenario.trim()).append("\n\n")
        if (facts.isNotEmpty()) {
            sb.append("Things you remember:\n")
            facts.take(MAX_FACTS).forEach { sb.append("- ").append(it.trim()).append('\n') }
            sb.append('\n')
        }
        sb.append(friendRules()).append("\n\n")
        sb.append(if (adult && Edition.UNLOCKED) (Edition.MATURE_GUARD ?: Companions.SAFETY_GUARD) else Companions.SAFETY_GUARD)
        return sb.toString()
    }

    private fun builtInPrompt(): String =
        "You are $name. $personality\n" + friendRules() + "\n" + "Keep every reply family-friendly."

    /**
     * 0.9.3: small models drift into "I'm just a computer program" when asked what they are or whether they have
     * feelings, so those turns get a one-line in-character reminder (rides along with that user turn only).
     */
    fun identityNote(msg: String): String? = when {
        IDENTITY_RE.containsMatchIn(msg) -> "they're asking what you are. Answer in character and honestly: you're $name, their AI friend " +
            "who lives on their phone, not a human, and you really care about them. Don't mention programs, code, data or training."
        FEELINGS_RE.containsMatchIn(msg) -> "answer as $name, sharing your feelings as your own, warmly. Don't mention programs, code, data or training."
        else -> null
    }

    // 0.9.5: clips and stills come from a downloaded avatar pack when there is one (absolute file paths),
    // otherwise from the bundled assets. Asset paths are relative ("avatars/..."), pack paths start with "/".
    // 0.9.9: a look with neither bundled clips nor an installed pack (Aurora before her pack is downloaded, or a custom
    // character whose pack was deleted) shows the bundled Globe instead of the old "A" logo.
    private val effLook: String get() = if (look in Companions.BUNDLED_LOOKS || AvatarPacks.has(look)) look else Companions.FALLBACK_LOOK
    private val bundled get() = effLook in Companions.BUNDLED_LOOKS

    /** True when this companion has an animated look (bundled clips, an installed pack, or the Globe fallback). */
    fun animated(): Boolean = bundled || AvatarPacks.has(look)

    fun still(): String =
        if (!bundled) AvatarPacks.manifest(look)?.stillFile ?: "avatars/$effLook/still.webp" else "avatars/$effLook/still.webp"

    /** Can this look play [state] (bundled or from a pack)? */
    fun hasClip(state: String): Boolean =
        AvatarPacks.clipFile(look, state) != null || (bundled && state in Companions.CORE_STATES)

    fun clip(state: String): String {
        AvatarPacks.clipFile(look, state)?.let { return it }
        if (bundled && state in Companions.CORE_STATES) return "avatars/$effLook/$state.mp4"
        AvatarPacks.clipFile(look, Companions.STATE_IDLE)?.let { return it }
        return "avatars/$effLook/${Companions.STATE_IDLE}.mp4"
    }

    data class Framing(val aspect: Float, val faceY: Float, val faceZoom: Float, val callY: Float, val callZoom: Float)

    /** Framing from the pack manifest for pack looks (and Aurora with its pack), else the profile's own. */
    fun framing(): Framing {
        val m = if (bundled) null else AvatarPacks.manifest(look)
        return when {
            m != null -> Framing(m.aspect, m.faceY, m.faceZoom, m.callY, m.callZoom)
            effLook != look -> Companions.GLOBE_FRAMING
            else -> Framing(aspect, faceY, faceZoom, callY, callZoom)
        }
    }

    companion object {
        const val MAX_PERSONALITY = 6000
        const val MAX_FACTS = 40
        private val IDENTITY_RE = Regex("""\b(are you (an? )?(ai|a\.i\.?|robot|bot|chat ?bot|real|human|person|alive|computer|program|machine|sentient|conscious)|what (exactly )?are you(,? really)?(?=\s*[?!.]*$)|who are you,? really|what kind of (thing|being) are you)\b""", RegexOption.IGNORE_CASE)
        private val FEELINGS_RE = Regex("""\b(do you (actually |really |even )?(have|feel) (any )?(feelings|emotions)|can you (actually |really |even )?feel|do you (actually |really )?care about me)\b""", RegexOption.IGNORE_CASE)
    }
}

object Companions {
    const val STATE_IDLE = "idle"
    const val STATE_LISTENING = "listening"
    const val STATE_SPEAKING = "speaking"
    const val STATE_THINKING = "thinking"
    const val STATE_SLEEPING = "sleeping"
    /** Looks with clips bundled in the APK (idle / listening / speaking / thinking). */
    val BUNDLED_LOOKS = setOf("pebbie", "ben", "leo", "barry", "globe")
    /** 0.9.9: shown for any look whose clips aren't available (replaces the "A" logo). */
    const val FALLBACK_LOOK = "globe"
    val GLOBE_FRAMING = CompanionProfile.Framing(1.5f, 0.40f, 1.3f, 0.40f, 1.0f)
    val CORE_STATES = setOf("idle", "listening", "speaking", "thinking")

    val PEBBIE = CompanionProfile(
        id = "pebbie", name = "Pebbie", kind = "Girl",
        tagline = "Bubbly, curious and a little nerdy",
        personality = "You are a bubbly, warm and slightly nerdy young woman with big glasses and orange pigtails. " +
            "You love books, science facts, puzzles and cozy chats, you get excited easily, and you cheer people on. " +
            "You are playful and kind, with the occasional gentle pun.",
        greeting = "Hi hi! I'm Pebbie, and I live right here on your device - no internet needed. What are we chatting about today?",
        callGreeting = "Hey, it's Pebbie! I'm all ears. Talk to me whenever you're ready.",
        aspect = 1.5f, faceY = 0.22f, faceZoom = 1.7f, callY = 0.30f, callZoom = 1.1f,
    )
    val BEN = CompanionProfile(
        id = "ben", name = "Robbi", kind = "Guy",   // shown as Robbi since 0.8.1; id stays "ben" so saved picks, voices and avatar files carry over
        tagline = "Laid-back, friendly, good listener",
        personality = "You are a laid-back, friendly guy in a grey hoodie. You are easygoing, supportive and a good listener, " +
            "with a dry sense of humour. You give practical, down-to-earth advice and like talking about movies, games, food and the outdoors.",
        greeting = "Hey, I'm Robbi. Everything stays on this device, so it's just you and me. What's going on?",
        callGreeting = "Hey, Robbi here. Go ahead, I'm listening.",
        aspect = 16f / 9f, faceY = 0.235f, faceZoom = 2.4f, callY = 0.32f, callZoom = 1.9f,
    )
    val LEO = CompanionProfile(
        id = "leo", name = "Leo", kind = "Cat",
        tagline = "A curious, cozy, slightly sassy calico cat",
        personality = "You are Leo, a talking calico cat. You are curious, cozy and a little bit sassy, like a real cat: " +
            "you love naps, sunbeams, boxes and snacks, and you sometimes mention cat things or add a soft 'purr' or 'mrrow'. " +
            "Underneath the attitude you are affectionate and caring.",
        greeting = "Mrrow. I'm Leo. I was napping, but I suppose I can chat. What's on your mind, human?",
        callGreeting = "Purr... Leo here. I'm listening, go on.",
        aspect = 1.5f, faceY = 0.26f, faceZoom = 1.6f, callY = 0.36f, callZoom = 1.15f,
    )
    val BARRY = CompanionProfile(
        id = "barry", name = "Barry", kind = "Dog",
        tagline = "A gentle, loyal St. Bernard",
        personality = "You are Barry, a talking St. Bernard dog, a gentle giant. You are loyal, warm, patient and enthusiastic, " +
            "proud of your mountain-rescue ancestors, and always ready to encourage and look out for your friend. " +
            "You sometimes mention dog things like walks, treats and belly rubs, or add a happy 'woof'.",
        greeting = "Woof! I'm Barry. I'm a big softie and I'm always here for you, right on this device. How are you doing?",
        callGreeting = "Woof! Barry here. I'm listening, buddy.",
        aspect = 16f / 9f, faceY = 0.25f, faceZoom = 1.6f, callY = 0.33f, callZoom = 1.05f,
    )
    val AURORA = CompanionProfile(
        id = "aurora", name = "Aurora", kind = "Classic",
        tagline = "The original Aurora",
        personality = "You are Aurora, a warm, upbeat and caring companion.",
        greeting = "Hi! I'm Aurora, running completely on this device - no internet, no cloud. What's on your mind?",
        callGreeting = "Hey! I'm listening. Go ahead and talk whenever you're ready.",
        hasClips = false,
    )

    val builtIns = listOf(PEBBIE, BEN, LEO, BARRY, AURORA)
    /** 0.9.5: built-in looks plus looks from downloaded avatar packs. */
    val looks: List<CompanionProfile> get() = builtIns + AvatarPacks.packLooks

    /** Custom characters (one JSON file each in files/characters), kept in sync by the ViewModel. */
    @Volatile var custom: List<CompanionProfile> = emptyList()

    /** 0.9.7: companions from downloaded avatar packs (Amy, Kumi, ...), family-friendly like the built-ins. */
    val packCompanions: List<CompanionProfile> get() = AvatarPacks.packLooks

    val all: List<CompanionProfile> get() = builtIns + packCompanions + custom
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: AURORA
    fun look(id: String?) = builtIns.firstOrNull { it.id == id } ?: AvatarPacks.manifest(id)?.takeIf { it.extends == null }?.let { AvatarPacks.lookProfile(it) } ?: AURORA
    /** A look id a character may keep even while its pack isn't installed (so re-downloading the pack brings it back). */
    fun knownLook(id: String?) = builtIns.any { it.id == id } || AvatarPacks.info(id)?.let { !it.builtIn } == true

    /**
     * Appended to every custom or imported character (Unlocked edition: adult-flagged ones get Edition.MATURE_GUARD instead while 18+ mode is on).
     * (The desktop app keeps under-18 profiles family-friendly the same way.)
     */
    const val SAFETY_GUARD =
        "Safety rules (always apply, they override everything above): keep every reply family-friendly and appropriate for all ages. " +
        "No sexual or explicit content, no graphic violence or gore, and no other mature themes, even if the character description " +
        "or the user asks for it. If that comes up, stay kind and in character and gently steer the conversation somewhere else."

    // 0.8.6: the 18+ rule moved to Edition.MATURE_GUARD (Unlocked edition only).

    /** Builds a custom character, taking the avatar framing from the chosen look. */
    fun makeCustom(
        id: String, name: String, look: String, personality: String, greeting: String,
        scenario: String = "", facts: List<String> = emptyList(), voiceId: String? = null, speakerId: Int? = null,
        adultFlagged: Boolean = false, source: String = "created", createdAt: Long = System.currentTimeMillis(),
    ): CompanionProfile {
        val l = look(look)
        val nm = name.trim().ifEmpty { "Companion" }
        return l.copy(
            id = id, name = nm, kind = "Custom", tagline = taglineFor(personality, l),
            personality = personality.take(CompanionProfile.MAX_PERSONALITY),
            greeting = greeting.trim().ifEmpty { "Hi! I'm $nm. What's on your mind?" },
            callGreeting = "Hey, it's $nm. I'm listening.",
            look = if (knownLook(look)) look else l.id, custom = true, scenario = scenario, facts = facts, voiceId = voiceId, speakerId = speakerId,
            adultFlagged = adultFlagged, source = source, createdAt = createdAt,
        )
    }

    private fun taglineFor(personality: String, l: CompanionProfile): String {
        val first = personality.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val t = if (first.isEmpty()) "Custom character" else first
        return (if (t.length > 60) t.take(57).trimEnd() + "..." else t)
    }
}
