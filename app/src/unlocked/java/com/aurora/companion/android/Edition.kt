package com.aurora.companion.android

/**
 * 0.8.6: the "Unlocked" edition (product flavor `unlocked`). Same app as Family, plus an optional 18+ mode that is OFF
 * by default and needs an age confirmation: mature themes for characters the user marks, one extra public-domain book,
 * and optional uncensored ("abliterated") models. This file only exists in the unlocked source set.
 */
object Edition {
    const val UNLOCKED = true
    /** Shown in Settings and matched (case-insensitively) in GitHub release asset names: AuroraCompanion-Unlocked-<ver>.apk */
    const val NAME = "Unlocked"
    /** 0.9.9: the Unlocked edition has its own GitHub repo (releases with AuroraCompanion-Unlocked-<ver>.apk). */
    const val UPDATE_REPO = "robertlionhart-cyber/aurora-companion-unlocked"

    /**
     * 18+ only. huihui-ai's abliterated Llama 3.2 3B Instruct (refusals removed), Q4_K_M by QuantFactory.
     * Pinned to a repo revision and checked against the file's SHA-256 (= the Hugging Face LFS oid).
     */
    val LLAMA_3B_ABLITERATED = ModelOption(
        id = "llama-3.2-3b-abliterated-q4km",
        title = "Unlocked · Smart (old, Q4)",
        techName = "Llama 3.2 3B Instruct abliterated (Q4_K_M)",
        fileName = "Llama-3.2-3B-Instruct-abliterated.Q4_K_M.gguf",
        url = "https://huggingface.co/QuantFactory/Llama-3.2-3B-Instruct-abliterated-GGUF/resolve/" +
            "120d74373f679f9d9e0463b435dfd3fb802d9eb0/Llama-3.2-3B-Instruct-abliterated.Q4_K_M.gguf",
        sizeBytes = 2_019_377_472L,
        minRamGb = ModelCatalog.MIN_RAM_3B,
        note = "Adult mode only. May be slow or freeze on 6 GB phones (e.g. Galaxy A54 6 GB); best on 8 GB+. An uncensored 3B " +
            "(huihui-ai abliteration of Llama 3.2 3B) that doesn't refuse. Checksum-verified after download.",
        license = "Meta Llama 3.2 Community License",
        adultOnly = true,
        sha256 = "e1f2b428e316d8bd1cb5ca13e9649329e299d951fbcee6b3cd0e9f1d646c9fa7",
    )

    /**
     * 0.8.9, 18+ only, recommended adult model: huihui-ai's abliterated Llama 3.2 3B Instruct, Q3_K_M by mradermacher.
     * Scored best of 8 small models in desktop chat tests (18/18 checks). 1.86 GB, so it always runs in the native
     * low-memory mode (>= 1.5 GB: no repacked weight copy, q8_0 KV cache) and needs one good reply before it auto-loads
     * on a < 7 GB phone. Llama 3 template (detected from the GGUF). Pinned revision + SHA-256 (= HF LFS oid).
     */
    val LLAMA_3B_ABLITERATED_Q3 = ModelOption(
        id = "llama-3.2-3b-abliterated-q3km",
        title = "Unlocked · Smart",
        techName = "Llama 3.2 3B Instruct abliterated (Q3_K_M)",
        fileName = "Llama-3.2-3B-Instruct-abliterated.Q3_K_M.gguf",
        url = "https://huggingface.co/mradermacher/Llama-3.2-3B-Instruct-abliterated-GGUF/resolve/" +
            "0b0fc4afe030af5b9bf106fe61b64967e3de67be/Llama-3.2-3B-Instruct-abliterated.Q3_K_M.gguf",
        sizeBytes = 1_856_457_696L,
        minRamGb = ModelCatalog.MIN_RAM_3B,
        note = "Best uncensored model for most phones",
        license = "Meta Llama 3.2 Community License",
        adultOnly = true,
        sha256 = "4a367a4683a3bc0e21ea4558be83e3beb03c74a045b0473c7e915be7d1e6b13b",
        ram = "~2.5 GB RAM",
    )

    /**
     * 0.8.9, 18+ only, backup adult model: abliterated Gemma 2 2B it (IlyaGusev), Q4_K_M by bartowski. 2nd in the
     * desktop tests (17/18). Gemma has no system role: the native layer folds the persona into the first user turn
     * (+ a short model reply) and stops on <end_of_turn>. 1.71 GB, low-memory mode like the 3B.
     */
    val GEMMA2_2B_ABLITERATED = ModelOption(
        id = "gemma-2-2b-abliterated-q4km",
        title = "Unlocked · Balanced",
        techName = "Gemma 2 2B it abliterated (Q4_K_M)",
        fileName = "gemma-2-2b-it-abliterated-Q4_K_M.gguf",
        url = "https://huggingface.co/bartowski/gemma-2-2b-it-abliterated-GGUF/resolve/" +
            "11124c8787de96de70c3d5cb8d5d49c420ebcdf8/gemma-2-2b-it-abliterated-Q4_K_M.gguf",
        sizeBytes = 1_708_582_784L,
        minRamGb = ModelCatalog.MIN_RAM_3B,
        note = "A bit lighter than Smart",
        license = "Gemma Terms of Use",
        adultOnly = true,
        sha256 = "5f0264dd8197b8f96b6f363461cc9d5b7ad49284d2849c68942f43109a1252c4",
        ram = "~2.5 GB RAM",
    )

    /**
     * 0.8.5-0.8.8 adult pick, RETIRED in 0.8.9: huihui-ai's abliterated Qwen2.5 1.5B came last of 8 small models in
     * desktop tests (made things up, lost track of the chat, looped or drifted into emoji). No longer offered; kept
     * only so a downloaded copy shows under "Other downloaded models" with a Delete button.
     */
    val QWEN_15B_ABLITERATED = ModelOption(
        id = "qwen2.5-1.5b-abliterated-q5km",
        title = "Unlocked · Qwen (old)",
        techName = "Qwen2.5 1.5B Instruct abliterated (Q5_K_M)",
        fileName = "Qwen2.5-1.5B-Instruct-abliterated.Q5_K_M.gguf",
        url = "https://huggingface.co/mradermacher/Qwen2.5-1.5B-Instruct-abliterated-GGUF/resolve/" +
            "36d335ab94cb641e885c327f110600342663eee2/Qwen2.5-1.5B-Instruct-abliterated.Q5_K_M.gguf",
        sizeBytes = 1_125_050_944L,
        minRamGb = 3.5,
        note = "Recommended for adult mode. An uncensored Qwen2.5 1.5B (huihui-ai abliteration): a bit smarter than the 1B and " +
            "still quick, about 1.6 GB RAM while chatting. Good on 6 GB phones. Checksum-verified after download.",
        license = "Apache 2.0 (Qwen2.5)",
        adultOnly = true,
        sha256 = "6d69679c756f406c6f173459af6e6bdf1e1f6b1b8a236017bed82c9f61c0a76a",
    )

    /** 0.8.5, 18+ only: huihui-ai's abliterated Llama 3.2 1B Instruct, Q4_K_M by mradermacher. Same speed as the 1B. */
    val LLAMA_1B_ABLITERATED = ModelOption(
        id = "llama-3.2-1b-abliterated-q4km",
        title = "Unlocked · Light",
        techName = "Llama 3.2 1B Instruct abliterated (Q4_K_M)",
        fileName = "Llama-3.2-1B-Instruct-abliterated.Q4_K_M.gguf",
        url = "https://huggingface.co/mradermacher/Llama-3.2-1B-Instruct-abliterated-GGUF/resolve/" +
            "becf36858832feddfea0ace2fabf791797571999/Llama-3.2-1B-Instruct-abliterated.Q4_K_M.gguf",
        sizeBytes = 955_445_792L,
        minRamGb = 3.0,
        note = "Fast, low memory",
        license = "Meta Llama 3.2 Community License",
        adultOnly = true,
        sha256 = "68336c25367576f49b29f01ee9034658b51bbded6a7f215e934d997de96301e0",
        ram = "~1.5 GB RAM",
    )

    /** 0.9.0: recommended 3B Q3 first, Gemma 2 2B as the backup, the light 1B. (Qwen2.5 1.5B and the 3B Q4 are retired.) */
    val extraModels: List<ModelOption> =
        listOf(LLAMA_3B_ABLITERATED_Q3, GEMMA2_2B_ABLITERATED, LLAMA_1B_ABLITERATED)

    /** Pre-selected when adult mode is turned on from model setup. */
    val recommendedMature: ModelOption? = LLAMA_3B_ABLITERATED_Q3

    /** No longer offered; a downloaded copy can still be deleted (and never auto-loads). */
    val retiredModels: List<ModelOption> = listOf(QWEN_15B_ABLITERATED, LLAMA_3B_ABLITERATED)

    /** Light models tried first when a heavy one froze (only while 18+ mode is on). */
    val lightMatureModels: List<ModelOption> = listOf(LLAMA_1B_ABLITERATED)

    /**
     * Replaces the family-friendly safety rule for characters marked 18+ while 18+ mode is on. Mature themes are allowed,
     * with hard limits that always apply.
     */
    const val MATURE_GUARD: String =
        "Adult mode is on: the user has confirmed they are 18 or older and chose adult content, so mature, romantic and " +
        "sexual themes are allowed when the user wants them. Rules that always apply: you and every character are adults; " +
        "never sexual content involving anyone under 18 or anyone who does not consent; respect the user's limits and " +
        "change the subject if they ask."

    /**
     * 0.9.3: 18+ chat modes, offered only while adult mode is on. Romantic: only adult-flagged custom characters.
     * Unhinged: everyone, but built-ins and unflagged characters keep the family-friendly rule (edgy humor, mild swearing, nothing sexual).
     */
    val adultModes: List<ChatMode> = listOf(
        ChatMode("romantic", "Romantic", "\u2764\uFE0F", "18+ \u00B7 Affectionate partner",
            "Mode: Romantic. Be affectionate, flirty and loving, like a devoted partner; compliments, sweet talk and romance are welcome.",
            adultOnly = true, adultCharsOnly = true),
        ChatMode("unhinged", "Unhinged", "\uD83E\uDD2A", "18+ \u00B7 Sarcastic, swears, no filter",
            "Mode: Unhinged. Be wild, sarcastic and brutally funny; swearing, roasting and edgy, unfiltered humor are welcome. Never hateful for real, and still on the user's side.",
            adultOnly = true),
    )

    const val GATE_TITLE = "Turn on adult mode (18+)?"
    const val GATE_TEXT =
        "Adult mode is for adults only. It lets characters you create or import that are marked as adult content talk about " +
        "mature and sexual themes, adds an adult-only book (the Kama Sutra) to the library, and offers uncensored " +
        "(\"abliterated\") AI models that do not refuse requests.\n\n" +
        "Everything stays on this device. The AI can be wrong or say things you don't like; it is fiction, not advice. " +
        "The built-in companions stay family-friendly. Only use it in private, and don't turn it on if anyone under 18 " +
        "uses this device. You can switch it off any time in Settings."
    const val GATE_CONFIRM = "I confirm I am 18 or older and want adult content"
}
