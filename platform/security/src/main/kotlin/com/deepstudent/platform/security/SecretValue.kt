package com.deepstudent.platform.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

@JvmInline
value class CredentialKey private constructor(val value: String) {
    init {
        require(value.matches(KEY_PATTERN)) { "credential key contains unsupported characters" }
    }

    override fun toString(): String = "CredentialKey(redacted)"

    companion object {
        private val KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        fun of(value: String): CredentialKey = CredentialKey(value)
    }
}

/**
 * Owns a mutable credential buffer. [use] deliberately exposes the owned bytes
 * without copying; callers must not retain or close the value inside the block.
 */
class SecretBytes private constructor(
    private var storage: ByteArray,
) : AutoCloseable {
    private var closed = false

    val size: Int
        @Synchronized get() {
            checkOpen()
            return storage.size
        }

    @Synchronized
    fun <T> use(block: (ByteArray) -> T): T {
        checkOpen()
        return block(storage)
    }

    @Synchronized
    fun copy(): SecretBytes {
        checkOpen()
        return SecretBytes(storage.copyOf())
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            storage.fill(0)
            closed = true
        }
    }

    @Synchronized
    override fun toString(): String =
        "SecretBytes(size=${storage.size}, value=redacted, closed=$closed)"

    private fun checkOpen() = check(!closed) { "credential value is closed" }

    companion object {
        const val MAX_SIZE_BYTES = 1024 * 1024

        fun fromUtf8(value: CharArray): SecretBytes {
            val encoded = Utf8Bytes.encode(value)
            return takeOwnership(encoded)
        }

        fun fromBytes(value: ByteArray): SecretBytes {
            require(value.size <= MAX_SIZE_BYTES) { "credential value exceeds its budget" }
            return takeOwnership(value.copyOf())
        }

        internal fun takeOwnership(value: ByteArray): SecretBytes {
            if (value.size > MAX_SIZE_BYTES) {
                value.wipe()
                throw IllegalArgumentException("credential value exceeds its budget")
            }
            return SecretBytes(value)
        }
    }
}

internal object Utf8Bytes {
    /**
     * Matches Node's `Buffer.from(value, 'utf8')`: an unpaired surrogate is
     * replaced with U+FFFD instead of failing. Desktop DSBK derives the
     * password key from those exact bytes, so replacement must be identical.
     */
    fun encode(value: CharArray): ByteArray {
        // UTF-8 uses at least one byte per UTF-16 code unit (including replacement).
        require(value.size <= SecretBytes.MAX_SIZE_BYTES) { "secret input exceeds its budget" }
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte()))
        val encoded = encoder.encode(CharBuffer.wrap(value))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        wipe(encoded)
        if (bytes.size > SecretBytes.MAX_SIZE_BYTES) {
            bytes.wipe()
            throw IllegalArgumentException("secret input exceeds its budget")
        }
        return bytes
    }

    private fun wipe(buffer: ByteBuffer) {
        if (buffer.hasArray()) buffer.array().fill(0)
    }
}

internal fun ByteArray.wipe() {
    fill(0)
}
