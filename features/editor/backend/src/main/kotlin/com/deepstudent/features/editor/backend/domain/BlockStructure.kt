package com.deepstudent.features.editor.backend.domain

import com.deepstudent.features.editor.api.BlockNode
import com.deepstudent.features.editor.api.InlineNode
import com.deepstudent.features.editor.api.SourceRange

/** Compare parse shape as well as spelling, so an edit cannot silently absorb adjacent blocks. */
internal object BlockStructure {
    fun equal(before: BlockNode, after: BlockNode): Boolean =
        equalBlock(before, after, before.range.start, after.range.start)

    private fun equalBlock(before: BlockNode, after: BlockNode, leftBase: Int, rightBase: Int): Boolean {
        if (!sameBlockHeader(before, after, leftBase, rightBase)) return false
        if (before.blocks.size != after.blocks.size || before.inlines.size != after.inlines.size) return false
        if (!before.blocks.zip(after.blocks).all { (a, b) -> equalBlock(a, b, leftBase, rightBase) }) return false
        return before.inlines.zip(after.inlines).all { (a, b) -> equalInline(a, b, leftBase, rightBase) }
    }

    private fun sameBlockHeader(before: BlockNode, after: BlockNode, leftBase: Int, rightBase: Int): Boolean =
        before.kind == after.kind && before.syntax == after.syntax && before.isOpaque == after.isOpaque &&
            sameRange(before.range, after.range, leftBase, rightBase)

    private fun equalInline(left: InlineNode, right: InlineNode, leftBase: Int, rightBase: Int): Boolean =
        left.kind == right.kind && left.syntax == right.syntax &&
            sameRange(left.range, right.range, leftBase, rightBase) && left.children.size == right.children.size &&
            left.children.zip(right.children).all { (a, b) -> equalInline(a, b, leftBase, rightBase) }

    private fun sameRange(a: SourceRange, b: SourceRange, baseA: Int, baseB: Int): Boolean =
        a.start - baseA == b.start - baseB && a.endExclusive - baseA == b.endExclusive - baseB
}
