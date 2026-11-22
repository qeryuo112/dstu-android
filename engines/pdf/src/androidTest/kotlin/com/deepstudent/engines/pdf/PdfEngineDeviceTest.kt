package com.deepstudent.engines.pdf

import android.graphics.Bitmap
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfEngineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, "pdf-$name")
        context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun open(name: String, password: String? = null): PdfDocument {
        val source = ParcelFileDescriptor.open(fixture(name), ParcelFileDescriptor.MODE_READ_ONLY)
        return source.use { PdfEngine.open(it, password) }
    }

    @Test fun chineseTextAndCharacterCoordinates() {
        open("bug_420508260.pdf").use { document ->
            assertEquals(1, document.pageCount())
            val text = document.text(0)
            assertEquals("What is 我的 favorite 食物?", text.text)
            val character = text.characters.first { it.codePoint == '我'.code }
            assertNotNull(character.bounds)
            val bounds = character.bounds!!
            assertEquals(70.13200378417969, bounds.left, 0.001)
            assertEquals(81.7300033569336, bounds.right, 0.001)
            assertEquals(97.44400024414062, bounds.bottom, 0.001)
            assertEquals(106.45600128173828, bounds.top, 0.001)
            val mapped = document.pageToPixel(0, 400, 400, PdfPoint(bounds.left, bounds.top))
            assertEquals(140.0, mapped.x, 0.0)
            assertEquals(187.0, mapped.y, 0.0)
        }
    }

    @Test fun pageRotationAndTextRotation() {
        open("bug_1229106.pdf").use { document ->
            assertEquals(4, document.pageCount())
            assertEquals(90, document.pageInfo(0).rotationDegrees)
            val info = document.pageInfo(0)
            assertEquals(792f, info.widthPoints, 0.01f)
            assertEquals(612f, info.heightPoints, 0.01f)
        }
        open("rotated_text_90.pdf").use { document ->
            assertEquals("Hello,\r\n world!\r\n Goodbye, world!", document.text(0).text)
            assertTrue(document.text(0).characters.any { it.bounds != null })
        }
    }

    @Test fun rotatedPageCoordinatesFollowItsRenderedPixels() {
        open("bug_1229106.pdf").use { document ->
            val bitmap = Bitmap.createBitmap(792, 612, Bitmap.Config.ARGB_8888)
            try {
                document.render(0, bitmap)
                val probes = listOf(PdfPoint(370.0, 175.0), PdfPoint(306.0, 396.0))
                val dark = probes.map { point ->
                    val mapped = document.pageToPixel(0, bitmap.width, bitmap.height, point)
                    val pixel = bitmap.getPixel(mapped.x.toInt().coerceIn(0, 791), mapped.y.toInt().coerceIn(0, 611))
                    (pixel and 0x00ffffff) != 0x00ffffff
                }
                assertTrue("rotated page should have both background and painted pixels", dark.any { it } && dark.any { !it })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun imageOnlyPageRendersWithoutInventingText() {
        open("rotated_image.pdf").use { document ->
            assertEquals("", document.text(0).text)
            val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            try {
                document.render(0, bitmap)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(pixels.any { it != -1 })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun nonSeekableDescriptorsAreRejectedWithoutStealingOwnership() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertPdfError(PdfError.FILE) { PdfEngine.open(pipe[0]).close() }
            assertTrue(pipe[0].fd >= 0)
            assertTrue(pipe[1].fd >= 0)
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun fileSizeAndOpenDocumentBudgetsAreEnforced() {
        val tooLarge = File(context.cacheDir, "pdf-too-large")
        java.io.RandomAccessFile(tooLarge, "rw").use { it.setLength(1_073_741_825) }
        try {
            ParcelFileDescriptor.open(tooLarge, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                assertPdfError(PdfError.LIMIT) { PdfEngine.open(source).close() }
            }
        } finally { tooLarge.delete() }
        val documents = mutableListOf<PdfDocument>()
        try {
            repeat(8) { documents += open("hello_world.pdf") }
            assertPdfError(PdfError.LIMIT) { open("hello_world.pdf").close() }
        } finally { documents.forEach { it.close() } }
        open("hello_world.pdf").close()
    }

    @Test fun passwordAndInvalidPageAreTypedErrors() {
        assertPdfError(PdfError.PASSWORD) { open("encrypted.pdf").close() }
        assertPdfError(PdfError.PASSWORD) { open("encrypted.pdf", "wrong").close() }
        open("encrypted.pdf", "1234").use { document ->
            assertEquals(1, document.pageCount())
            assertPdfError(PdfError.PAGE) { document.pageInfo(1) }
        }
        assertPdfError(PdfError.FORMAT) { open("corrupt-body.pdf").close() }
    }

    @Test fun failedOpensDoNotLeakDescriptors() {
        val encrypted = fixture("encrypted.pdf")
        val corrupt = fixture("corrupt-body.pdf")
        val before = File("/proc/self/fd").list()!!.size
        repeat(30) {
            listOf(encrypted, corrupt).forEach { file ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                    val expected = if (file == encrypted) PdfError.PASSWORD else PdfError.FORMAT
                    assertPdfError(expected) { PdfEngine.open(source, "wrong").close() }
                    assertTrue(source.statSize > 0)
                }
            }
        }
        val delta = File("/proc/self/fd").list()!!.size - before
        Log.i("DstuPdfBudget", "failedOpenIterations=60 fdDelta=$delta")
        assertTrue("failed-open fd delta=$delta", delta <= 1)
    }

    @Test fun closeIsIdempotentAndBorrowedPfdSurvives() {
        val fd = ParcelFileDescriptor.open(fixture("hello_world.pdf"), ParcelFileDescriptor.MODE_READ_ONLY)
        fd.use {
            val document = PdfEngine.open(fd)
            document.close()
            document.close()
            assertTrue(fd.statSize > 0)
            try { document.pageCount(); fail("closed document accepted") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun repeatsBoundNativeHeapAndDescriptors() {
        val file = fixture("hello_world.pdf")
        repeat(3) { open("hello_world.pdf").close() }
        val baselineFd = File("/proc/self/fd").list()!!.size
        val baselineHeap = Debug.getNativeHeapAllocatedSize()
        repeat(50) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                PdfEngine.open(source).use { document ->
                    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    try { document.render(0, bitmap); document.text(0) } finally { bitmap.recycle() }
                }
            }
        }
        val delta = Debug.getNativeHeapAllocatedSize() - baselineHeap
        val fdDelta = File("/proc/self/fd").list()!!.size - baselineFd
        Log.i("DstuPdfBudget", "iterations=50 nativeHeapDelta=$delta fdDelta=$fdDelta")
        assertTrue("native heap delta=$delta", delta < 48L * 1024 * 1024)
        assertTrue("fd delta=$fdDelta", fdDelta <= 2)
    }

    private fun assertPdfError(expected: PdfError, action: () -> Unit) {
        try { action(); fail("expected $expected") }
        catch (error: PdfException) { assertEquals(expected, error.error) }
    }
}
