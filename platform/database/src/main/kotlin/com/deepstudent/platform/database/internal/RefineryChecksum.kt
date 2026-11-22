package com.deepstudent.platform.database.internal

/** Rust refinery 0.9: SipHash-1-3 over name + FF, little-endian i32 version, SQL + FF. */
internal fun refineryChecksum(name: String, version: Int, sql: ByteArray): String {
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    val input = ByteArray(nameBytes.size + sql.size + 6)
    nameBytes.copyInto(input)
    var pos = nameBytes.size
    input[pos++] = 0xff.toByte()
    repeat(4) { input[pos++] = (version ushr (it * 8)).toByte() }
    sql.copyInto(input, pos)
    input[input.lastIndex] = 0xff.toByte()
    return java.lang.Long.toUnsignedString(SipHash13().hash(input))
}

internal class SipHash13 {
    private var v0 = 0x736f6d6570736575L
    private var v1 = 0x646f72616e646f6dL
    private var v2 = 0x6c7967656e657261L
    private var v3 = 0x7465646279746573L

    fun hash(bytes: ByteArray): Long {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            var word = 0L
            repeat(8) { word = word or ((bytes[offset + it].toLong() and 255L) shl (it * 8)) }
            block(word)
            offset += 8
        }
        var last = bytes.size.toLong() shl 56
        for (i in offset until bytes.size) {
            last = last or ((bytes[i].toLong() and 255L) shl ((i - offset) * 8))
        }
        block(last)
        v2 = v2 xor 0xffL
        repeat(3) { round() }
        return v0 xor v1 xor v2 xor v3
    }

    private fun block(word: Long) {
        v3 = v3 xor word
        round()
        v0 = v0 xor word
    }

    private fun round() {
        v0 += v1
        v1 = java.lang.Long.rotateLeft(v1, 13) xor v0
        v0 = java.lang.Long.rotateLeft(v0, 32)
        v2 += v3
        v3 = java.lang.Long.rotateLeft(v3, 16) xor v2
        v0 += v3
        v3 = java.lang.Long.rotateLeft(v3, 21) xor v0
        v2 += v1
        v1 = java.lang.Long.rotateLeft(v1, 17) xor v2
        v2 = java.lang.Long.rotateLeft(v2, 32)
    }
}
