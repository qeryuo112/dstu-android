package com.deepstudent.features.editor.backend

import com.deepstudent.features.editor.api.BlockKind
import com.deepstudent.features.editor.api.BlockPatch
import com.deepstudent.features.editor.api.PatchRejection
import com.deepstudent.features.editor.api.PatchResult
import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.backend.application.DefaultMarkdownCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.CharacterCodingException

class MarkdownCodecTest {
    private val codec = DefaultMarkdownCodec()

    @Test
    fun `mixed newline offsets and exact bytes survive edits`() {
        val original = "# 中文👩‍💻\r\n\r\né and é\r\n\r\n~~~x\n<raw>\n~~~\r\n"
        val doc = codec.parseUtf8(original.toByteArray(Charsets.UTF_8))
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.CODE), doc.blocks.map { it.kind })
        val block = doc.blocks[1]
        assertEquals("é and é", block.range.textOf(original))
        val result = applied(codec.replaceBlock(doc, BlockPatch(block.range, block.range.textOf(original), "新 **内容**")))
        val expected = original.replaceRange(block.range.start, block.range.endExclusive, "新 **内容**")
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), codec.serializeUtf8(result.document))
        assertEquals(block.range, result.previousRange)
        assertEquals(SourceRange(block.range.start, block.range.start + "新 **内容**".length), result.replacementRange)
        assertEquals("~~~x\n<raw>\n~~~", result.document.blocks.last().range.textOf(expected))
    }

    @Test
    fun `frozen and future directives remain opaque while other blocks are editable`() {
        val source = "before\n\n:::future-widget{data=α}\n\nunknown\n\n:::end-future-widget\n\nafter"
        val doc = codec.parse(source)
        assertEquals(3, doc.blocks.size)
        assertTrue(doc.blocks[1].isOpaque)
        assertTrue(doc.blocks[1].range.textOf(source).contains("unknown"))
        val block = doc.blocks.last()
        val changed = applied(codec.replaceBlock(doc, BlockPatch(block.range, "after", "updated")))
        assertEquals(source.replaceRange(block.range.start, block.range.endExclusive, "updated"), changed.document.source)
        assertEquals(PatchRejection.OPAQUE_BLOCK, rejected(codec.replaceBlock(doc,
            BlockPatch(doc.blocks[1].range, doc.blocks[1].range.textOf(source), "replacement"))))
    }

    @Test
    fun `nested future directives cannot expose interior blocks to edits`() {
        val source = """
            :::future

            :::future

            inner

            :::end-future

            protected

            :::end-future

            outside
        """.trimIndent()
        val doc = codec.parse(source)
        assertEquals(2, doc.blocks.size)
        assertTrue(doc.blocks[0].isOpaque)
        assertTrue(doc.blocks[0].range.textOf(source).contains("protected"))
        assertEquals("outside", doc.blocks[1].range.textOf(source))
    }

    @Test
    fun `directive following text on same paragraph still protects the whole container`() {
        val source = "intro\n:::future\n\nbody\n\n:::end-future\n\noutside"
        val doc = codec.parse(source)
        assertEquals(2, doc.blocks.size)
        assertTrue(doc.blocks[0].isOpaque)
        assertTrue(doc.blocks[0].range.textOf(source).contains("body"))
        assertEquals("outside", doc.blocks[1].range.textOf(source))
    }

    @Test
    fun `unsupported inline syntax is protected from lossy replacement`() {
        for (source in listOf("[[note|title]]", "$" + "x$", "> [!toggle]- title", "<custom attr='x'>")) {
            val doc = codec.parse(source)
            assertTrue(source, doc.blocks.single().isOpaque)
            assertEquals(PatchRejection.OPAQUE_BLOCK,
                rejected(codec.replaceBlock(doc, BlockPatch(doc.blocks.single().range, source, "plain"))))
        }
    }

    @Test
    fun `stale nonroot malformed and multi block patches are rejected`() {
        val doc = codec.parse("first\n\nsecond")
        val first = doc.blocks.first().range
        assertEquals(PatchRejection.STALE_SOURCE, rejected(codec.replaceBlock(doc, BlockPatch(first, "old", "new"))))
        assertEquals(PatchRejection.NOT_ROOT_BLOCK,
            rejected(codec.replaceBlock(doc, BlockPatch(SourceRange(0, 2), "fi", "new"))))
        assertEquals(PatchRejection.INVALID_RANGE,
            rejected(codec.replaceBlock(doc, BlockPatch(SourceRange(0, 100), "", "new"))))
        assertEquals(PatchRejection.REPLACEMENT_NOT_SINGLE_BLOCK,
            rejected(codec.replaceBlock(doc, BlockPatch(first, "first", "a\n\nb"))))
        assertEquals(PatchRejection.INVALID_REPLACEMENT,
            rejected(codec.replaceBlock(doc, BlockPatch(first, "first", "\uD800"))))
    }

    @Test
    fun `editing cannot change parsing of adjacent blocks`() {
        val source = "paragraph\n\n- first\n- second\n\nlast"
        val doc = codec.parse(source)
        val block = doc.blocks[1]
        val replacement = "```\nopen"
        assertEquals(PatchRejection.OUTSIDE_STRUCTURE_CHANGED,
            rejected(codec.replaceBlock(doc, BlockPatch(block.range, block.range.textOf(source), replacement))))
    }

    @Test
    fun `irregular source is preserved and invalid UTF-8 or surrogates fail explicitly`() {
        val inputs = listOf("", "\n\r\n \t\n", "  # no final newline", "```lang\nopen", "---\n\n>\n", "a\rb\r\rc")
        for (source in inputs) assertEquals(source, codec.serialize(codec.parse(source)))
        assertThrows(CharacterCodingException::class.java) { codec.parseUtf8(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { codec.parse("\uD800") }
        assertThrows(IllegalArgumentException::class.java) { codec.parse("\uDC00") }
        assertFalse(codec.parse("plain").blocks.single().isOpaque)
    }

    private fun applied(result: PatchResult): PatchResult.Applied = result as PatchResult.Applied
    private fun rejected(result: PatchResult): PatchRejection = (result as PatchResult.Rejected).reason
}
