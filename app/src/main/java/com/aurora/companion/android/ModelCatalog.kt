package com.aurora.companion.android

import android.app.ActivityManager
import android.content.Context

/** A downloadable, third-party GGUF chat model. */
data class ModelOption(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Minimum total device RAM (GB, as reported by Android) we allow this model on. */
    val minRamGb: Double,
    val note: String,
    val license: String,
    /** Only offered (and only loadable) while 18+ mode is on (Unlocked edition only). */
    val adultOnly: Boolean = false,
    /** Pinned SHA-256 of the file; checked after download when set. */
    val sha256: String? = null,
    /** 0.9.0: approximate RAM while chatting, shown in the model list (e.g. "~2.5 GB RAM"). */
    val ram: String = "",
    /** 0.9.0: at most one short tag (e.g. "Recommended", "Slower"). */
    val tag: String? = null,
    /** 0.9.1: the technical model name, shown small and grey under the plain-language [title]. */
    val techName: String = "",
)

object ModelCatalog {
    val LLAMA_1B = ModelOption(
        id = "llama-3.2-1b-q4km",
        title = "Family · Light",
        techName = "Llama 3.2 1B Instruct (Q4_K_M)",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        sizeBytes = 807_694_464L,
        minRamGb = 3.0,
        note = "Fast, low memory",
        license = "Meta Llama 3.2 Community License",
        ram = "~1.5 GB RAM",
    )
    val LLAMA_3B = ModelOption(
        id = "llama-3.2-3b-q4km",
        title = "Family · Smart",
        techName = "Llama 3.2 3B Instruct (Q4_K_M)",
        fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        sizeBytes = 2_019_377_696L,
        // 0.8.4: a "6 GB" phone reports roughly 5.3-5.6 GB total to apps (Galaxy A54 6 GB: 5.3).
        minRamGb = ModelCatalog.MIN_RAM_3B,
        note = "Smarter, slower, needs more memory",
        license = "Meta Llama 3.2 Community License",
        ram = "~3 GB RAM",
    )

    /** 0.8.6: the Unlocked edition adds its optional models (Edition.extraModels); the Family edition has none. */
    val all = listOf(LLAMA_1B, LLAMA_3B) + Edition.extraModels

    /** 0.8.9: models no longer offered (Unlocked: Qwen2.5 1.5B). Only listed so a downloaded copy can be deleted. */
    val retired: List<ModelOption> = Edition.retiredModels

    /** 3B-class files (about 2 GB): on 6 GB phones they can run out of memory and freeze. */
    const val HEAVY_BYTES = 1_500_000_000L
    /** RAM (as reported) from which heavy models are trusted to auto-load at startup. */
    const val HEAVY_SAFE_RAM_GB = 7.0

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * 0.9.0: everything this device can run, regular models first, then the 18+ ones (Unlocked only; listed even with
     * adult mode off, marked 18+; picking one to download asks for the age confirmation).
     */
    fun available(ramGb: Double) = all.filter { ramGb >= it.minRamGb }

    /** 0.9.0: first-run setup only offers light models (the 1B, plus the 18+ 1B in Unlocked); bigger ones come later. */
    val firstRun: List<ModelOption> get() = listOf(LLAMA_1B) + Edition.lightMatureModels

    fun byFileName(name: String?) = (all + retired).firstOrNull { it.fileName == name }

    fun isRetired(name: String?) = retired.any { it.fileName == name }

    /** True for a catalog model that needs adult mode (imported .gguf files are the user's own choice). */
    fun isAdultOnly(fileName: String?) = byFileName(fileName)?.adultOnly == true

    /** Always default to 1B: it is responsive everywhere. 3B is an opt-in on 6 GB+ (warned), Llama 3.2 3B abliterated (Q3) is the adult pick. */
    fun recommended(ramGb: Double) = LLAMA_1B

    /** 0.9.2: heavy (3B-class, >= 1.5 GB) models are labelled "Powerful phones" and warned about below 7 GB. */
    fun isHeavy(o: ModelOption) = o.sizeBytes >= HEAVY_BYTES

    /** 0.9.2: which model gets the "Recommended" tag: a Light one under ~7 GB, Unlocked · Smart (or Family · Smart) on 7 GB+. */
    fun recommendedTag(ramGb: Double): ModelOption =
        if (ramGb < HEAVY_SAFE_RAM_GB) (Edition.lightMatureModels.firstOrNull() ?: LLAMA_1B)
        else (Edition.recommendedMature ?: LLAMA_3B)

    const val POWERFUL_LINE = "Best on phones with 8 GB+ RAM; may be slow or close on others."

    const val MIN_RAM_3B = 5.0
}

fun Long.toNiceSize(): String {
    val gb = this / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.0f MB", this / (1024.0 * 1024.0))
}
