package com.aurora.companion.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * 1.0 "Find a book": searches Project Gutenberg through the free Gutendex API (gutendex.com, no account) and adds a
 * book to the Library. Only used while Online mode is on; only the search words go out.
 *
 * Adding: plain text (UTF-8 preferred) or, when a book has none, its EPUB; the Gutenberg header and licence are removed
 * (a one-line credit is kept), and the text is stored offline next to the library books.
 */
object Gutendex {
    private const val TAG = "AuroraGutendex"
    private const val API = "https://gutendex.com/books/"
    private const val MAX_BYTES = 25L * 1024 * 1024
    private val UA get() = "AuroraCompanion/1.0 (Android; github.com/" + Edition.UPDATE_REPO + ")"

    data class Result(
        val id: Int,
        val title: String,
        val author: String,
        val languages: List<String>,
        val textUrl: String?,
        val epubUrl: String?,
        val downloads: Int,
        val subjects: List<String>,
    ) { val url get() = textUrl ?: epubUrl }

    data class Page(val results: List<Result>, val next: String?, val total: Int, val hidden: Int)

    /**
     * Family edition: a result is hidden only when Gutendex's own subject / bookshelf tags mark it as erotica or a sex
     * manual. Books not public domain in the USA (Gutendex "copyright": true) are hidden in both editions.
     */
    private val FAMILY_HIDE = Regex("\\b(erotic|erotica|sex instruction|sex manuals?|pornograph)", RegexOption.IGNORE_CASE)

    suspend fun search(query: String, nextUrl: String? = null): Page = withContext(Dispatchers.IO) {
        val url = nextUrl?.takeIf { it.startsWith(API) }
            ?: (API + "?search=" + URLEncoder.encode(query.trim().take(80), "UTF-8") + "&languages=en")
        val root = JSONObject(String(get(url, 2L * 1024 * 1024), Charsets.UTF_8))
        val arr = root.optJSONArray("results")
        var hidden = 0
        val out = ArrayList<Result>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.getJSONObject(i)
            if (o.optString("media_type", "Text") != "Text") continue
            if (o.opt("copyright") == true) { hidden++; continue }
            val subjects = (o.optJSONArray("subjects")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()) +
                (o.optJSONArray("bookshelves")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
            if (!Edition.UNLOCKED && subjects.any { FAMILY_HIDE.containsMatchIn(it) }) { hidden++; continue }
            val formats = o.optJSONObject("formats") ?: JSONObject()
            var txtUtf8: String? = null; var txtAny: String? = null; var epub: String? = null
            for (k in formats.keys()) {
                val v = formats.optString(k)
                if (v.endsWith(".zip")) continue
                when {
                    k.startsWith("text/plain") && k.contains("utf-8", true) -> txtUtf8 = v
                    k.startsWith("text/plain") -> if (txtAny == null) txtAny = v
                    k.startsWith("application/epub+zip") -> if (epub == null) epub = v
                }
            }
            val text = txtUtf8 ?: txtAny
            if (text == null && epub == null) continue
            val authors = o.optJSONArray("authors")
            val author = if (authors == null || authors.length() == 0) "" else (0 until authors.length()).joinToString(", ") { prettyName(authors.getJSONObject(it).optString("name")) }
            out += Result(
                id = o.getInt("id"), title = o.optString("title").replace(Regex("\\s+"), " ").trim(), author = author,
                languages = o.optJSONArray("languages")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                textUrl = text?.replace("http://", "https://"), epubUrl = epub?.replace("http://", "https://"),
                downloads = o.optInt("download_count"), subjects = subjects,
            )
        }
        Page(out, root.optString("next").takeIf { it.startsWith("http") }, root.optInt("count"), hidden)
    }

    /** "Austen, Jane" -> "Jane Austen". */
    fun prettyName(n: String): String {
        val p = n.split(", ")
        return if (p.size == 2 && !p[1].contains(Regex("\\d"))) "${p[1]} ${p[0]}" else n
    }

    /** Download sizes (HEAD, Content-Length) for the shown results; a few at a time. */
    suspend fun sizes(results: List<Result>): Map<Int, Long> = coroutineScope {
        val sem = Semaphore(4)
        results.map { r ->
            async(Dispatchers.IO) {
                val u = r.url
                if (u == null) r.id to -1L else sem.withPermit { r.id to runCatching { head(u) }.getOrDefault(-1L) }
            }
        }.awaitAll().toMap()
    }

    fun fileFor(id: Int) = "gutenberg_$id.txt"

    /** Downloads, cleans and stores [r]; returns the Library entry. [progress] gets 0..1 or null when unknown. */
    suspend fun add(ctx: Context, r: Result, progress: (Float?) -> Unit = {}): BookInfo = withContext(Dispatchers.IO) {
        val body: String
        if (r.textUrl != null) {
            val bytes = getAny(listOf(r.textUrl, "$MIRROR${r.id}/pg${r.id}.txt"), progress)
            body = BookText.stripGutenberg(decode(bytes))
        } else {
            val bytes = getAny(listOf(r.epubUrl!!, "$MIRROR${r.id}/pg${r.id}.epub"), progress)
            body = epubText(bytes)
        }
        if (body.count { it.isLetter() } < 500) throw IllegalStateException("This book has almost no text to read.")
        val credit = "${r.title}\n" + (if (r.author.isNotBlank()) "by ${r.author}\n" else "") +
            "\nFrom Project Gutenberg (gutenberg.org), eBook #${r.id}. Public domain in the USA; " +
            "Project Gutenberg's header and licence were removed for reading aloud.\n\n"
        // The START / END lines let the library's reader and search skip the credit, like with the bundled books.
        val text = credit + "*** START OF THE BOOK ***\n\n" + body.trim() + "\n\n*** END OF THE BOOK ***\n"
        val b = Library.addBook(ctx, fileFor(r.id), r.title.take(160), r.author.take(120), r.id, text)
        runCatching { Library.indexOne(ctx, b) }.onFailure { Log.w(TAG, "index ${b.file}", it) }
        b
    }

    /** Project Gutenberg's own mirror (pglaf.org), used when gutenberg.org doesn't answer. */
    private const val MIRROR = "https://gutenberg.pglaf.org/cache/epub/"

    private suspend fun getAny(urls: List<String>, progress: (Float?) -> Unit): ByteArray {
        var last: Exception? = null
        for (u in urls) {
            try { return get(u, MAX_BYTES, progress) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.message?.contains("too large") == true) throw e
                Log.w(TAG, "download $u: ${e.message}"); last = e
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun decode(bytes: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: java.nio.charset.CharacterCodingException) { String(bytes, Charsets.ISO_8859_1) }
    }

    /** EPUB -> text: spine order from the OPF, each XHTML file flattened (pictures and styles ignored). */
    fun epubText(bytes: ByteArray): String {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) {
                    val n = e.name.lowercase()
                    if (n.endsWith(".opf") || n.endsWith(".xml") || n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))
                        files[e.name] = z.readBytes()
                }
            }
        }
        val container = files["META-INF/container.xml"]?.let { String(it, Charsets.UTF_8) }
        val opfPath = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: files.keys.firstOrNull { it.endsWith(".opf") }
            ?: throw IllegalStateException("Not a readable EPUB")
        val opf = String(files[opfPath] ?: throw IllegalStateException("EPUB package missing"), Charsets.UTF_8)
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val items = HashMap<String, String>()
        Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
            val t = m.value
            val id = Regex("\\bid=\"([^\"]+)\"").find(t)?.groupValues?.get(1)
            val href = Regex("\\bhref=\"([^\"]+)\"").find(t)?.groupValues?.get(1)
            if (id != null && href != null) items[id] = java.net.URLDecoder.decode(href, "UTF-8")
        }
        val spine = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        val sb = StringBuilder()
        for (id in spine) {
            val href = items[id] ?: continue
            val data = files[base + href] ?: files[href] ?: continue
            val t = BookText.htmlToText(String(data, Charsets.UTF_8))
            // Gutenberg's own header / licence pages inside the EPUB.
            if (t.contains("PROJECT GUTENBERG LICENSE", true) || t.contains("START OF THE PROJECT GUTENBERG", true) && t.length < 6000) continue
            if (t.isNotBlank()) sb.append(t).append("\n\n\n")
        }
        return BookText.stripGutenberg(sb.toString())
    }

    // ---------- HTTP ----------
    private fun open(url0: String, method: String = "GET"): HttpURLConnection {
        var url = URL(url0)
        var redirects = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code in 300..399 && redirects < 6) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); redirects++; continue }
            return c
        }
    }

    private fun head(url: String): Long {
        val c = open(url, "HEAD")
        try { return if (c.responseCode == 200) c.contentLengthLong else -1L } finally { c.disconnect() }
    }

    private suspend fun get(url: String, max: Long, progress: (Float?) -> Unit = {}): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("server returned HTTP ${c.responseCode}")
            val len = c.contentLengthLong
            if (len > max) throw IllegalStateException("This book is too large (${len.toNiceSize()}).")
            val out = ByteArrayOutputStream(if (len > 0) len.toInt() else 256 * 1024)
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IllegalStateException("This book is too large.")
                    progress(if (len > 0) out.size().toFloat() / len else null)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }
}
