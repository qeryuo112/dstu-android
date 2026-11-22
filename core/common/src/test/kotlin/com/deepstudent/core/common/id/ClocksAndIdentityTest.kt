package com.deepstudent.core.common.id

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.RandomSource
import com.deepstudent.core.common.time.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class ClocksAndIdentityTest {
    @Test
    fun wallClockRollbackIsObservableWithoutRewritingTime() {
        val readings = ArrayDeque(listOf(Instant.parse("2026-11-15T08:00:00Z"), Instant.parse("2026-11-15T07:59:59Z")))
        val clock = WallClock { readings.removeFirst() }
        val first = clock.now()
        val second = clock.now()
        assertTrue(ClockChecks.wallRegressed(first, second))
        assertEquals(-1000L, ClockChecks.wallDeltaMillis(first, second)!!)
        assertEquals(Instant.parse("2026-11-15T07:59:59Z"), second)
    }

    @Test
    fun wallDeltaOverflowAndInstantExtremesFailClosed() {
        assertNull(ClockChecks.wallDeltaMillis(Instant.MIN, Instant.MAX))
        assertNull(
            ClockChecks.wallDeltaMillis(
                Instant.ofEpochMilli(Long.MIN_VALUE),
                Instant.ofEpochMilli(Long.MAX_VALUE),
            ),
        )
        assertEquals(
            2L,
            ClockChecks.wallDeltaMillis(
                Instant.ofEpochMilli(-1),
                Instant.ofEpochMilli(1),
            )!!,
        )
    }

    @Test
    fun monotonicRollbackAndSignedWrapAreDistinguished() {
        assertNull(ClockChecks.monotonicElapsedNanos(20, 10))
        assertEquals(10L, ClockChecks.monotonicElapsedNanos(10, 20))
        assertEquals(0L, ClockChecks.monotonicElapsedNanos(10, 10))
        assertEquals(
            11L,
            ClockChecks.monotonicElapsedNanos(Long.MAX_VALUE - 5, Long.MIN_VALUE + 5),
        )
        assertNull(ClockChecks.monotonicElapsedNanos(Long.MIN_VALUE, Long.MAX_VALUE))
        assertNull(ClockChecks.monotonicElapsedNanos(0, Long.MIN_VALUE))
        val clock = MonotonicClock { 42L }
        assertEquals(42L, clock.nowNanos())
    }

    @Test
    fun randomSourceIsInjectedNotSelectedByProductionCode() {
        val fixture = Files.readString(
            Paths.get(
                requireNotNull(javaClass.getResource("/s1-03/random-source-v1.json")).toURI(),
            ),
        )
        val fixedBytes = JsonFields.string(
            JsonValue.parseObject(fixture, "random source fixture"),
            "fixedBytesHex",
        ).hexToBytes()
        val fixed = FixedRandomSource(fixedBytes)
        assertEquals("00010203", fixed.nextBytes(4).toHex())
        assertEquals("040506070809", fixed.nextBytes(6).toHex())
        assertEquals(2, fixed.calls)

        val source = RandomSource { count -> ByteArray(count) { (it + 1).toByte() } }
        assertEquals(listOf<Byte>(1, 2, 3, 4), source.nextBytes(4).toList())
    }

    @Test
    fun deviceIdentityMatchesDesktopNormalizeAndShortHash() {
        assertEquals("device-a", DeviceIdentity.normalize("  device-a\u3000").value)
        assertEquals(
            "device-5ec1f7e700f37c3d0b2981d04855fc34b94aaa15457b05ca571817442d228f81",
            DeviceIdentity.normalize("..").value,
        )
        assertEquals("dd5e8641af47e250", DeviceIdentity.shortHash(DeviceId.parse("device-a")))
        assertEquals("03204de92e11fc8c", DeviceIdentity.shortHash(DeviceId.parse("device-1")))
    }

    private class FixedRandomSource(private val bytes: ByteArray) : RandomSource {
        var calls: Int = 0
            private set
        private var offset: Int = 0

        override fun nextBytes(count: Int): ByteArray {
            require(count >= 0 && offset + count <= bytes.size)
            calls++
            val output = bytes.copyOfRange(offset, offset + count)
            offset += count
            return output
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
