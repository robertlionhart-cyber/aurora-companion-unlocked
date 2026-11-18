package com.aurora.companion.android

import com.aurora.companion.android.BookText.ReadRequest
import org.junit.Assert.*
import org.junit.Test

class BookTextTest {

    private fun sents(t: String): List<String> { val s = BookText.sentences(t); return (0 until s.size).map { t.substring(s.starts[it], s.ends[it]) } }

    @Test fun splitsSentencesAndKeepsAbbreviations() {
        val t = "Mr. Holmes sat down. \"Is it true?\" asked Dr. Watson! J. R. Smith left.\n\nA new paragraph begins here."
        val s = sents(t)
        assertEquals(listOf("Mr. Holmes sat down.", "\"Is it true?\" asked Dr. Watson!", "J. R. Smith left.", "A new paragraph begins here."), s)
        val sp = BookText.sentences(t)
        assertTrue(sp.paraStart[0]); assertFalse(sp.paraStart[1]); assertTrue(sp.paraStart[3])
        assertEquals(3, sp.nextParagraph(0)); assertEquals(0, sp.prevParagraph(2)); assertEquals(0, sp.prevParagraph(3))
        assertEquals(2, sp.indexAt(t.indexOf("J. R.") + 3))
    }

    @Test fun longSentencesAreCut() {
        val t = (1..60).joinToString(", ") { "word$it and more" } + "."
        val s = sents(t)
        assertTrue(s.size > 1)
        assertTrue(s.all { it.length <= BookText.MAX_SENTENCE })
        assertEquals(t.replace(" ", ""), s.joinToString("").replace(" ", ""))
    }

    @Test fun detectsChaptersAndSkipsContents() {
        val para = "It was a dark and stormy night. ".repeat(30)
        val t = "CONTENTS\n\nCHAPTER I\n\nCHAPTER II\n\nCHAPTER I\n\nDown the Rabbit-Hole\n\n$para\n\nCHAPTER II\n\nThe Pool of Tears\n\n$para\n\nCHAPTER III\n\n$para"
        val ch = BookText.chapters(t)
        assertEquals(listOf("Chapter I: Down the Rabbit-Hole", "Chapter II: The Pool of Tears", "Chapter III"), ch.map { it.title })
        assertEquals(1, BookText.chapterAt(ch, ch[1].start + 5))
    }

    @Test fun romanNumeralHeadingsWithTitles() {
        val para = "Squire Trelawney asked me to write it all down, from the beginning to the end. ".repeat(10)
        val toc = "CONTENTS\n\nPART ONE\nThe Old Buccaneer\n\nI.  THE OLD SEA-DOG . . . 11\nII.  BLACK DOG APPEARS . . . 17"
        val t = "$toc\n\nPART ONE--The Old Buccaneer\n\nI\nThe Old Sea-dog at the Admiral Benbow\n\n$para\n\nII\nBlack Dog Appears and Disappears\n\n$para\n\nIII\nThe Black Spot\n\n$para"
        val ch = BookText.chapters(t)
        assertEquals(listOf("Opening", "I: The Old Sea-dog at the Admiral Benbow", "II: Black Dog Appears and Disappears", "III: The Black Spot").drop(if (ch.first().title == "Opening") 0 else 1), ch.map { it.title })
    }

    @Test fun fallsBackToParts() {
        val t = ("Plain prose without headings. ".repeat(40) + "\n\n").repeat(40)
        val ch = BookText.chapters(t)
        assertTrue(ch.size >= 2)
        assertEquals("Part 1", ch[0].title); assertEquals(0, ch[0].start)
    }

    @Test fun stripsGutenbergBoilerplate() {
        val raw = "The Project Gutenberg eBook of Test\r\nLicense stuff\r\n*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\r\nProduced by Someone\r\n\r\nCHAPTER I\r\n\r\nHello there.\r\n*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\r\nMore license"
        val b = BookText.stripGutenberg(raw)
        assertEquals("CHAPTER I\n\nHello there.", b)
        assertFalse(b.contains("License"))
    }

    @Test fun htmlToText() {
        assertEquals("Title\n\nOne & two \u2014 three.", BookText.htmlToText("<html><head><title>x</title></head><body><h1>Title</h1><p>One &amp; two &mdash; three.</p></body></html>"))
    }

    @Test fun readRequests() {
        assertEquals(ReadRequest.Title("pride and prejudice"), BookText.readRequest("Can you read me Pride and Prejudice?"))
        assertEquals(ReadRequest.Title("treasure island"), BookText.readRequest("Aurora, please read Treasure Island to me"))
        assertEquals(ReadRequest.Title("alice in wonderland"), BookText.readRequest("read me the book alice in wonderland"))
        assertEquals(ReadRequest.AnyBook, BookText.readRequest("can you read the book"))
        assertEquals(ReadRequest.AnyBook, BookText.readRequest("Read me a story"))
        assertEquals(ReadRequest.Resume, BookText.readRequest("keep reading please"))
        assertEquals(ReadRequest.Stop, BookText.readRequest("stop reading"))
        assertNull(BookText.readRequest("I read a great book yesterday"))
        assertNull(BookText.readRequest("read my messages"))
        assertNull(BookText.readRequest("can you read it"))
        assertNull(BookText.readRequest("what should I read next?"))
        assertNull(BookText.readRequest("read up on dinosaurs"))
        assertNull(BookText.readRequest("read this to me"))
        assertNull(BookText.readRequest("Can you read?"))
    }

    @Test fun fuzzyMatch() {
        data class B(val t: String, val a: String, val al: List<String> = emptyList())
        val books = listOf(B("Pride and Prejudice", "Jane Austen"), B("Treasure Island", "Robert Louis Stevenson"),
            B("Alice's Adventures in Wonderland", "Lewis Carroll", listOf("alice in wonderland")), B("The Adventures of Sherlock Holmes", "Arthur Conan Doyle"))
        fun m(q: String) = BookText.bestMatch(q, books, { it.t }, { it.a }, { it.al })?.first?.t
        assertEquals("Pride and Prejudice", m("pride and prejudice"))
        assertEquals("Pride and Prejudice", m("pride & predjudice"))
        assertEquals("Treasure Island", m("treasure island by stevenson"))
        assertEquals("Alice's Adventures in Wonderland", m("alice in wonderland"))
        assertEquals("The Adventures of Sherlock Holmes", m("sherlock holmes"))
        assertNull(m("moby dick"))
    }
}
