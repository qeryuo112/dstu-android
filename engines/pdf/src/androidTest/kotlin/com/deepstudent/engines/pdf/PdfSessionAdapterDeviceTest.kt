package com.deepstudent.engines.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.services.engine.api.pdf.PdfFailure
import com.deepstudent.services.engine.api.pdf.PdfFailureCode
import com.deepstudent.services.engine.api.pdf.PdfPoint
import com.deepstudent.services.engine.api.pdf.PdfRasterSize
import com.deepstudent.services.engine.api.pdf.PdfSession
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfSessionAdapterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, "session-$name")
        context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun open(name: String, password: String? = null): PdfSession =
        ParcelFileDescriptor.open(fixture(name), ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            PdfSessionFactory.open(source, password)
        }

    @Test fun pageTextBoundsAndPixelsSurviveSourceAndSessionClose() {
        val session = open("bug_420508260.pdf")
        val text: com.deepstudent.services.engine.api.pdf.PdfPageText
        val bitmap: com.deepstudent.services.engine.api.pdf.PdfBitmap
        try {
            assertEquals(1, session.pageCount())
            text = session.text(0)
            assertEquals("What is 我的 favorite 食物?", text.text)
            val glyph = text.glyphs.first { it.codePoint == '我'.code }
            assertNotNull(glyph.bounds)
            val bounds = glyph.bounds!!
            assertEquals(70.13200378417969, bounds.left, 0.001)
            assertEquals(106.45600128173828, bounds.top, 0.001)
            val size = PdfRasterSize(400, 400)
            val mapped = session.pageToPixel(0, size, PdfPoint(bounds.left, bounds.top))
            assertEquals(140.0, mapped.x, 0.0)
            assertEquals(187.0, mapped.y, 0.0)
            bitmap = session.render(0, size)
        } finally {
            session.close()
        }
        assertTrue(text.text.contains("我的"))
        val pixels = bitmap.copyPixels()
        assertEquals(160_000, pixels.size)
        assertTrue(pixels.any { it != -1 })
        val original = pixels[0]
        pixels[0] = original.inv()
        assertEquals(original, bitmap.copyPixels()[0])
    }

    @Test fun rotatedPageCoordinatesMatchRenderedPixels() {
        open("bug_1229106.pdf").use { session ->
            assertEquals(4, session.pageCount())
            val info = session.pageInfo(0)
            assertEquals(90, info.rotationDegrees)
            assertEquals(792f, info.widthPoints, 0.01f)
            assertEquals(612f, info.heightPoints, 0.01f)
            val size = PdfRasterSize(792, 612)
            val pixels = session.render(0, size).copyPixels()
            val dark = listOf(PdfPoint(370.0, 175.0), PdfPoint(306.0, 396.0)).map { point ->
                val mapped = session.pageToPixel(0, size, point)
                val x = mapped.x.toInt().coerceIn(0, size.width - 1)
                val y = mapped.y.toInt().coerceIn(0, size.height - 1)
                pixels[y * size.width + x] and 0x00ffffff != 0x00ffffff
            }
            assertTrue(dark.any { it } && dark.any { !it })
        }
    }

    @Test fun imageOnlyPageHasNoInventedTextAndStillRenders() {
        open("rotated_image.pdf").use { session ->
            assertEquals("", session.text(0).text)
            assertTrue(session.render(0, PdfRasterSize(200, 200)).copyPixels().any { it != -1 })
        }
    }

    @Test fun payloadUsesTheSameArgbPixelsAsTheNativeBitmap() {
        ParcelFileDescriptor.open(fixture("rotated_image.pdf"), ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            PdfEngine.open(source).use { document ->
                PdfSessionFactory.open(source).use { session ->
                    val size = PdfRasterSize(200, 200)
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    try {
                        document.render(0, bitmap)
                        val expected = IntArray(size.pixelCount)
                        bitmap.getPixels(expected, 0, size.width, 0, 0, size.width, size.height)
                        assertArrayEquals(expected, session.render(0, size).copyPixels())
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test fun nativeFailuresAndInvalidArgumentsAreTyped() {
        assertFailure(PdfFailureCode.PASSWORD) { open("encrypted.pdf").close() }
        assertFailure(PdfFailureCode.PASSWORD) { open("encrypted.pdf", "wrong").close() }
        assertFailure(PdfFailureCode.FORMAT) { open("corrupt-body.pdf").close() }
        assertFailure(PdfFailureCode.INVALID_ARGUMENT) { open("hello_world.pdf", "bad\u0000password").close() }
        open("encrypted.pdf", "1234").use { session ->
            assertEquals(1, session.pageCount())
            assertFailure(PdfFailureCode.PAGE) { session.pageInfo(1) }
            assertFailure(PdfFailureCode.PAGE) { session.render(-1, PdfRasterSize(1, 1)) }
            assertFailure(PdfFailureCode.INVALID_ARGUMENT) {
                session.pageToPixel(0, PdfRasterSize(1, 1), PdfPoint(Double.NaN, 0.0))
            }
        }
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertFailure(PdfFailureCode.FILE) { PdfSessionFactory.open(pipe[0]).close() }
            assertTrue(pipe[0].fd >= 0)
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test fun everyNativeErrorCodeMapsToItsPublicEquivalent() {
        val expected = listOf(
            PdfFailureCode.UNKNOWN, PdfFailureCode.FILE, PdfFailureCode.FORMAT,
            PdfFailureCode.PASSWORD, PdfFailureCode.SECURITY, PdfFailureCode.PAGE, PdfFailureCode.LIMIT,
        )
        val failures = PdfError.entries.map { PdfException(it).toFailure() }
        assertEquals(expected, failures.map { it.code })
        assertTrue(failures.all { it.cause == null })
    }

    @Test fun closeIsIdempotentAndReleasesNativeHandleWithoutClosingBorrowedPfd() {
        val file = fixture("hello_world.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            val sessions = mutableListOf<PdfSession>()
            try {
                repeat(8) { sessions += PdfSessionFactory.open(source) }
                assertFailure(PdfFailureCode.LIMIT) { PdfSessionFactory.open(source).close() }
                val first = sessions.removeAt(0)
                first.close()
                first.close()
                assertFailure(PdfFailureCode.CLOSED) { first.pageCount() }
                assertFailure(PdfFailureCode.CLOSED) { first.pageInfo(0) }
                assertFailure(PdfFailureCode.CLOSED) { first.text(0) }
                assertFailure(PdfFailureCode.CLOSED) { first.render(0, PdfRasterSize(1, 1)) }
                assertFailure(PdfFailureCode.CLOSED) {
                    first.pageToPixel(0, PdfRasterSize(1, 1), PdfPoint(0.0, 0.0))
                }
                assertTrue(source.statSize > 0)
                PdfSessionFactory.open(source).use { assertEquals(1, it.pageCount()) }
            } finally {
                sessions.forEach { it.close() }
            }
        }
    }

    @Test fun successfulAndFailedOpensDoNotLeakDescriptors() {
        val file = fixture("hello_world.pdf")
        val encrypted = fixture("encrypted.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfSessionFactory.open(it).close() }
        val before = File("/proc/self/fd").list()!!.size
        repeat(30) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                PdfSessionFactory.open(source).close()
                assertTrue(source.statSize > 0)
            }
            ParcelFileDescriptor.open(encrypted, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                assertFailure(PdfFailureCode.PASSWORD) { PdfSessionFactory.open(source, "wrong").close() }
                assertTrue(source.statSize > 0)
            }
        }
        val delta = File("/proc/self/fd").list()!!.size - before
        assertTrue("descriptor delta=$delta", delta <= 1)
    }

    private fun assertFailure(expected: PdfFailureCode, action: () -> Unit) {
        try {
            action()
            fail("expected $expected")
        } catch (error: PdfFailure) {
            assertEquals(expected, error.code)
        }
    }
}
