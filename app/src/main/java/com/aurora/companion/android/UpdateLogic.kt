package com.aurora.companion.android

import org.json.JSONArray
import org.json.JSONObject

/** One downloadable file of a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** A newer release that has an APK for this edition. */
data class UpdateInfo(
    val tag: String,
    val versionName: String,
    /** From a "versionCode: 16" line in the release notes, when present. */
    val versionCode: Int?,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apk: ReleaseAsset,
    /** A separate "<apk>.sha256" asset, when published. */
    val shaAsset: ReleaseAsset?,
    val prerelease: Boolean,
)

/**
 * 0.8.6: the pure (no Android) part of "Check for updates": parses the GitHub Releases API, picks the APK for this
 * edition and decides whether it is newer. Kept free of Android classes so it can be tested on a plain JVM.
 *
 * Release asset naming (see README): AuroraCompanion-<Edition>-<version>.apk, e.g. AuroraCompanion-Family-0.8.6-beta.apk.
 * Older single-edition names (AuroraCompanion-0.8.3-beta.apk) count as Family. Debug builds are never offered. Checksums come from (1) the asset's "digest" field GitHub adds, (2) a
 * "<apk>.sha256" asset, or (3) a line in the release notes naming the APK with its 64-hex SHA-256.
 */
object UpdateLogic {
    /** 0.9.9: per edition (Family: aurora-companion-android, Unlocked: aurora-companion-unlocked). */
    const val REPO = Edition.UPDATE_REPO
    const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=15"

    private val HEX64 = Regex("""\b[0-9a-fA-F]{64}\b""")

    /** "v0.8.6-beta" / "0.8.6-beta" / "AuroraCompanion 0.8.10" -> [0, 8, 6]. Empty when there is no number. */
    fun numbers(version: String): List<Int> {
        val m = Regex("""(\d+(?:\.\d+)*)""").find(version) ?: return emptyList()
        return m.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
    }

    /** True when the part after the numbers marks a pre-release ("-beta", "-rc1"...). */
    private fun hasSuffix(version: String): Boolean {
        val m = Regex("""\d+(?:\.\d+)*(.*)""").find(version) ?: return false
        return m.groupValues[1].trim().trimStart('-', '_', ' ').isNotEmpty()
    }

    /** Compares two version names: numbers first (0.8.10 > 0.8.9), then "0.9.0" (final) > "0.9.0-beta". */
    fun compareVersions(a: String, b: String): Int {
        val x = numbers(a); val y = numbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        val sa = hasSuffix(a); val sb = hasSuffix(b)
        return when { sa == sb -> 0; sa -> -1; else -> 1 }
    }

    /** A release APK for [edition]: the edition word as its own token in the file name, never a debug build. */
    fun assetMatches(name: String, edition: String): Boolean {
        if (!name.endsWith(".apk", ignoreCase = true)) return false
        val base = name.dropLast(4)
        if (Regex("""(^|[-_. ])debug([-_. ]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(base)) return false
        return Regex("""(^|[-_. ])${Regex.escape(edition)}([-_. ]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(base)
    }

    /**
     * Releases before 0.8.6 had one edition named "AuroraCompanion-<version>.apk" (no edition word). That is the Family
     * edition (same app id), so the Family updater also accepts that pattern. Never debug builds.
     */
    private val LEGACY = Regex("""^AuroraCompanion-v?\d[\w.]*(-(beta|alpha|rc\d*))?\.apk$""", RegexOption.IGNORE_CASE)
    const val LEGACY_EDITION = "Family"

    fun legacyMatches(name: String, edition: String): Boolean = edition.equals(LEGACY_EDITION, ignoreCase = true) && LEGACY.matches(name)

    fun versionCodeFromNotes(body: String): Int? =
        Regex("""version\s*code\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** SHA-256 for [assetName] from the notes: on the line naming the file, or on the line right after it. */
    fun sha256FromNotes(body: String, assetName: String): String? {
        val lines = body.lines()
        for ((i, line) in lines.withIndex()) {
            if (!line.contains(assetName, ignoreCase = true)) continue
            HEX64.find(line)?.let { return it.value.lowercase() }
            lines.getOrNull(i + 1)?.let { next -> HEX64.find(next)?.let { return it.value.lowercase() } }
        }
        return null
    }

    /** First 64-hex token of a "<apk>.sha256" file (sha256sum format "hash  name" or just the hash). */
    fun sha256FromFile(text: String): String? = HEX64.find(text)?.value?.lowercase()

    private fun asset(o: JSONObject): ReleaseAsset {
        val digest = o.optString("digest", "")
        val sha = if (digest.startsWith("sha256:", ignoreCase = true)) digest.substring(7).lowercase().takeIf { HEX64.matches(it) } else null
        return ReleaseAsset(o.getString("name"), o.getString("browser_download_url"), o.optLong("size", -1), sha)
    }

    /** Accepts the list endpoint (array) or a single release (/releases/latest). */
    fun releases(json: String): List<JSONObject> {
        val t = json.trim()
        return if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(t))
    }

    /**
     * The newest non-draft release with an APK for [edition] that is newer than the running app, or null.
     * Newer = higher versionCode when the notes give one, otherwise a higher version name.
     */
    fun pick(json: String, edition: String, currentName: String, currentCode: Int, includePrerelease: Boolean = true): UpdateInfo? {
        val found = releases(json).mapNotNull { r ->
            if (r.optBoolean("draft")) return@mapNotNull null
            if (r.optBoolean("prerelease") && !includePrerelease) return@mapNotNull null
            val tag = r.optString("tag_name")
            val body = if (r.isNull("body")) "" else r.optString("body")
            val arr = r.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until arr.length()).map { asset(arr.getJSONObject(it)) }
            val apk = assets.firstOrNull { assetMatches(it.name, edition) } ?: assets.firstOrNull { legacyMatches(it.name, edition) } ?: return@mapNotNull null
            val name = tag.ifBlank { apk.name }
            val code = versionCodeFromNotes(body)
            val newer = if (code != null) code > currentCode else compareVersions(name, currentName) > 0
            if (!newer) return@mapNotNull null
            val shaAsset = assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }
            val apkWithSha = if (apk.sha256 == null) apk.copy(sha256 = sha256FromNotes(body, apk.name)) else apk
            UpdateInfo(tag, name.removePrefix("v").removePrefix("V"), code, r.optString("name").ifBlank { tag }, body,
                r.optString("html_url"), apkWithSha, shaAsset, r.optBoolean("prerelease"))
        }
        return found.maxWithOrNull { a, b ->
            val c = compareVersions(a.versionName, b.versionName)
            if (c != 0) c else (a.versionCode ?: 0).compareTo(b.versionCode ?: 0)
        }
    }
}
