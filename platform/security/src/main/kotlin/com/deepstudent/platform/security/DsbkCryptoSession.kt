package com.deepstudent.platform.security

import java.io.InputStream
import java.io.OutputStream

/** Run-scoped keys. Each format has a fresh salt and its own monotonically unique IV/prefix. */
class DsbkCryptoSession internal constructor(source: DsbkEngine, password: CharArray) : AutoCloseable {
    private val secret = password.copyOf()
    private val keys = DsbkSessionKeys()
    private val engine = source.cached(keys)
    private val v1 = ObjectNonces(engine.newSalt(), DsbkFormat.V1_NONCE_LEN)
    private val v2 = ObjectNonces(
        engine.newSalt().also { salt ->
            if (salt.contentEquals(v1.salt)) salt[0] = (salt[0].toInt() xor 128).toByte()
        },
        DsbkFormat.V2_PREFIX_LEN,
    )
    private var closed = false

    @Synchronized
    fun encryptV1(bytes: ByteArray): ByteArray {
        requireOpen()
        return engine.encryptV1(bytes, secret, v1.salt, DsbkCrypto.PRODUCTION_KDF, v1.next())
    }

    @Synchronized
    fun encryptV2(input: InputStream, output: OutputStream, limit: Long) {
        requireOpen()
        engine.encryptV2(input, output, secret, v2.salt, DsbkCrypto.PRODUCTION_KDF, v2.next(), limit)
    }

    @Synchronized
    fun decrypt(input: InputStream, sink: DsbkSink, limit: Long) {
        requireOpen()
        engine.decrypt(input, secret, sink, limit)
    }

    fun decryptToBytes(input: InputStream, limit: Long): ByteArray {
        val sink = ByteArrayDsbkSink(minOf(limit, DsbkCrypto.MAX_IN_MEMORY_BYTES))
        decrypt(input, sink, limit)
        return sink.bytes()
    }

    @Synchronized
    override fun close() {
        closed = true
        secret.fill('\u0000')
        keys.close()
    }

    private fun requireOpen() = check(!closed) { "closed DSBK session" }
}

private class ObjectNonces(val salt: ByteArray, private val bytes: Int) {
    private var sequence = 0L

    fun next(): ByteArray {
        // v2 leaves 32 bits to the chunk counter and a byte to the final marker.
        check(sequence < (1L shl 56) - 1) { "DSBK session nonce space exhausted" }
        val value = ++sequence
        return ByteArray(bytes) { i ->
            val shift = bytes - i - 1
            if (shift >= 7) 0 else (value ushr (shift * 8)).toByte()
        }
    }
}

internal class DsbkSessionKeys : AutoCloseable {
    private data class Identity(val salt: List<Byte>, val params: DsbkKdfParams)
    private val cached = LinkedHashMap<Identity, ByteArray>(8, 0.75f, true)

    fun wrap(deriver: DsbkKeyDeriver): DsbkKeyDeriver = object : DsbkKeyDeriver {
        override fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
            val identity = Identity(salt.toList(), params)
            cached[identity]?.let { return it.copyOf() }
            val key = deriver.derive(password, salt, params)
            if (key.size != DsbkFormat.KEY_LEN) return key
            if (cached.size >= 4) {
                val oldest = cached.entries.iterator()
                oldest.next().value.fill(0)
                oldest.remove()
            }
            cached[identity] = key.copyOf()
            return key
        }
    }

    override fun close() {
        cached.values.forEach { it.fill(0) }
        cached.clear()
    }
}
