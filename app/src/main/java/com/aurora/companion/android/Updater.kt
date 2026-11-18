package com.aurora.companion.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 0.8.6: in-app updates from this app's GitHub Releases. Only runs when the user taps "Check for updates" (or the
 * optional once-a-day check on launch, which can be turned off). Downloads the APK for this edition, checks its size,
 * its SHA-256 (when the release publishes one) and that it is this same app signed with the same key, then hands it
 * to Android's installer. Same app id + same signature = an in-place update that keeps all chats and settings.
 */
object Updater {
    private const val UA = "AuroraCompanion-Android-Updater"

    fun currentVersionName(ctx: Context): String = pkgInfo(ctx, ctx.packageName, 0)?.versionName ?: "0"
    fun currentVersionCode(ctx: Context): Int = pkgInfo(ctx, ctx.packageName, 0)?.let { code(it) } ?: 0

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Int = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode

    @Suppress("DEPRECATION")
    private fun pkgInfo(ctx: Context, pkg: String, flags: Int): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else ctx.packageManager.getPackageInfo(pkg, flags)
    }.getOrNull()

    private fun get(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        return c
    }

    private fun getText(url: String, accept: String): String {
        val c = get(url, accept)
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IllegalStateException("GitHub is busy (rate limit). Try again in an hour.")
            if (code !in 200..299) throw IllegalStateException("GitHub answered $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Blocking: asks GitHub for the newest release of this edition. Null = you're up to date. */
    fun check(ctx: Context): UpdateInfo? {
        val json = getText(UpdateLogic.API_URL, "application/vnd.github+json")
        val info = UpdateLogic.pick(json, Edition.NAME, currentVersionName(ctx), currentVersionCode(ctx)) ?: return null
        // A separate "<apk>.sha256" asset wins over nothing (GitHub's own digest wins over both).
        if (info.apk.sha256 == null && info.shaAsset != null) {
            val sha = runCatching { UpdateLogic.sha256FromFile(getText(info.shaAsset.url, "application/octet-stream")) }.getOrNull()
            if (sha != null) return info.copy(apk = info.apk.copy(sha256 = sha))
        }
        return info
    }

    fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Blocking: downloads + verifies the APK; returns the file ready to install. Throws with a readable message. */
    fun download(ctx: Context, info: UpdateInfo, isCancelled: () -> Boolean, onProgress: (Float?) -> Unit): File {
        val dir = updatesDir(ctx)
        dir.listFiles()?.forEach { if (it.name != info.apk.name) it.delete() }   // old updates
        val out = File(dir, info.apk.name)
        val part = File(dir, info.apk.name + ".part")
        part.delete()
        val md = MessageDigest.getInstance("SHA-256")
        val c = get(info.apk.url, "application/octet-stream")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (${c.responseCode})")
            val total = if (info.apk.size > 0) info.apk.size else c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                FileOutputStream(part).use { o ->
                    val buf = ByteArray(256 * 1024)
                    var lastPct = -1
                    while (true) {
                        if (isCancelled()) throw InterruptedException("cancelled")
                        val r = input.read(buf); if (r < 0) break
                        o.write(buf, 0, r); md.update(buf, 0, r); done += r
                        if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / total) } }
                        else onProgress(null)
                    }
                }
            }
            if (info.apk.size > 0 && done != info.apk.size) throw IllegalStateException("Download incomplete ($done of ${info.apk.size} bytes). Try again.")
        } catch (e: Throwable) { part.delete(); throw e } finally { c.disconnect() }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (info.apk.sha256 != null && !sha.equals(info.apk.sha256, ignoreCase = true)) {
            part.delete(); throw IllegalStateException("Checksum didn't match, so the download was deleted. Try again later.")
        }
        verifyApk(ctx, part)
        out.delete(); if (!part.renameTo(out)) throw IllegalStateException("Couldn't save the update")
        return out
    }

    /** The downloaded file must be this app (same id), a newer version, and signed with the same key. */
    @Suppress("DEPRECATION")
    fun verifyApk(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: run { apk.delete(); throw IllegalStateException("The download isn't a valid app file.") }
        if (archive.packageName != ctx.packageName) { apk.delete(); throw IllegalStateException("That file is a different edition of the app (${archive.packageName}).") }
        if (code(archive) <= currentVersionCode(ctx)) { apk.delete(); throw IllegalStateException("That file isn't newer than this version.") }
        val mine = pkgInfo(ctx, ctx.packageName, flags)
        fun certs(p: PackageInfo?): Set<String> = when {
            p == null -> emptySet()
            Build.VERSION.SDK_INT >= 28 -> p.signingInfo?.let { si -> (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory) }
                ?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
            else -> p.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
        }
        val a = certs(archive); val b = certs(mine)
        // Some Android versions don't read signatures from an archive; then the installer itself enforces the match.
        if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()) {
            apk.delete()
            throw IllegalStateException("This copy of the app is signed differently (for example a test build), so it can't update in place. " +
                "Download it from the GitHub page instead.")
        }
    }

    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Android 8+: apps need "Install unknown apps" permission to start an install. */
    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for this app. */
    fun openInstallPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Hands the verified APK to Android's package installer (the user confirms "Update"). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
