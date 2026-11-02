package org.pockettts.android.engine

import java.text.BreakIterator
import java.util.Locale

/** One speakable sentence: a range inside the text of [block]. */
internal data class Sentence(val block: Int, val start: Int, val end: Int)

/** A displayed paragraph (or part of a long paragraph) of the reader. */
internal data class ReaderBlock(val text: String, val firstSentence: Int, val sentenceCount: Int, val continuation: Boolean)

/** Text prepared for the player: display blocks plus the global sentence list. */
internal class ReaderDocument(val blocks: List<ReaderBlock>, val sentences: List<Sentence>) {
    /** Cumulative character counts, used to estimate elapsed and total listening time. */
    private val charsBefore: IntArray = IntArray(sentences.size + 1).also { sums ->
        sentences.forEachIndexed { index, s -> sums[index + 1] = sums[index] + (s.end - s.start) }
    }

    val size: Int get() = sentences.size
    val totalChars: Int get() = charsBefore[sentences.size]

    fun text(index: Int): String = sentences[index].let { blocks[it.block].text.substring(it.start, it.end) }
    fun charsBefore(index: Int): Int = charsBefore[index.coerceIn(0, sentences.size)]

    /** The sentence containing [offset] inside block [block], or the nearest one. */
    fun sentenceAt(block: Int, offset: Int): Int? {
        val b = blocks.getOrNull(block) ?: return null
        if (b.sentenceCount == 0) return null
        for (i in b.firstSentence until b.firstSentence + b.sentenceCount) {
            if (offset < sentences[i].end) return i
        }
        return b.firstSentence + b.sentenceCount - 1
    }
}

internal object ReaderText {
    /** Fragments shorter than this (for example “Dr.” or “1.”) are merged into their neighbour. */
    const val MIN_SENTENCE_CHARS = 12
    const val MAX_BLOCK_CHARS = 2_400

    fun prepare(text: String, locale: Locale, maxSentenceChars: Int): ReaderDocument {
        val blocks = ArrayList<ReaderBlock>()
        val sentences = ArrayList<Sentence>()
        for (paragraph in paragraphs(text)) {
            val ranges = sentenceRanges(paragraph, locale, maxSentenceChars)
            if (ranges.isEmpty()) continue
            // Long paragraphs are displayed as several blocks so each TextView stays small.
            var blockStart = ranges.first().first
            var pending = ArrayList<IntRange>()
            fun flush(continuation: Boolean) {
                if (pending.isEmpty()) return
                val end = pending.last().last + 1
                val blockText = paragraph.substring(blockStart, end)
                val blockIndex = blocks.size
                blocks += ReaderBlock(blockText, sentences.size, pending.size, continuation)
                pending.forEach { sentences += Sentence(blockIndex, it.first - blockStart, it.last + 1 - blockStart) }
                pending = ArrayList()
            }
            var firstOfParagraph = true
            for (range in ranges) {
                if (pending.isNotEmpty() && range.last + 1 - blockStart > MAX_BLOCK_CHARS) {
                    flush(!firstOfParagraph)
                    firstOfParagraph = false
                    blockStart = range.first
                }
                pending += range
            }
            flush(!firstOfParagraph)
        }
        return ReaderDocument(blocks, sentences)
    }

    /**
     * Splits text into display paragraphs. Hard-wrapped plain text (lines of similar length
     * with blank lines between paragraphs, as in many .txt books) is unwrapped; otherwise
     * every line break starts a new paragraph.
     */
    fun paragraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace(" ", " ").removePrefix("﻿")
        val chunks = normalized.split(Regex("\n[ \t]*\n+"))
        val lines = normalized.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val wrapped = chunks.size > 1 && lines.size >= chunks.size * 2 &&
            lines.count { it.length in 45..110 } >= lines.size * 0.6
        val result = ArrayList<String>()
        for (chunk in chunks) {
            val parts = if (wrapped) listOf(chunk.replace('\n', ' ')) else chunk.split('\n')
            parts.map { it.replace(Regex("[ \t\u000B\u000C]+"), " ").trim() }
                .filter { part -> part.any { it.isLetterOrDigit() } }
                .forEach { result += it }
        }
        return result
    }

    /** Sentence ranges (inclusive) of one paragraph, merged and hard-split for the TTS limit. */
    fun sentenceRanges(paragraph: String, locale: Locale, maxSentenceChars: Int): List<IntRange> {
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(paragraph)
        val raw = ArrayList<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            trim(paragraph, start, end)?.let { raw += it }
            start = end
            end = iterator.next()
        }
        // Merge fragments that are too short or contain nothing speakable.
        val merged = ArrayList<IntRange>()
        var carry: IntRange? = null
        for (range in raw) {
            val current = carry?.let { it.first..range.last } ?: range
            carry = if (isTooShort(paragraph, current)) current else {
                merged += current
                null
            }
        }
        carry?.let { tail ->
            if (merged.isEmpty()) merged += tail else merged[merged.size - 1] = merged.last().first..tail.last
        }
        return merged.flatMap { hardSplit(paragraph, it, maxSentenceChars.coerceAtLeast(MIN_SENTENCE_CHARS * 2)) }
    }

    private fun isTooShort(text: String, range: IntRange): Boolean =
        range.last - range.first + 1 < MIN_SENTENCE_CHARS || text.substring(range.first, range.last + 1).none { it.isLetterOrDigit() }

    private fun trim(text: String, start: Int, end: Int): IntRange? {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (s < e) s until e else null
    }

    /** Splits a range longer than [max] at a clause or word boundary. */
    private fun hardSplit(text: String, range: IntRange, max: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = range.first
        val end = range.last + 1
        while (end - start > max) {
            val limit = start + max
            var cut = -1
            for (i in limit - 1 downTo start + max / 2) {
                if (text[i] in ",;:—–)" && i + 1 < end && text[i + 1].isWhitespace()) { cut = i + 1; break }
            }
            if (cut < 0) {
                for (i in limit - 1 downTo start + max / 3) if (text[i].isWhitespace()) { cut = i; break }
            }
            if (cut < 0) cut = limit
            trim(text, start, cut)?.let { out += it }
            start = cut
        }
        trim(text, start, end)?.let { out += it }
        return out
    }

    /**
     * Decodes a plain-text file: honours a UTF-8/UTF-16 byte-order mark, otherwise strict
     * UTF-8 with a Windows-1252 fallback. [truncated] means the bytes were cut at a limit,
     * so a partial trailing UTF-8 sequence is dropped first.
     */
    fun decode(bytes: ByteArray, truncated: Boolean): String {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        when {
            startsWith(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> return String(bytes, 2, (bytes.size - 2) and 1.inv(), Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> return String(bytes, 2, (bytes.size - 2) and 1.inv(), Charsets.UTF_16BE)
        }
        var length = bytes.size
        if (truncated) {
            var lead = length - 1
            while (lead > 0 && length - lead < 4 && (bytes[lead].toInt() and 0xC0) == 0x80) lead--
            if (lead >= 0 && (bytes[lead].toInt() and 0xC0) == 0xC0) length = lead
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, length)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(bytes, 0, bytes.size, charset("windows-1252"))
        }
    }

    /** First ~40 characters of the text on one line, for list titles. */
    fun title(text: String, maxChars: Int = 40): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        if (line.length <= maxChars) return line
        val cut = line.lastIndexOf(' ', maxChars).takeIf { it >= maxChars / 2 } ?: maxChars
        return line.substring(0, cut).trimEnd(',', ';', ':', '.', ' ', '-', '—') + "…"
    }
}
