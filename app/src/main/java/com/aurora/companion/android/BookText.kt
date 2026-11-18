package com.aurora.companion.android

/**
 * 1.0: pure text helpers for read-aloud and "Find a book" (no Android classes, so they are unit-tested on the JVM).
 *  - sentence splitting of the reader text (what the voice reads, one sentence at a time)
 *  - chapter detection (CHAPTER I, BOOK II, ACT III, STAVE ONE...; falls back to equal parts)
 *  - Project Gutenberg header / licence stripping for books added from Gutendex
 *  - "read me <title>" detection in chat and fuzzy title matching
 */
object BookText {

    // ---------------- sentences ----------------
    /** Sentence spans over [text]: starts[i] until ends[i]; paraStart[i] is true for the first sentence of a paragraph. */
    class Sentences(val starts: IntArray, val ends: IntArray, val paraStart: BooleanArray) {
        val size get() = starts.size
        /** Index of the sentence containing (or following) character [pos]. */
        fun indexAt(pos: Int): Int {
            if (size == 0) return 0
            var lo = 0; var hi = size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (starts[mid] <= pos) lo = mid else hi = mid - 1 }
            return lo
        }
        fun prevParagraph(i: Int): Int {
            var j = i.coerceIn(0, maxOf(0, size - 1))
            // Already at a paragraph start: go to the previous paragraph.
            if (j > 0 && paraStart[j]) j--
            while (j > 0 && !paraStart[j]) j--
            return j
        }
        fun nextParagraph(i: Int): Int {
            var j = i + 1
            while (j < size && !paraStart[j]) j++
            return j.coerceAtMost(size)
        }
    }

    private val ABBREV = setOf("mr", "mrs", "ms", "dr", "st", "mt", "jr", "sr", "vs", "etc", "no", "vol", "ch", "chap", "fig",
        "capt", "col", "gen", "lt", "sgt", "rev", "prof", "hon", "messrs", "mme", "mlle", "ft", "esq", "viz", "cf", "ibid", "e.g", "i.e", "p", "pp")
    const val MAX_SENTENCE = 280

    fun sentences(text: String): Sentences {
        val s = ArrayList<Int>(); val e = ArrayList<Int>(); val p = ArrayList<Boolean>()
        var start = 0
        var newPara = true
        fun emit(a0: Int, b0: Int) {
            var a = a0; var b = b0
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b <= a) return
            var hasWord = false
            for (k in a until b) if (text[k].isLetterOrDigit()) { hasWord = true; break }
            if (!hasWord) return
            // Long runs (lists, legal prose) are cut at ; : , or a space so each piece stays short to synthesize.
            while (b - a > MAX_SENTENCE) {
                var cut = -1
                for (k in a + MAX_SENTENCE downTo a + 80) { val c = text[k - 1]; if ((c == ';' || c == ':' || c == ',' || c == '\u2014') && text[k].isWhitespace()) { cut = k; break } }
                if (cut < 0) for (k in a + MAX_SENTENCE downTo a + 80) if (text[k].isWhitespace()) { cut = k; break }
                if (cut < 0) cut = a + MAX_SENTENCE
                s += a; e += cut; p += newPara; newPara = false
                a = cut; while (a < b && text[a].isWhitespace()) a++
            }
            if (b > a) { s += a; e += b; p += newPara; newPara = false }
        }
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                if (i + 1 < n && text[i + 1] == '\n') {
                    emit(start, i); newPara = true
                    while (i < n && text[i] == '\n') i++
                    start = i; continue
                }
                // Single line break (verse): a pause once the line has some length.
                if (i - start >= 60) { emit(start, i); start = i + 1 }
            } else if (c == '.' || c == '!' || c == '?') {
                var j = i + 1
                while (j < n && (text[j] == '.' || text[j] == '!' || text[j] == '?')) j++
                while (j < n && text[j] in "\"'\u201D\u2019)]") j++
                if (j >= n || text[j].isWhitespace()) {
                    if (c == '.' && isAbbrev(text, i)) { i = j; continue }
                    // '"Is it true?" asked Watson': the sentence goes on when the next word starts in lower case.
                    var k = j; while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    if (k < n && text[k].isLowerCase()) { i = j; continue }
                    emit(start, j); start = j; i = j; continue
                }
            }
            i++
        }
        emit(start, n)
        return Sentences(s.toIntArray(), e.toIntArray(), p.toBooleanArray())
    }

    private fun isAbbrev(text: String, dot: Int): Boolean {
        var k = dot
        while (k > 0 && (text[k - 1].isLetter() || text[k - 1] == '.')) k--
        val w = text.substring(k, dot)
        if (w.isEmpty()) return false
        if (w.length == 1 && w[0].isUpperCase()) return true   // initials: "J. R. Smith"
        return w.lowercase() in ABBREV
    }

    // ---------------- chapters ----------------
    data class Chapter(val title: String, val start: Int)

    private val HEAD = Regex(
        "^(chapter|book|part|act|scene|stave|canto|section|letter|volume|psalm|fytte|adventure|story|tale)\\s+([ivxlcdm]+|\\d+|[a-z]+)\\b.*" +
        "|^(prologue|epilogue|preface|introduction|foreword|conclusion|the end|afterword|proem|induction)\\b.{0,40}$" +
        "|^[ivxlc]+\\.?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    const val PART_CHARS = 20_000

    fun chapters(text: String): List<Chapter> {
        // Paragraphs (blank-line separated).
        val paras = ArrayList<IntRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i] == '\n') i++
            if (i >= n) break
            var j = text.indexOf("\n\n", i); if (j < 0) j = n
            paras += i until j
            i = j
        }
        fun ptext(r: IntRange) = text.substring(r.first, r.last + 1).trim()
        // A "prose" paragraph: real text, not a contents list or a heading.
        val prose = BooleanArray(paras.size) { k ->
            val t = ptext(paras[k])
            val letters = t.count { it.isLetter() }
            t.length >= 150 && letters > 0 && t.count { it.isLowerCase() } * 10 >= letters * 6
        }
        val heads = ArrayList<Pair<Int, String>>()   // (paraIndex, title)
        for ((k, r) in paras.withIndex()) {
            if (r.last - r.first > 160) continue
            val lines = ptext(r).lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || lines.size > 3) continue
            if (HEAD.matches(lines[0])) heads += k to lines.joinToString(": ") { it.trimEnd('.') }
        }
        if (heads.size < 3) {
            // All-caps short headings ("THE RED-HEADED LEAGUE"), only when nothing better exists.
            val caps = paras.withIndex().filter { (_, r) ->
                val t = ptext(r)
                r.last - r.first in 3..70 && !t.contains('\n') && t.count { it.isLetter() } >= 4 && t.none { it.isLowerCase() } && !t.endsWith(",") && !t.endsWith(".")
            }.map { it.index to ptext(it.value).trimEnd('.') }
            if (caps.size >= 3 && caps.size > heads.size) { heads.clear(); heads += caps }
        }
        val out = ArrayList<Chapter>()
        for ((idx, h) in heads.withIndex()) {
            val nextPara = if (idx + 1 < heads.size) heads[idx + 1].first else paras.size
            // Contents entries and stacked headings ("PART ONE" right above "I") have no prose of their own.
            if ((h.first + 1 until nextPara).none { prose[it] }) continue
            var title = cleanHeading(h.second)
            if (!title.contains(':')) {
                val sub = paras.getOrNull(h.first + 1)?.let { cleanHeading(ptext(it)) }?.takeIf { it.isNotEmpty() && !it.startsWith("[") }
                if (sub != null && sub.length in 2..60 && !sub.endsWith(".") && !sub.endsWith(",") && sub.any { it.isLetter() } && !HEAD.matches(sub))
                    title = "$title: ${sub.trimEnd('.')}"
            }
            // "CHAPTER IV: The Rabbit Sends in a Little Bill" -> "Chapter IV: The Rabbit Sends in a Little Bill"
            val head = title.substringBefore(": "); val rest = title.substringAfter(": ", "")
            out += Chapter((prettyTitle(head) + if (rest.isNotEmpty()) ": " + (if (rest.none { it.isLowerCase() }) prettyTitle(rest) else rest) else "").take(80), paras[h.first].first)
        }
        if (out.size >= 2) {
            if (out[0].start > 1500) out.add(0, Chapter("Opening", 0))
            return out
        }
        // No usable headings: equal parts at paragraph boundaries.
        val parts = ArrayList<Chapter>()
        var at = 0
        while (at < n) {
            parts += Chapter("Part ${parts.size + 1}", at)
            var next = at + PART_CHARS
            if (next >= n) break
            val pb = text.indexOf("\n\n", next)
            next = if (pb < 0 || pb - next > 4000) next else pb + 2
            at = next
        }
        if (parts.isEmpty()) parts += Chapter("Part 1", 0)
        return parts
    }

    /** Heading text without [Illustration] tags, stray brackets or underscores. */
    private fun cleanHeading(t: String) = t.replace(Regex("\\[(Illustration|Sidenote)[^\\]]*\\]?", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[\\[\\]_]"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.', ':').replace(Regex(":\\s*$"), "")

    private val HEAD_WORDS = setOf("chapter", "book", "part", "act", "scene", "stave", "canto", "section", "letter", "volume", "psalm", "fytte", "adventure", "story", "tale")

    private fun prettyTitle(t: String): String {
        if (t.any { it.isLowerCase() }) return t
        val w = t.lowercase().split(' ')
        return w.mapIndexed { i, x ->
            val roman = x.matches(Regex("[ivxlcdm]+[.:]?")) && ((i == 0 && w.size == 1) || (i == 1 && w[0] in HEAD_WORDS))
            if (roman) x.uppercase() else x.replaceFirstChar { it.uppercase() }
        }.joinToString(" ")
    }

    fun chapterAt(chapters: List<Chapter>, pos: Int): Int {
        var r = 0
        for ((k, c) in chapters.withIndex()) if (c.start <= pos) r = k else break
        return r
    }

    // ---------------- Gutenberg cleanup ----------------
    /** The book body without the Project Gutenberg header and licence (between the START / END markers). */
    fun stripGutenberg(raw0: String): String {
        val raw = raw0.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        val startM = Regex("^\\*{3}\\s*START OF (THE|THIS) PROJECT GUTENBERG.*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).find(raw)
        val endM = Regex("^\\*{3}\\s*END OF (THE|THIS) PROJECT GUTENBERG.*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).find(raw, startM?.range?.last ?: 0)
        var a = startM?.range?.last?.plus(1) ?: 0
        var e = endM?.range?.first ?: raw.length
        // Older files: "*END*THE SMALL PRINT" header and "End of the Project Gutenberg EBook" footer.
        if (startM == null) Regex("\\*END\\*THE SMALL PRINT.*$", RegexOption.MULTILINE).find(raw)?.let { a = it.range.last + 1 }
        if (endM == null) Regex("^\\s*End of (the )?Project Gutenberg", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).find(raw, a)?.let { e = it.range.first }
        if (e < a) { a = 0; e = raw.length }
        var body = raw.substring(a, e).trim('\n', ' ')
        // Producer credits at the very top.
        val first = body.substringBefore("\n\n")
        if (first.length < 400 && Regex("^(produced by|e-?text prepared by|this e-?book was produced|transcribed from|updated editions)", RegexOption.IGNORE_CASE).containsMatchIn(first.trim()))
            body = body.substringAfter("\n\n").trimStart('\n')
        return body.replace(Regex("\n{4,}"), "\n\n\n")
    }

    /** Very small XHTML -> text for EPUB chapters: block tags become paragraph breaks, entities decoded. */
    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(head|script|style)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</?(p|div|h[1-6]|li|blockquote|tr|section|article|pre)[^>]*>"), "\n\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&mdash;", "\u2014").replace("&ndash;", "\u2013")
            .replace("&rsquo;", "\u2019").replace("&lsquo;", "\u2018").replace("&rdquo;", "\u201D").replace("&ldquo;", "\u201C")
        s = Regex("&#(x?)([0-9a-fA-F]+);").replace(s) { m ->
            runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16))) }.getOrDefault(" ")
        }
        s = s.replace("&amp;", "&")
        s = s.lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]+"), " ") }
        return s.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ---------------- chat: "read me ..." ----------------
    sealed class ReadRequest {
        data class Title(val query: String) : ReadRequest()
        /** "Can you read the book / read me a story" with no title. */
        object AnyBook : ReadRequest()
        /** "Keep reading", "continue the book". */
        object Resume : ReadRequest()
        /** "Stop reading", "pause the book". */
        object Stop : ReadRequest()
    }

    private val LEAD = Regex("^(hey|hi|ok|okay|so|um|well|please|aurora|now|and)\\b[ ,]*")
    private val ASK = Regex("^((can|could|would|will) you( please)? |can we |let's |lets |i want you to |i'd like you to |i would like you to |i want to (hear|listen to) |go ahead and )")
    private val READ = Regex("^(please )?(read|narrate)( it)?((?: (?:me|to me|us|to us|aloud|out loud|for me))*)(?: |$)(.*)$")
    private val TAIL = Regex("( (to me|for me|to us|please|aloud|out loud|now|tonight|again|for a while|a bit|now please))+$")
    private val HEADWORDS = Regex("^((the|a|an) (book|novel|story|poem|play|audiobook|audio book) |the book called |the book |something from |some of |some |a bit of |a little of |a chapter (of|from) |from )+")
    private val NOT_BOOK = Regex("^((my|your|this|that|these|those|it|him|her|them)\\b|the (news|weather|reminder|reminders|message|messages|text|email|letter|room|instructions|label|menu|screen|chat|list)\\b|back\\b|what |something (you|i) )")
    private val RESUME = Regex("^(continue|keep|resume|carry on|go on|start)( with)? (reading|the book|the audiobook|the story|listening|where (you|we) (left|stopped))")
    private val STOP = Regex("^(stop|pause|quit|end) (reading|the book|the audiobook|the story)")

    fun readRequest(msg: String): ReadRequest? {
        var m = msg.lowercase().replace(Regex("[\u2018\u2019]"), "'").replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        repeat(4) { m = LEAD.replace(m, "").trim() }
        if (STOP.containsMatchIn(m)) return ReadRequest.Stop
        val asked = ASK.find(m)?.let { m.substring(it.range.last + 1) } ?: m
        if (RESUME.containsMatchIn(asked)) return ReadRequest.Resume
        val r = READ.matchEntire(asked) ?: return null
        val mentionsWho = r.groupValues[4].isNotBlank()
        var rest = r.groupValues[5].trim()
        rest = TAIL.replace(rest, "").trim()
        val hadHead = HEADWORDS.containsMatchIn("$rest ")
        rest = HEADWORDS.replace("$rest ", "").trim()
        rest = TAIL.replace(rest, "").trim()
        if (rest.isEmpty() || rest in setOf("book", "a book", "the book", "story", "a story", "something", "it", "one", "a bedtime story", "bedtime story")) {
            // "read it" alone is too vague (could be a message); a book / story word or "to me" is needed.
            return if (hadHead || Regex("\\b(book|story|novel|something)\\b").containsMatchIn(asked) || (mentionsWho && rest.isNotEmpty() && rest != "it")) ReadRequest.AnyBook else null
        }
        if (NOT_BOOK.containsMatchIn(rest)) return null
        // "read up on", "read about": not a request to narrate.
        if (Regex("^(up|about|into|between|through|over)\\b").containsMatchIn(rest)) return null
        // Unprompted "read X" without "me / to me", an ask, or a book word: still fine when it begins the message.
        return ReadRequest.Title(rest)
    }

    // ---------------- fuzzy title match ----------------
    private val ARTICLES = setOf("the", "a", "an", "of", "and", "by", "in", "on", "to", "or", "s")

    fun norm(s: String) = s.lowercase().replace(Regex("[\u2018\u2019']"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun tokens(s: String) = norm(s).split(' ').filter { it.isNotEmpty() && it !in ARTICLES }

    private fun near(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        if (minOf(a.length, b.length) < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
        return lev(a, b) <= 1 || (a.length >= 8 && lev(a, b) <= 2)
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** 0..1: how well [query] names a book with [title] / [author] / [aliases]. */
    fun score(query: String, title: String, author: String = "", aliases: List<String> = emptyList()): Double {
        val q0 = norm(query)
        if (q0.isEmpty()) return 0.0
        // "pride and prejudice by jane austen"
        val byIdx = q0.lastIndexOf(" by ")
        val qTitle = if (byIdx > 0) q0.substring(0, byIdx) else q0
        val qAuthor = if (byIdx > 0) q0.substring(byIdx + 4) else ""
        var best = 0.0
        val names = listOf(title, title.substringBefore(":").substringBefore(";").substringBefore("(")) + aliases
        for (name in names) {
            val n = norm(name)
            if (n.isEmpty()) continue
            if (n == qTitle || n.removePrefix("the ") == qTitle.removePrefix("the ")) { best = maxOf(best, 1.0); continue }
            val qt = tokens(qTitle); val nt = tokens(name)
            if (qt.isEmpty() || nt.isEmpty()) continue
            val hitQ = qt.count { a -> nt.any { near(a, it) } }
            val hitN = nt.count { a -> qt.any { near(a, it) } }
            // Mostly about the query being covered; a short query inside a long title still scores well.
            val s = 0.7 * hitQ / qt.size + 0.3 * hitN / nt.size
            best = maxOf(best, s)
        }
        if (qAuthor.isNotEmpty() && author.isNotEmpty()) {
            val at = tokens(author); val qa = tokens(qAuthor)
            val ah = qa.count { a -> at.any { near(a, it) } }.toDouble() / maxOf(1, qa.size)
            best = best * 0.75 + 0.25 * ah
        } else if (byIdx < 0 && author.isNotEmpty()) {
            // "read me something by dickens" / "read me dickens"
            val at = tokens(author); val qt = tokens(q0)
            if (qt.isNotEmpty() && qt.all { a -> at.any { near(a, it) } }) best = maxOf(best, 0.62)
        }
        return best
    }

    const val MATCH_MIN = 0.6

    fun <T> bestMatch(query: String, items: List<T>, title: (T) -> String, author: (T) -> String, aliases: (T) -> List<String>): Pair<T, Double>? =
        items.map { it to score(query, title(it), author(it), aliases(it)) }.filter { it.second >= MATCH_MIN }.maxByOrNull { it.second }
}
