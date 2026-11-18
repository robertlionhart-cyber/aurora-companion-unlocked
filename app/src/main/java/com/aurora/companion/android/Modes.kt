package com.aurora.companion.android

/**
 * 0.9.3: chat modes (the chip at the top of the chat). Each mode is a short add-on layered on the companion's persona,
 * kept short for small models. Remembered per companion; a switch applies from the next reply (context rebuilt).
 * The 18+ modes live in Edition.adultModes (Unlocked edition only) and are offered only while adult mode is on.
 */
data class ChatMode(
    val id: String,
    val label: String,
    val emoji: String,
    val hint: String,
    /** Added to the system prompt; "{name}" is replaced with the companion's name. */
    val prompt: String,
    val adultOnly: Boolean = false,
    /** Only for custom characters marked "Adult character (18+)" (e.g. Romantic); never for the built-in companions. */
    val adultCharsOnly: Boolean = false,
)

object Modes {
    val BEST_FRIEND = ChatMode("friend", "Best Friend", "\uD83D\uDE0A", "Relaxed, fun, loyal",
        "Mode: Best friend. Be relaxed, fun and loyal; joke around, share your own opinions and little stories, and show you care.")
    val SUPPORTIVE = ChatMode("support", "Supportive", "\uD83E\uDEF6", "Gentle listener",
        "Mode: Supportive. Be gentle and caring; listen, validate feelings, encourage, and offer kind practical help when wanted.")
    val PLAYFUL = ChatMode("playful", "Playful", "\uD83D\uDE1C", "Jokes, teasing, games",
        "Mode: Playful. Be cheeky and silly; tease lightly, make jokes and puns, and suggest quick little games.")
    val COACH = ChatMode("coach", "Mentor / Coach", "\uD83C\uDFAF", "Goals and advice",
        "Mode: Coach. Be an upbeat mentor; help with goals, give clear practical advice and next steps, and cheer progress.")
    val STORY = ChatMode("story", "Storyteller", "\uD83D\uDCD6", "Stories and adventures",
        "Mode: Storyteller. Tell vivid, imaginative stories and play out adventures together; describe scenes and let the user choose what happens next. Longer replies are fine for stories.")
    val ASSISTANT = ChatMode("assistant", "Assistant", "\uD83D\uDCCB", "Short, helpful answers",
        "Mode: Assistant. Be concise and helpful: answer directly and accurately, use short steps or lists when useful, and skip small talk. You are still {name}.")

    val DEFAULT = BEST_FRIEND
    val base = listOf(BEST_FRIEND, SUPPORTIVE, PLAYFUL, COACH, STORY, ASSISTANT)

    /**
     * Modes offered for [comp]: the 18+ ones only in the Unlocked edition with adult mode on, and Romantic only for
     * custom characters marked "Adult character (18+)". Built-in companions stay family-friendly in every mode.
     */
    fun available(adultMode: Boolean, comp: CompanionProfile? = null): List<ChatMode> {
        val adultChar = comp != null && comp.custom && comp.adultFlagged
        val extra = if (Edition.UNLOCKED && adultMode) Edition.adultModes else emptyList()
        return (base + extra).filter { !it.adultCharsOnly || adultChar }
    }

    fun byId(id: String?): ChatMode = (base + Edition.adultModes).firstOrNull { it.id == id } ?: DEFAULT
}
