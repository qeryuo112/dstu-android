package com.deepstudent.features.editor.backend.domain

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object SourceText {
    fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun encode(source: String): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(source))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    fun requireValid(source: String) {
        var index = 0
        while (index < source.length) {
            val char = source[index++]
            if (char.isHighSurrogate()) {
                require(index < source.length && source[index].isLowSurrogate()) { "Unpaired surrogate" }
                index++
            } else {
                require(!char.isLowSurrogate()) { "Unpaired surrogate" }
            }
        }
    }
}

/** JetBrains Markdown expects LF; boundaries translate back to the unchanged original. */
internal class ParserSource(source: String) {
    val text: String
    private val offsets: IntArray

    init {
        val normalized = StringBuilder(source.length)
        val positions = ArrayList<Int>(source.length + 1)
        var index = 0
        positions.add(0)
        while (index < source.length) {
            val char = source[index++]
            if (char == '\r' && index < source.length && source[index] == '\n') index++
            normalized.append(if (char == '\r') '\n' else char)
            positions.add(index)
        }
        text = normalized.toString()
        offsets = positions.toIntArray()
    }

    fun originalOffset(normalizedOffset: Int): Int = offsets[normalizedOffset]
}
