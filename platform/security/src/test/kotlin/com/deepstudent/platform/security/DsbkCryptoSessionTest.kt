package com.deepstudent.platform.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * S2.29 session-level evidence: one derivation per exact salt/KDF parameter pair,
 * fresh object material for every v1/v2 object, old public decoders still read both
 * formats, and close makes every session operation unusable.
 */
class DsbkCryptoSessionTest {
    private val password = "s2.29-session-🔐".toCharArray()

    @Test
    fun sameSessionCachesBySaltAndParametersWhileEveryObjectUsesFreshMaterial() {
        val deriver = RecordingSessionDeriver()
        val crypto = DsbkCrypto(DsbkEngine(deriver, SequenceRandomBytes()), SecurityAudit.NONE)
        val first = ByteArray(97) { (it * 17 + 3).toByte() }
        val second = ByteArray(DsbkFormat.STREAM_CHUNK + 31) { (it * 29 + 11).toByte() }

        val session = crypto.openSession(password)
        val v1First: ByteArray
        val v1Second: ByteArray
        val v2First: ByteArray
        val v2Second: ByteArray
        try {
            v1First = session.encryptV1(first)
            v1Second = session.encryptV1(second.copyOfRange(0, 97))
            v2First = encryptV2(session, first)
            v2Second = encryptV2(session, second)

            val v1HeaderFirst = DsbkHeaderCodec.parseV1(v1First.copyOf(DsbkFormat.V1_HEADER_LEN))
            val v1HeaderSecond = DsbkHeaderCodec.parseV1(v1Second.copyOf(DsbkFormat.V1_HEADER_LEN))
            val v2HeaderFirst = DsbkHeaderCodec.parseV2(v2First.copyOf(DsbkFormat.V2_HEADER_LEN))
            val v2HeaderSecond = DsbkHeaderCodec.parseV2(v2Second.copyOf(DsbkFormat.V2_HEADER_LEN))

            assertArrayEquals(v1HeaderFirst.salt, v1HeaderSecond.salt)
            assertEquals(DsbkCrypto.PRODUCTION_KDF, v1HeaderFirst.params)
            assertFalse(v1HeaderFirst.nonce.contentEquals(v1HeaderSecond.nonce))
            assertNotEquals(v1HeaderFirst.nonce.toHex(), v1HeaderSecond.nonce.toHex())

            assertArrayEquals(v2HeaderFirst.salt, v2HeaderSecond.salt)
            assertEquals(DsbkCrypto.PRODUCTION_KDF, v2HeaderFirst.params)
            assertFalse(v2HeaderFirst.prefix.contentEquals(v2HeaderSecond.prefix))
            assertNotEquals(v2HeaderFirst.prefix.toHex(), v2HeaderSecond.prefix.toHex())

            assertFalse(v1HeaderFirst.salt.contentEquals(v2HeaderFirst.salt))
            assertEquals(2, deriver.calls.size)
            assertEquals(1, deriver.calls.count { it.saltHex == v1HeaderFirst.salt.toHex() })
            assertEquals(1, deriver.calls.count { it.saltHex == v2HeaderFirst.salt.toHex() })

            assertArrayEquals(first, session.decryptToBytes(ByteArrayInputStream(v1First), first.size.toLong()))
            assertArrayEquals(second.copyOfRange(0, 97), session.decryptToBytes(ByteArrayInputStream(v1Second), 97))
            assertArrayEquals(first, session.decryptToBytes(ByteArrayInputStream(v2First), first.size.toLong()))
            assertArrayEquals(second, session.decryptToBytes(ByteArrayInputStream(v2Second), second.size.toLong()))
            assertEquals(2, deriver.calls.size)
        } finally {
            session.close()
        }

        // The old public one-shot entry points are independent of the new session cache.
        assertArrayEquals(first, DsbkCrypto.production().decryptToBytes(ByteArrayInputStream(v1First), password, first.size.toLong()))
        assertArrayEquals(second, DsbkCrypto.production().decryptToBytes(ByteArrayInputStream(v2Second), password, second.size.toLong()))

        assertThrows(IllegalStateException::class.java) { session.encryptV1(first) }
        assertThrows(IllegalStateException::class.java) {
            session.encryptV2(ByteArrayInputStream(first), ByteArrayOutputStream(), first.size.toLong())
        }
        assertThrows(IllegalStateException::class.java) {
            session.decryptToBytes(ByteArrayInputStream(v1First), first.size.toLong())
        }
    }

    private fun encryptV2(session: DsbkCryptoSession, plain: ByteArray): ByteArray =
        ByteArrayOutputStream(plain.size + 128).also { output ->
            session.encryptV2(ByteArrayInputStream(plain), output, plain.size.toLong())
        }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class RecordingSessionDeriver(private val delegate: DsbkKeyDeriver = Argon2idKeyDeriver()) : DsbkKeyDeriver {
    data class Call(val saltHex: String, val params: DsbkKdfParams)

    val calls = mutableListOf<Call>()

    override fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        calls += Call(salt.joinToString("") { "%02x".format(it.toInt() and 0xff) }, params)
        return delegate.derive(password, salt, params)
    }
}

private class SequenceRandomBytes : RandomBytesSource {
    private var call = 0

    override fun next(length: Int): ByteArray = ByteArray(length) { index ->
        ((call * 41 + index * 13 + 7) and 0xff).toByte()
    }.also { call++ }
}
