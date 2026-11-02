package org.pockettts.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReaderTextTest {
    private fun sentences(text: String, max: Int = 4000): List<String> {
        val doc = ReaderText.prepare(text, Locale.ENGLISH, max)
        return (0 until doc.size).map(doc::text)
    }

    @Test
    fun splitsSentences() {
        assertEquals(
            listOf("The first sentence is here.", "Is this the second one?", "Yes, and this is the third!"),
            sentences("The first sentence is here. Is this the second one? Yes, and this is the third!")
        )
    }

    @Test
    fun mergesShortFragments() {
        val result = sentences("Hi. I met Dr. Smith at the station today. Ok.")
        assertTrue(result.all { it.length >= ReaderText.MIN_SENTENCE_CHARS })
        assertEquals("Hi. I met Dr. Smith at the station today. Ok.", result.joinToString(" "))
    }

    @Test
    fun hardSplitsLongSentences() {
        val long = (1..200).joinToString(" ") { "word$it" } + "."
        val result = sentences(long, max = 100)
        assertTrue(result.size > 1)
        assertTrue(result.all { it.length <= 100 })
        assertEquals(long, result.joinToString(" "))
    }

    @Test
    fun keepsParagraphsAndLines() {
        val doc = ReaderText.prepare("Title line\nSecond line here.\n\nNew paragraph starts here.", Locale.ENGLISH, 4000)
        assertEquals(3, doc.blocks.size)
        assertEquals("Second line here.", doc.blocks[1].text)
    }

    @Test
    fun unwrapsHardWrappedText() {
        val line = "This is a hard wrapped line of a plain text book that continues"
        val text = "$line\n$line\n$line.\n\n$line\n$line.\n"
        val doc = ReaderText.prepare(text, Locale.ENGLISH, 4000)
        assertEquals(2, doc.blocks.size)
        assertFalse(doc.blocks[0].text.contains('\n'))
    }

    @Test
    fun splitsLongParagraphIntoContinuationBlocks() {
        val paragraph = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val doc = ReaderText.prepare(paragraph, Locale.ENGLISH, 4000)
        assertTrue(doc.blocks.size > 1)
        assertFalse(doc.blocks[0].continuation)
        assertTrue(doc.blocks.drop(1).all { it.continuation })
        assertTrue(doc.blocks.all { it.text.length <= ReaderText.MAX_BLOCK_CHARS })
        assertEquals(400, doc.size)
        // Offsets map back to the right sentence.
        val second = doc.blocks[1]
        assertEquals(second.firstSentence, doc.sentenceAt(1, 0))
    }

    @Test
    fun skipsUnspeakableText() {
        assertEquals(0, ReaderText.prepare("  \n***\n\n---  ", Locale.ENGLISH, 4000).size)
    }

    @Test
    fun titleIsShortSingleLine() {
        assertEquals("Short title", ReaderText.title("  Short\n title "))
        val title = ReaderText.title("The quick brown fox jumps over the lazy dog and keeps running far away")
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 41)
    }

    @Test
    fun decodesTextFiles() {
        assertEquals("Grüße", ReaderText.decode("Grüße".toByteArray(Charsets.UTF_8), false))
        assertEquals("Grüße", ReaderText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Grüße".toByteArray(), false))
        assertEquals("Grüße", ReaderText.decode("Grüße".toByteArray(charset("windows-1252")), false))
        assertEquals("Hi", ReaderText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hi".toByteArray(Charsets.UTF_16LE), false))
        // A multi-byte character cut at the size limit is dropped instead of falling back to 1252.
        val cut = "abc€".toByteArray(Charsets.UTF_8).copyOf(5)
        assertEquals("abc", ReaderText.decode(cut, true))
    }
}
