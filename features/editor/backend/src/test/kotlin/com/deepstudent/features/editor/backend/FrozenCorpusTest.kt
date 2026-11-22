package com.deepstudent.features.editor.backend

import com.deepstudent.features.editor.api.BlockKind
import com.deepstudent.features.editor.api.BlockNode
import com.deepstudent.features.editor.api.InlineKind
import com.deepstudent.features.editor.api.InlineNode
import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.backend.application.DefaultMarkdownCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FrozenCorpusTest {
    private val codec = DefaultMarkdownCodec()

    @Test
    fun `all frozen inputs round trip against desktop capture`() {
        val inputs = resource("content-inputs.json")["markdown"]!!.jsonArray
        val desktop = resource("content.json")["markdown"]!!.jsonArray
        assertEquals(9, inputs.size)
        assertEquals(inputs.size, desktop.size)
        val captures = desktop.associateBy { it.jsonObject.string("id") }
        inputs.forEach { item -> verifySample(item.jsonObject, captures) }
    }

    @Test
    fun `desktop standard types and offsets correspond to parsed blocks`() {
        val rows = resource("content.json")["markdown"]!!.jsonArray
        val common = rows.first { it.jsonObject.string("id") == "commonmark-gfm" }.jsonObject
        val text = common.string("text")
        val parsed = codec.parse(text)
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.LIST, BlockKind.TABLE, BlockKind.PARAGRAPH),
            parsed.blocks.map { it.kind })
        assertDesktopRanges(common, parsed.blocks.map { it.range.start to it.range.endExclusive })
        val kinds = flatten(parsed.blocks[1].inlines).map { it.kind }.toSet()
        assertTrue(kinds.containsAll(setOf(InlineKind.STRONG, InlineKind.EMPHASIS, InlineKind.STRIKETHROUGH, InlineKind.CODE)))
        val unicode = rows.first { it.jsonObject.string("id") == "unicode-crlf" }.jsonObject
        val unicodeDoc = codec.parse(unicode.string("text"))
        assertDesktopRanges(unicode, unicodeDoc.blocks.map { it.range.start to it.range.endExclusive })
    }

    @Test
    fun `frozen desktop corpus retains all 150 byte fixtures`() {
        val corpus = resource("corpus.json")["markdown"]!!.jsonObject["samples"]!!.jsonArray
        assertEquals(150, corpus.size)
        corpus.forEach { item ->
            val row = item.jsonObject
            val bytes = row.string("text").toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(row.string("id"), row["input"]!!.jsonObject.string("sha256"), hash)
            val document = codec.parseUtf8(bytes)
            assertArrayEquals(row.string("id"), bytes, codec.serializeUtf8(document))
            document.blocks.forEach { verifyBlock(it, SourceRange(0, document.source.length), document.source) }
        }
    }

    @Test
    fun `desktop extension ranges remain guarded as opaque`() {
        val rows = resource("content.json")["markdown"]!!.jsonArray
        val ids = listOf("callout", "toggle", "columns", "wiki-mention-reference")
        for (id in ids) {
            val row = rows.first { it.jsonObject.string("id") == id }.jsonObject
            val document = codec.parse(row.string("text"))
            assertTrue(id, document.blocks.single().isOpaque)
            assertDesktopRanges(row, document.blocks.map { it.range.start to it.range.endExclusive })
        }
    }

    private fun verifyBlock(node: BlockNode, parent: SourceRange, source: String) {
        verifyRange(node.range, parent, source)
        node.blocks.forEach { verifyBlock(it, node.range, source) }
        node.inlines.forEach { verifyInline(it, node.range, source) }
    }

    private fun verifyInline(node: InlineNode, parent: SourceRange, source: String) {
        verifyRange(node.range, parent, source)
        node.children.forEach { verifyInline(it, node.range, source) }
    }

    private fun verifyRange(range: SourceRange, parent: SourceRange, source: String) {
        assertTrue("$range not inside $parent", range.start >= parent.start && range.endExclusive <= parent.endExclusive)
        range.textOf(source)
    }

    private fun verifySample(input: JsonObject, captures: Map<String, kotlinx.serialization.json.JsonElement>) {
        val id = input.string("id")
        val captured = captures.getValue(id).jsonObject
        val source = input.string("text")
        assertEquals(id, source, captured.string("text"))
        assertTrue(id, captured["expected"]!!.jsonObject["children"] is JsonArray)
        val bytes = source.toByteArray(Charsets.UTF_8)
        val document = codec.parseUtf8(bytes)
        assertEquals(id, source, codec.serialize(document))
        assertArrayEquals(id, bytes, codec.serializeUtf8(document))
        document.blocks.forEach { block -> assertEquals(id, block.range.textOf(source), block.range.textOf(document.source)) }
    }

    private fun assertDesktopRanges(row: JsonObject, actual: List<Pair<Int, Int>>) {
        val children = row["expected"]!!.jsonObject["children"]!!.jsonArray
        val expected = children.map { element ->
            val position = element.jsonObject["position"]!!.jsonObject
            position["start"]!!.jsonObject["offset"]!!.jsonPrimitive.content.toInt() to
                position["end"]!!.jsonObject["offset"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals(expected, actual)
    }

    private fun flatten(nodes: List<InlineNode>): List<InlineNode> =
        nodes.flatMap { listOf(it) + flatten(it.children) }

    private fun resource(name: String): JsonObject {
        val text = javaClass.getResourceAsStream("/frozen/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.contentOrNull!!
}
