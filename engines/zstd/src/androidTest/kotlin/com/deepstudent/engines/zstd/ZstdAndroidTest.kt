package com.deepstudent.engines.zstd

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZstdAndroidTest {
    @Test fun goldenRoundTripAndLimits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val golden = JSONObject(context.assets.open("sync-golden.json").bufferedReader().use { it.readText() })
        val hex = golden.getJSONObject("compression").getJSONObject("expected").getString("zstdHex")
        val input = golden.getJSONObject("lossless").getJSONObject("input").getString("text").toByteArray()
        val frame = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val engine = ZstdEngine()
        assertArrayEquals(input, engine.decode(frame))
        assertArrayEquals(input, engine.decode(engine.encode(input)))
        assertArrayEquals(ByteArray(0), engine.decode(engine.encode(ByteArray(0))))
        assertThrows(IllegalArgumentException::class.java) { engine.decode(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { engine.encode(ByteArray(ZstdEngine.MAX_BYTES + 1)) }
        val compressedBomb = engine.encode(ByteArray(ZstdEngine.MAX_BYTES))
        assertArrayEquals(ByteArray(ZstdEngine.MAX_BYTES), engine.decode(compressedBomb))
    }
}
