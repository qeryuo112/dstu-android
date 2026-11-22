package com.deepstudent.core.common.text

import java.util.Arrays

object Utf8Text {
    fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    fun byteLength(value: String): Int = bytes(value).size

    /** Unsigned UTF-8 byte order, matching Node Buffer.compare. */
    fun compare(left: String, right: String): Int =
        Arrays.compareUnsigned(bytes(left), bytes(right))
}
