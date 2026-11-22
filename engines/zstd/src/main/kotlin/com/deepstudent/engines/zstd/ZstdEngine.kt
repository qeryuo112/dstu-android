package com.deepstudent.engines.zstd

class ZstdEngine {
    fun encode(input: ByteArray): ByteArray = checked(input, ZstdNative::encode)
    fun decode(input: ByteArray): ByteArray = checked(input, ZstdNative::decode)

    private fun checked(input: ByteArray, operation: (ByteArray) -> ByteArray): ByteArray {
        require(input.size <= MAX_BYTES) { "ZSTD_INPUT_LIMIT" }
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        val result = operation(input)
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        return result
    }

    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}

internal object ZstdNative {
    init { System.loadLibrary("dstu_zstd") }
    external fun encode(input: ByteArray): ByteArray
    external fun decode(input: ByteArray): ByteArray
}
