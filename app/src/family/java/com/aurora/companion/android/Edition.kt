package com.aurora.companion.android

/**
 * 0.8.6: the "Family" edition (product flavor `family`). No 18+ mode at all: no uncensored models, no adult-only book,
 * and every custom or imported character always keeps the family-friendly safety rule. This file only exists in the
 * family source set, so the optional models and the mature rule text are not even in this APK.
 */
object Edition {
    const val UNLOCKED = false
    /** Shown in Settings and matched (case-insensitively) in GitHub release asset names: AuroraCompanion-Family-<ver>.apk */
    const val NAME = "Family"
    /** 0.9.9: GitHub repo the in-app updater checks (releases with AuroraCompanion-Family-<ver>.apk). */
    const val UPDATE_REPO = "robertlionhart-cyber/aurora-companion-android"
    val extraModels: List<ModelOption> = emptyList()
    val lightMatureModels: List<ModelOption> = emptyList()
    val recommendedMature: ModelOption? = null
    val retiredModels: List<ModelOption> = emptyList()
    /** Family edition: never replaces the safety rule. */
    val MATURE_GUARD: String? = null
    /** 0.9.3: no 18+ chat modes in the Family edition. */
    val adultModes: List<ChatMode> = emptyList()
    const val GATE_TITLE = ""
    const val GATE_TEXT = ""
    const val GATE_CONFIRM = ""
}
