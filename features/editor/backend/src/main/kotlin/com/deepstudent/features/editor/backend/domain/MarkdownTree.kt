package com.deepstudent.features.editor.backend.domain

import com.deepstudent.features.editor.api.BlockKind
import com.deepstudent.features.editor.api.BlockNode
import com.deepstudent.features.editor.api.InlineNode
import com.deepstudent.features.editor.api.SourceRange
import org.intellij.markdown.ast.ASTNode

internal class MarkdownTree(private val source: ParserSource) {
    fun roots(tree: ASTNode): List<BlockNode> = tree.children
        .filterNot { it.type.name == "EOL" || it.type.name == "WHITE_SPACE" }
        .map(::block)

    private fun range(node: ASTNode) = SourceRange(
        source.originalOffset(node.startOffset), source.originalOffset(node.endOffset),
    )

    private fun block(node: ASTNode): BlockNode {
        val blocks = mutableListOf<BlockNode>()
        val inlines = mutableListOf<InlineNode>()
        for (child in node.children) {
            if (NodeTypes.block(child.type) != null) blocks.add(block(child))
            else inlines.add(inline(child))
        }
        return BlockNode(NodeTypes.block(node.type) ?: BlockKind.OPAQUE, node.type.name, range(node), blocks, inlines)
    }

    private fun inline(node: ASTNode): InlineNode = InlineNode(
        NodeTypes.inline(node.type), node.type.name, range(node), node.children.map(::inline),
    )
}
