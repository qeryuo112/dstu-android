package com.deepstudent.platform.security

interface DsbkSink {
    val stagedBytes: Long

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    fun commit()

    fun abort()
}
