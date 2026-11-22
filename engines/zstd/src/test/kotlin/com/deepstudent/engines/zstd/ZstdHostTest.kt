package com.deepstudent.engines.zstd

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZstdHostTest {
    @Test fun roundTripInvalidAndLimits() {
        val engine = ZstdEngine()
        val body = "中文🙂 9007199254740993".toByteArray()
        assertArrayEquals(body, engine.decode(engine.encode(body)))
        assertArrayEquals(ByteArray(0), engine.decode(engine.encode(ByteArray(0))))
        assertThrows(IllegalArgumentException::class.java) { engine.decode(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { engine.decode(ByteArray(ZstdEngine.MAX_BYTES + 1)) }
        assertArrayEquals(ByteArray(ZstdEngine.MAX_BYTES), engine.decode(engine.encode(ByteArray(ZstdEngine.MAX_BYTES))))
    }

    @Test fun desktopGoldenDecodeAndExportForDesktopDecode() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { it.resolve("config/baseline/sync-golden.json").isFile }
        val json = root.resolve("config/baseline/sync-golden.json").readText()
        val hex = Regex("\"zstdHex\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)!!.groupValues[1]
        val compressed = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val engine = ZstdEngine()
        val plain = engine.decode(compressed)
        val hash = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it.toInt() and 255) }
        org.junit.Assert.assertEquals("c7142b6d0513b6732039f138e55581ec8014742f81278051273432d8ccc8165f", hash)
        val output = System.getProperty("dstu.zstd.crosscheck")
        if (output != null) File(output).writeBytes(engine.encode(plain))
    }
}
