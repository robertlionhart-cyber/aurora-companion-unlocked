package com.aurora.companion.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.ln

/** One public-domain book from the desktop beta's knowledge folder (Project Gutenberg texts). */
data class BookInfo(
    val file: String,
    val title: String,
    val author: String,
    val bytes: Long,
    val sha1: String,          // git blob SHA-1 at the pinned commit
    val gutenberg: Int?,
    val category: String,
    val aliases: List<String>,
    val adult: Boolean,
)

data class LibraryStatus(
    val totalBooks: Int = 0,
    val totalBytes: Long = 0,
    val downloadedBooks: Int = 0,
    val downloadedBytes: Long = 0,
    val indexedBooks: Int = 0,
    val chunks: Int = 0,
    val indexBytes: Long = 0,
) {
    val anyDownloaded get() = downloadedBooks > 0
    val complete get() = totalBooks > 0 && downloadedBooks == totalBooks
    val ready get() = indexedBooks > 0 && indexedBooks == downloadedBooks
}

data class Passage(val book: BookInfo, val text: String, val score: Double, val byteStart: Long)

/**
 * 0.8 offline book library.
 *
 * Desktop beta (read only reference): KnowledgeBase over the knowledge folder, 700-char chunks with BM25,
 * one-turn "KNOWLEDGE LIBRARY" system message, kama_sutra.txt only in adult mode, and the feature on by
 * default only with 6 GB of RAM or more.
 *
 * Android: books are an optional download from the public GitHub repo (raw files pinned to one commit,
 * each checked against its git blob SHA-1), stored in app storage. The index is SQLite FTS4 over
 * paragraph chunks (no embeddings, no RAM-resident index); BM25 is computed from matchinfo().
 * The FTS table is contentless: chunk text is read back from the book files by byte offset.
 */
object Library {
    private const val TAG = "AuroraLibrary"
    const val MANIFEST_ASSET = "library/manifest.json"
    const val DB_VERSION = 1

    /**
     * 18+ gate (0.8.4). Adult-only books (the Kama Sutra) are hidden from the list, never downloaded, never indexed
     * and never retrieved unless the user has confirmed their age and turned adult mode on (Settings).
     */
    fun adultAllowed(ctx: Context): Boolean = Edition.UNLOCKED && AdultMode.enabled(ctx)

    // ---------- manifest ----------
    @Volatile private var manifest: List<BookInfo>? = null
    @Volatile var baseUrl: String = ""
        private set
    @Volatile var commit: String = ""
        private set

    fun allBooks(ctx: Context): List<BookInfo> {
        manifest?.let { return it }
        val root = JSONObject(ctx.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() })
        baseUrl = root.getString("base_url")
        commit = root.getString("commit")
        val arr = root.getJSONArray("books")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val al = o.optJSONArray("aliases")
            BookInfo(
                file = o.getString("file"), title = o.getString("title"), author = o.optString("author"),
                bytes = o.getLong("bytes"), sha1 = o.getString("sha1"),
                gutenberg = if (o.isNull("gutenberg")) null else o.optInt("gutenberg"),
                category = o.optString("category", "Books"),
                aliases = if (al == null) emptyList() else (0 until al.length()).map { al.getString(it).lowercase() },
                adult = o.optBoolean("adult", false),
            )
        }
        // 0.8.6: the Family edition drops adult-only books entirely (never listed, downloaded, indexed or searched).
        val kept = if (Edition.UNLOCKED) list else list.filter { !it.adult }
        manifest = kept
        return kept
    }

    /** Books the user may see / download / search right now (adult-only ones need the 18+ gate). */
    fun books(ctx: Context): List<BookInfo> = allBooks(ctx).filter { !it.adult || adultAllowed(ctx) }

    fun byFile(ctx: Context, file: String) = books(ctx).firstOrNull { it.file == file }

    // ---------- storage ----------
    fun dir(ctx: Context) = File(ctx.filesDir, "library").apply { mkdirs() }
    private fun booksDir(ctx: Context) = File(dir(ctx), "books").apply { mkdirs() }
    fun bookFile(ctx: Context, b: BookInfo) = File(booksDir(ctx), b.file)
    private fun dbFile(ctx: Context) = File(dir(ctx), "index.db")
    fun isDownloaded(ctx: Context, b: BookInfo) = bookFile(ctx, b).let { it.isFile && it.length() == b.bytes }

    fun status(ctx: Context): LibraryStatus {
        val bs = books(ctx)
        val have = bs.filter { isDownloaded(ctx, it) }
        val indexed = runCatching { indexedFiles(ctx) }.getOrDefault(emptySet())
        return LibraryStatus(
            totalBooks = bs.size, totalBytes = bs.sumOf { it.bytes },
            downloadedBooks = have.size, downloadedBytes = have.sumOf { it.bytes },
            indexedBooks = have.count { it.file in indexed },
            chunks = runCatching { chunkCount(ctx) }.getOrDefault(0),
            indexBytes = dbFile(ctx).length() + File(dbFile(ctx).path + "-journal").length(),
        )
    }

    /** Removes the downloaded books and the index (the manifest stays in the APK). */
    fun deleteAll(ctx: Context) {
        synchronized(dbLock) { db?.close(); db = null }
        dir(ctx).deleteRecursively()
    }

    // ---------- download ----------
    /**
     * Downloads every missing book (one file at a time, resumable through .part files) and verifies each
     * against its git blob SHA-1. [progress] gets (bytesDone, bytesTotal, current title).
     */
    suspend fun downloadAll(ctx: Context, progress: (Long, Long, String) -> Unit) {
        allBooks(ctx)
        val bs = books(ctx)
        val total = bs.sumOf { it.bytes }
        var done = bs.filter { isDownloaded(ctx, it) }.sumOf { it.bytes }
        for (b in bs) {
            currentCoroutineContext().ensureActive()
            if (isDownloaded(ctx, b)) continue
            progress(done, total, b.title)
            val base = done
            downloadOne(ctx, b) { have -> progress(base + have, total, b.title) }
            done += b.bytes
        }
        progress(total, total, "")
    }

    private suspend fun downloadOne(ctx: Context, b: BookInfo, onBytes: (Long) -> Unit) {
        val target = bookFile(ctx, b)
        val part = File(target.path + ".part")
        if (part.length() > b.bytes) part.delete()
        var attempt = 0
        while (true) {
            try {
                fetch(baseUrl + b.file, part, b.bytes, onBytes)
                break
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (++attempt >= 3) throw IllegalStateException("${b.title}: ${e.message}", e)
                Log.w(TAG, "retry ${b.file}: ${e.message}")
                kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        if (part.length() != b.bytes) { part.delete(); throw IllegalStateException("${b.title}: size mismatch, tap Download again") }
        if (gitBlobSha1(part) != b.sha1) { part.delete(); throw IllegalStateException("${b.title}: checksum mismatch, tap Download again") }
        if (!part.renameTo(target)) throw IllegalStateException("Could not save ${b.file}")
    }

    private suspend fun fetch(urlStr: String, part: File, expected: Long, onBytes: (Long) -> Unit) {
        if (part.length() == expected) return
        var url = URL(urlStr)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("User-Agent", "AuroraCompanion-Android/0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue }
            break
        }
        try {
            val code = conn.responseCode
            if (code == 416) { part.delete(); throw IllegalStateException("range error, restarting") }
            if (code != 200 && code != 206) throw IllegalStateException("server returned HTTP $code")
            val resume = code == 206
            if (!resume && part.exists()) part.delete()
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(if (resume) part.length() else 0)
                var have = raf.filePointer
                val buf = ByteArray(64 * 1024)
                var last = 0L
                conn.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 200) { last = now; onBytes(have) }
                    }
                }
                onBytes(have)
            }
        } finally { conn.disconnect() }
    }

    /** Git's blob id: SHA-1 of "blob <size>\u0000" followed by the content (what the GitHub API lists). */
    fun gitBlobSha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${f.length()}\u0000".toByteArray(Charsets.US_ASCII))
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- index (SQLite FTS4) ----------
    private val dbLock = Any()
    private var db: SQLiteDatabase? = null

    private fun open(ctx: Context): SQLiteDatabase = synchronized(dbLock) {
        db?.takeIf { it.isOpen }?.let { return it }
        val d = SQLiteDatabase.openOrCreateDatabase(dbFile(ctx), null)
        if (d.version != DB_VERSION) {
            d.execSQL("DROP TABLE IF EXISTS chunks_fts")
            d.execSQL("DROP TABLE IF EXISTS chunks")
            d.execSQL("DROP TABLE IF EXISTS books_indexed")
            d.execSQL("CREATE TABLE chunks(id INTEGER PRIMARY KEY, book TEXT NOT NULL, start INTEGER NOT NULL, len INTEGER NOT NULL)")
            d.execSQL("CREATE TABLE books_indexed(file TEXT PRIMARY KEY, sha1 TEXT, chunks INTEGER)")
            // Contentless FTS4 (text lives in the book files). Porter stemming so "burns" finds "burn".
            try { d.execSQL("CREATE VIRTUAL TABLE chunks_fts USING fts4(content=\"\", body, tokenize=porter)") }
            catch (e: Exception) {
                Log.w(TAG, "porter tokenizer unavailable, using simple", e)
                d.execSQL("CREATE VIRTUAL TABLE chunks_fts USING fts4(content=\"\", body)")
            }
            d.version = DB_VERSION
        }
        db = d
        d
    }

    fun indexedFiles(ctx: Context): Set<String> {
        if (!dbFile(ctx).exists()) return emptySet()
        val d = open(ctx)
        val out = HashSet<String>()
        d.rawQuery("SELECT file, sha1 FROM books_indexed", null).use { c ->
            val shaByFile = allBooks(ctx).associate { it.file to it.sha1 }
            while (c.moveToNext()) if (shaByFile[c.getString(0)] == c.getString(1)) out += c.getString(0)
        }
        return out
    }

    private fun chunkCount(ctx: Context): Int {
        if (!dbFile(ctx).exists()) return 0
        open(ctx).rawQuery("SELECT COALESCE(SUM(chunks),0) FROM books_indexed", null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * Indexes every downloaded book that is not indexed yet. Each book is one transaction, so an
     * interrupted run simply continues with the next unfinished book. [progress] gets (booksDone, booksTotal, title).
     */
    suspend fun indexAll(ctx: Context, progress: (Int, Int, String) -> Unit) {
        val todo0 = books(ctx).filter { isDownloaded(ctx, it) }
        val have = indexedFiles(ctx)
        val todo = todo0.filter { it.file !in have }
        val totalBytes = todo.sumOf { it.bytes }.coerceAtLeast(1)
        var doneBytes = 0L
        val d = open(ctx)
        todo.forEachIndexed { i, b ->
            currentCoroutineContext().ensureActive()
            progress(((doneBytes * 1000) / totalBytes).toInt(), 1000, b.title)
            indexBook(d, b, bookFile(ctx, b).readBytes())
            doneBytes += b.bytes
            Log.i(TAG, "indexed ${i + 1}/${todo.size} ${b.file}")
        }
        progress(1000, 1000, "")
    }

    private fun indexBook(d: SQLiteDatabase, b: BookInfo, bytes: ByteArray) {
        val chunks = chunkBook(bytes)
        synchronized(dbLock) {
            d.beginTransaction()
            try {
                var next = 1L
                d.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM chunks", null).use { c -> if (c.moveToFirst()) next = c.getLong(0) }
                val insC = d.compileStatement("INSERT INTO chunks(id, book, start, len) VALUES(?,?,?,?)")
                val insF = d.compileStatement("INSERT INTO chunks_fts(docid, body) VALUES(?,?)")
                for (ch in chunks) {
                    insC.clearBindings(); insC.bindLong(1, next); insC.bindString(2, b.file); insC.bindLong(3, ch.first.toLong()); insC.bindLong(4, ch.second.toLong())
                    insC.executeInsert()
                    insF.clearBindings(); insF.bindLong(1, next); insF.bindString(2, normalize(String(bytes, ch.first, ch.second, Charsets.UTF_8)))
                    insF.executeInsert()
                    next++
                }
                d.execSQL("INSERT OR REPLACE INTO books_indexed(file, sha1, chunks) VALUES(?,?,?)", arrayOf<Any>(b.file, b.sha1, chunks.size))
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    private fun normalize(s: String) = s.replace(WS, " ").trim()
    private val WS = Regex("\\s+")

    /** Byte range of the book body between the Project Gutenberg START and END markers. */
    fun bodyRange(bytes: ByteArray): Pair<Int, Int> {
        var a = indexOf(bytes, "*** START OF".toByteArray(), 0)
        a = if (a >= 0) { var p = a; while (p < bytes.size && bytes[p] != '\n'.code.toByte()) p++; p + 1 } else 0
        val e = indexOf(bytes, "*** END OF".toByteArray(), a).let { if (it < 0) bytes.size else it }
        return a.coerceAtMost(bytes.size) to e.coerceAtLeast(a.coerceAtMost(bytes.size))
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * Paragraph chunks as (byteStart, byteLength). Paragraphs are separated by blank lines; short ones are
     * merged up to ~350 bytes and long ones split at a sentence end around 600 bytes (desktop: 700 chars).
     * Works on raw UTF-8 bytes ('\n' and '.' never occur inside a multi-byte character).
     */
    fun chunkBook(bytes: ByteArray): List<Pair<Int, Int>> {
        val (a, e) = bodyRange(bytes)
        val out = ArrayList<Pair<Int, Int>>(bytes.size / 450)
        fun emit(s: Int, en: Int) {
            var st = s; var end = en
            while (st < end && bytes[st].toInt().let { it == 32 || it == 9 || it == 10 || it == 13 }) st++
            while (end > st && bytes[end - 1].toInt().let { it == 32 || it == 9 || it == 10 || it == 13 }) end--
            if (end - st >= 60) out += st to (end - st)
        }
        var curStart = -1; var curEnd = -1
        fun flushLong() {
            while (curEnd - curStart > 700) {
                var cut = -1
                var p = curStart + 600
                while (p > curStart + 250) { if (bytes[p] == '.'.code.toByte() && bytes[p + 1].toInt().let { it == 32 || it == 10 || it == 13 }) { cut = p + 1; break }; p-- }
                if (cut < 0) {
                    cut = curStart + 600
                    while (cut > curStart && bytes[cut].toInt() and 0xC0 == 0x80) cut--
                    var q = cut; while (q > curStart + 300 && bytes[q] != ' '.code.toByte()) q--
                    if (q > curStart + 300) cut = q
                }
                emit(curStart, cut); curStart = cut
            }
        }
        var lineStart = a
        var paraStart = -1
        var i = a
        while (i <= e) {
            if (i == e || bytes[i] == '\n'.code.toByte()) {
                var blank = true
                for (k in lineStart until i) { val c = bytes[k].toInt(); if (c != 32 && c != 9 && c != 13) { blank = false; break } }
                if (!blank && paraStart < 0) paraStart = lineStart
                if ((blank || i == e) && paraStart >= 0) {
                    val pEnd = if (blank) lineStart else i
                    if (curStart < 0) curStart = paraStart
                    curEnd = pEnd
                    if (curEnd - curStart >= 350) { flushLong(); emit(curStart, curEnd); curStart = -1 }
                    paraStart = -1
                }
                lineStart = i + 1
            }
            i++
        }
        if (curStart >= 0 && curEnd > curStart) { flushLong(); emit(curStart, curEnd) }
        return out
    }

    // ---------- search / retrieval ----------
    private val STOP = (
        "a an the and or but if of to in on at by for with about from into onto over under than then so as is are was were be been being " +
        "do does did doing done can could would should will shall may might must i you he she it we they me my mine your yours his her hers its our ours " +
        "their theirs them him us this that these those what who whom whose which when where why how there here some any all also just very really " +
        "tell explain say says said know knows please aurora book books passage passages quote quotes chapter about tell describe give find show me " +
        "let lets let's ok okay yeah yes no not dont don't im i'm youre you're thing things something anything like want wanted need much many more most " +
        "according read reading wrote write written does did talk talks teach teaches mean means meaning"
    ).split(" ").filter { it.isNotBlank() }.toSet()

    private val QUESTION = Regex(
        "^(who|what|when|where|why|how|which|whose|explain|describe|define|teach|summari[sz]e|tell me about|tell me what|tell me how|tell me why|give me facts|is it true)\\b" +
        "|\\b(according to|in the book|from the book|in the bible|what does .{2,40} say|quote|passage|history of|facts? about|how (do|to|can|should) )",
        RegexOption.IGNORE_CASE,
    )
    private val SMALL_TALK = Regex(
        "^(hi|hey|hello|yo|thanks|thank you|ok|okay|good (morning|night|evening|afternoon)|how are you|what'?s up|how'?s it going|i love you|bye|goodbye|lol|haha)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val BOOK_TALK = Regex("\\b(book|read|reading|says?|said|quote|passage|chapter|verse|author|wrote|story|poem|play)\\b", RegexOption.IGNORE_CASE)

    /** Book whose title or alias is mentioned in the message (longest alias wins). */
    fun mentionedBook(ctx: Context, msg: String): Pair<BookInfo, String>? {
        val m = " " + msg.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(WS, " ") + " "
        var best: Pair<BookInfo, String>? = null
        for (b in books(ctx)) {
            val names = b.aliases + listOf(b.title.lowercase().substringBefore(":").substringBefore(";").substringBefore("(").trim().removePrefix("the ").trim())
            for (n0 in names) {
                val n = n0.replace(Regex("[^a-z0-9' ]"), " ").replace(WS, " ").trim()
                if (n.length < 4) continue
                if (m.contains(" $n ") && (best == null || n.length > best.second.length)) best = b to n
            }
        }
        return best
    }

    fun keywords(text: String, drop: String = ""): List<String> {
        val dropped = drop.split(" ").toSet()
        return Regex("[a-z][a-z']+").findAll(text.lowercase()).map { it.value.trim('\'').removeSuffix("'s") }
            .filter { it.length > 2 && it !in STOP && it !in dropped }.distinct().take(6).toList()
    }

    /** True when the message looks like a knowledge question or names a book (desktop skips simple utterances). */
    fun looksLikeKnowledge(ctx: Context, msg: String): Boolean {
        val t = msg.trim()
        if (t.length < 12 || SMALL_TALK.containsMatchIn(t) && t.length < 40) return false
        val kw = keywords(t)
        if (QUESTION.containsMatchIn(t) && kw.isNotEmpty()) return true
        if (t.endsWith("?") && kw.size >= 2) return true
        // A named book counts on its own only when the name is unambiguous (several words) or the user talks about reading it,
        // so everyday words that happen to be titles ("apology", "meditations") don't pull in passages.
        val m = mentionedBook(ctx, t) ?: return false
        return kw.isNotEmpty() && (m.second.contains(' ') || BOOK_TALK.containsMatchIn(t))
    }

    private fun bm25(mi: ByteArray): Pair<Double, Int> {
        // matchinfo 'pcnalx': p, c, n, a[c], l[c], x[3*p*c] as 32-bit native-order ints (one column).
        val bb = java.nio.ByteBuffer.wrap(mi).order(java.nio.ByteOrder.nativeOrder())
        val p = bb.getInt(0); val n = bb.getInt(8).toDouble()
        val avg = bb.getInt(12).toDouble().coerceAtLeast(1.0); val dl = bb.getInt(16).toDouble()
        var s = 0.0; var cov = 0
        for (i in 0 until p) {
            val base = 20 + i * 12
            val tf = bb.getInt(base).toDouble(); val df = bb.getInt(base + 8).toDouble()
            if (tf > 0) cov++
            val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
            s += idf * tf * 2.5 / (tf + 1.5 * (1 - 0.75 + 0.75 * dl / avg))
        }
        return s * (0.5 + cov.toDouble() / p.coerceAtLeast(1)) to cov
    }

    private fun ftsTerm(w: String) = w.replace("'", "").replace("\"", "")

    /**
     * Top passages for [keywords] (optionally inside one book). Tries all words first (AND), then any word (OR)
     * if that finds too little, and ranks with BM25. Returns chunk text read back from the book file.
     */
    fun search(ctx: Context, keywords: List<String>, limit: Int, onlyBook: String? = null, minCoverage: Int = 1): List<Passage> {
        if (keywords.isEmpty() || !dbFile(ctx).exists()) return emptyList()
        val d = open(ctx)
        val terms = keywords.map(::ftsTerm).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        fun run(q: String): List<Triple<Long, Double, Int>> {
            val sql = if (onlyBook == null)
                "SELECT docid, matchinfo(chunks_fts,'pcnalx') FROM chunks_fts WHERE chunks_fts MATCH ? LIMIT 3000"
            else "SELECT f.docid, matchinfo(chunks_fts,'pcnalx') FROM chunks_fts f JOIN chunks c ON c.id = f.docid WHERE chunks_fts MATCH ? AND c.book = ? LIMIT 3000"
            val args = if (onlyBook == null) arrayOf(q) else arrayOf(q, onlyBook)
            val out = ArrayList<Triple<Long, Double, Int>>()
            synchronized(dbLock) {
                d.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) { val (s, cov) = bm25(c.getBlob(1)); out += Triple(c.getLong(0), s, cov) }
                }
            }
            return out
        }
        var rows = runCatching { run(terms.joinToString(" ")) }.getOrDefault(emptyList())
        if (rows.size < limit && terms.size > 1) rows = runCatching { run(terms.joinToString(" OR ")) }.getOrDefault(rows)
        val ranked = rows.filter { it.third >= minCoverage.coerceAtMost(terms.size) }.sortedByDescending { it.second }
        val allowed = books(ctx).associateBy { it.file }
        val out = ArrayList<Passage>()
        val seen = HashSet<String>()
        for ((id, score, _) in ranked) {
            if (out.size >= limit) break
            val row = synchronized(dbLock) {
                d.rawQuery("SELECT book, start, len FROM chunks WHERE id = ?", arrayOf(id.toString())).use { c ->
                    if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getInt(2)) else null
                }
            } ?: continue
            val book = allowed[row.first] ?: continue   // adult-only books never come back while the 18+ hook is off
            val text = readChunk(bookFile(ctx, book), row.second, row.third) ?: continue
            val key = text.take(80)
            if (!seen.add(key)) continue
            out += Passage(book, text, score, row.second)
        }
        return out
    }

    private fun readChunk(f: File, start: Long, len: Int): String? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(len); raf.seek(start); raf.readFully(buf)
            normalize(String(buf, Charsets.UTF_8))
        }
    }.getOrNull()

    /** Cuts a passage to [max] chars around the first keyword hit, on sentence / word boundaries. */
    fun excerpt(text: String, keywords: List<String>, max: Int): String {
        if (text.length <= max) return text
        val low = text.lowercase()
        val hit = keywords.mapNotNull { k -> low.indexOf(k.take(5)).takeIf { it >= 0 } }.minOrNull() ?: 0
        var start = 0
        if (hit > max / 2) {
            val dot = text.lastIndexOf(". ", hit)
            start = if (dot >= 0 && hit - dot < max - 60) dot + 2 else text.lastIndexOf(' ', hit - max / 3).coerceAtLeast(0)
        }
        var end = (start + max).coerceAtMost(text.length)
        if (end < text.length) { val sp = text.lastIndexOf(' ', end); if (sp > start + max / 2) end = sp }
        return (if (start > 0) "\u2026" else "") + text.substring(start, end).trim() + (if (end < text.length) "\u2026" else "")
    }

    /**
     * Chat retrieval: up to 3 passages within ~1,200 characters, formatted as the hidden App note.
     * Returns (note, cited titles) or null when nothing relevant was found.
     */
    fun retrieveForChat(ctx: Context, msg: String, budget: Int = 1200): Pair<String, List<String>>? {
        if (!looksLikeKnowledge(ctx, msg)) return null
        val mention = mentionedBook(ctx, msg)
        val idx = indexedFiles(ctx)
        if (idx.isEmpty()) return null
        val kw = keywords(msg, drop = mention?.second ?: "")
        var hits: List<Passage> = emptyList()
        if (mention != null && mention.first.file in idx && kw.isNotEmpty()) hits = search(ctx, kw, 3, onlyBook = mention.first.file)
        if (hits.isEmpty() && kw.isNotEmpty()) {
            // Without a named book, ask for most of the keywords to appear so random mentions don't sneak in.
            val need = if (kw.size >= 3) 2 else kw.size
            hits = search(ctx, kw, 3, minCoverage = need)
        }
        if (hits.isEmpty()) return null
        val per = (budget / hits.size) - 40
        val sb = StringBuilder()
        var used = 0
        val cited = ArrayList<String>()
        hits.forEachIndexed { i, p ->
            val ex = excerpt(p.text, kw, per.coerceAtLeast(200))
            if (used + ex.length > budget && i > 0) return@forEachIndexed
            used += ex.length
            val by = if (p.book.author.isNotBlank()) " by ${p.book.author}" else ""
            sb.append("[${i + 1}] From \"${p.book.title}\"$by: \"$ex\"\n")
            if (p.book.title !in cited) cited += p.book.title
        }
        val note = "Reference passages from the offline public-domain book library (this turn only). " +
            "Use them only if they help answer the user; explain in your own words and stay in character. " +
            "When you use a passage, name the book title (for example: \"In ${cited.first()}, ...\"). " +
            "If they don't help, ignore them and do not mention them.\n" + sb.toString().trimEnd()
        return note to cited
    }

    // ---------- reader ----------
    /**
     * Reader text: the book body with hard-wrapped prose lines joined into paragraphs (short lines such as
     * verse keep their breaks). [lineMap] pairs (rawByteOffset, textIndex) per source line, so a search hit's
     * byte offset can be turned into a reader position.
     */
    class ReaderText(val text: String, private val rawOffsets: LongArray, private val textOffsets: IntArray) {
        fun positionForByte(byte: Long): Int {
            var lo = 0; var hi = rawOffsets.size - 1
            if (hi < 0) return 0
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (rawOffsets[mid] <= byte) lo = mid else hi = mid - 1 }
            return textOffsets[lo].coerceIn(0, text.length)
        }
    }

    fun readerText(ctx: Context, b: BookInfo): ReaderText {
        val bytes = bookFile(ctx, b).readBytes()
        val (a, e) = bodyRange(bytes)
        val sb = StringBuilder((e - a))
        val raws = ArrayList<Long>(); val outs = ArrayList<Int>()
        var lineStart = a
        var prevLen = 0       // length of the previous non-blank line (chars)
        var pendingBlank = false
        var i = a
        while (i <= e) {
            if (i == e || bytes[i] == '\n'.code.toByte()) {
                var end = i
                if (end > lineStart && bytes[end - 1] == '\r'.code.toByte()) end--
                val line = String(bytes, lineStart, end - lineStart, Charsets.UTF_8).trimEnd()
                if (line.isBlank()) {
                    if (sb.isNotEmpty()) pendingBlank = true
                } else {
                    raws += lineStart.toLong()
                    if (sb.isNotEmpty()) {
                        when {
                            pendingBlank -> sb.append("\n\n")
                            prevLen >= 55 && !line.startsWith("  ") -> sb.append(' ')
                            else -> sb.append('\n')
                        }
                    }
                    outs += sb.length
                    sb.append(if (pendingBlank || sb.isEmpty() || prevLen < 55) line else line.trimStart())
                    prevLen = line.length
                    pendingBlank = false
                }
                lineStart = i + 1
            }
            i++
        }
        return ReaderText(sb.toString(), raws.toLongArray(), outs.toIntArray())
    }

    /** Splits [text] into page start offsets of about [chars] characters, breaking at paragraph / line / word ends. */
    fun paginate(text: String, chars: Int): IntArray {
        val starts = ArrayList<Int>()
        var p = 0
        val n = text.length
        while (p < n) {
            starts += p
            var end = (p + chars).coerceAtMost(n)
            if (end < n) {
                val para = text.lastIndexOf("\n\n", end)
                val line = text.lastIndexOf('\n', end)
                val word = text.lastIndexOf(' ', end)
                end = when {
                    para > p + chars * 0.6 -> para + 2
                    line > p + chars * 0.75 -> line + 1
                    word > p + chars / 2 -> word + 1
                    else -> end
                }
            }
            p = end
        }
        if (starts.isEmpty()) starts += 0
        return starts.toIntArray()
    }
}
