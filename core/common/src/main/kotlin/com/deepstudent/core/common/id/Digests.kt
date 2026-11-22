package com.deepstudent.core.common.id

import java.security.MessageDigest

object Digests {
    private val hex = "0123456789abcdef".toCharArray()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = toHex(sha256(bytes))

    fun sha256HexUtf8(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun toHex(bytes: ByteArray): String {
        val output = CharArray(bytes.size * 2)
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            output[index++] = hex[value ushr 4]
            output[index++] = hex[value and 0x0f]
        }
        return String(output)
    }
}

object RustText {
    private val singleWhitespace = setOf(
        0x20,
        0x85,
        0xa0,
        0x1680,
        0x2028,
        0x2029,
        0x202f,
        0x205f,
        0x3000,
    )
    private val whitespaceRanges = setOf(0x09..0x0d, 0x2000..0x200a)

    fun trim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isRustWhitespace(value[start])) start++
        while (end > start && isRustWhitespace(value[end - 1])) end--
        return value.substring(start, end)
    }

    private fun isRustWhitespace(character: Char): Boolean {
        val code = character.code
        return code in singleWhitespace || whitespaceRanges.any { code in it }
    }
}
