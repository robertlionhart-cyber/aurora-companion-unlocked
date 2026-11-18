package com.aurora.companion.android

import android.content.Context

/**
 * 0.8.4: the 18+ gate (like the desktop app's Adult mode). Off by default; turned on only after the user confirms
 * they are 18 or older. While it is off the app behaves exactly as before (family-friendly everywhere).
 * While it is on: the adult-only book (Kama Sutra) can be downloaded and searched, adult-flagged custom/imported
 * characters lose the family-friendly safety rule, and the abliterated 3B model is offered.
 * Built-in companions always stay family-friendly.
 */
object AdultMode {
    private const val PREF = "adult_mode"
    private const val PREF_CONFIRMED_AT = "adult_confirmed_at"

    /** 0.8.6: always false in the Family edition, whatever is in the prefs. */
    fun enabled(ctx: Context): Boolean = Edition.UNLOCKED && ctx.getSharedPreferences("aurora", 0).getBoolean(PREF, false)

    fun set(ctx: Context, on: Boolean) {
        if (!Edition.UNLOCKED) return
        val e = ctx.getSharedPreferences("aurora", 0).edit().putBoolean(PREF, on)
        if (on) e.putLong(PREF_CONFIRMED_AT, System.currentTimeMillis())
        e.apply()
    }

    // 0.8.6: the texts live in Edition (Unlocked edition only; empty in the Family edition).
    val TITLE get() = Edition.GATE_TITLE
    val DISCLAIMER get() = Edition.GATE_TEXT
    val CONFIRM get() = Edition.GATE_CONFIRM
}
