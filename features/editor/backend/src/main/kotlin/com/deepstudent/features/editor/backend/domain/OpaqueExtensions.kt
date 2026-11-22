package com.deepstudent.features.editor.backend.domain

import com.deepstudent.features.editor.api.BlockKind
import com.deepstudent.features.editor.api.BlockNode
import com.deepstudent.features.editor.api.SourceRange

/** Protect desktop/future directives as a unit; this does not interpret their grammar. */
internal object OpaqueExtensions {
    fun protect(source: String, blocks: List<BlockNode>): List<BlockNode> {
        val result = mutableListOf<BlockNode>()
        var index = 0
        while (index < blocks.size) {
            val name = directiveName(source, blocks[index])
            if (name == null) {
                result.add(protectInline(source, blocks[index]))
                index++
                continue
            }
            val end = closingBlock(source, blocks, index, name)
            val range = SourceRange(blocks[index].range.start, blocks[end].range.endExclusive)
            result.add(BlockNode(BlockKind.OPAQUE, "opaque.directive", range, isOpaque = true))
            index = end + 1
        }
        return result
    }

    private fun directiveName(source: String, block: BlockNode): String? {
        if (block.kind != BlockKind.PARAGRAPH) return null
        return block.range.textOf(source).lineSequence().mapNotNull(::openingName).firstOrNull()
    }

    private fun openingName(line: String): String? {
        val marker = line.trim()
        if (!marker.startsWith(":::") || marker.startsWith(":::end-")) return null
        val name = marker.removePrefix(":::").takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return name.takeIf { it.isNotEmpty() }
    }

    private fun closingBlock(source: String, blocks: List<BlockNode>, start: Int, name: String): Int {
        val close = ":::end-$name"
        var depth = 0
        for (index in start until blocks.size) {
            if (blocks[index].kind != BlockKind.PARAGRAPH) continue
            val lines = blocks[index].range.textOf(source).lineSequence()
            for (line in lines) {
                val marker = line.trim()
                if (isOpening(marker, name)) depth++
                if (marker == close && depth > 0 && --depth == 0) return index
            }
        }
        return blocks.lastIndex
    }

    private fun isOpening(marker: String, name: String): Boolean {
        val prefix = ":::$name"
        if (!marker.startsWith(prefix)) return false
        return marker.length == prefix.length || marker[prefix.length] == '{' || marker[prefix.length].isWhitespace()
    }

    private fun protectInline(source: String, block: BlockNode): BlockNode {
        val raw = block.range.textOf(source)
        val extension = block.kind == BlockKind.HTML || block.kind == BlockKind.REFERENCE_DEFINITION ||
            (block.kind != BlockKind.CODE && (raw.contains('[') || raw.contains('$') ||
                raw.contains(":::") || raw.contains('<')))
        return if (extension) {
            BlockNode(block.kind, block.syntax, block.range, block.blocks, block.inlines, isOpaque = true)
        } else block
    }
}
