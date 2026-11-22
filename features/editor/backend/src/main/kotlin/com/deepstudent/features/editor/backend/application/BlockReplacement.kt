package com.deepstudent.features.editor.backend.application

import com.deepstudent.features.editor.api.BlockKind
import com.deepstudent.features.editor.api.BlockPatch
import com.deepstudent.features.editor.api.MarkdownCodec
import com.deepstudent.features.editor.api.NoteDocument
import com.deepstudent.features.editor.api.PatchRejection
import com.deepstudent.features.editor.api.PatchResult
import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.backend.domain.BlockStructure

internal class BlockReplacement(private val codec: MarkdownCodec) {
    fun apply(document: NoteDocument, patch: BlockPatch): PatchResult {
        val source = document.source
        val oldText = runCatching { patch.range.textOf(source) }.getOrNull()
            ?: return rejected(PatchRejection.INVALID_RANGE)
        if (oldText != patch.expected) return rejected(PatchRejection.STALE_SOURCE)
        val original = codec.parse(source)
        val index = original.blocks.indexOfFirst { it.range == patch.range }
        if (index < 0) return rejected(PatchRejection.NOT_ROOT_BLOCK)
        if (original.blocks[index].isOpaque || original.blocks[index].kind == BlockKind.OPAQUE) {
            return rejected(PatchRejection.OPAQUE_BLOCK)
        }
        val standalone = runCatching { codec.parse(patch.replacement) }.getOrNull()
            ?: return rejected(PatchRejection.INVALID_REPLACEMENT)
        if (!isSingleReplacement(standalone)) return rejected(PatchRejection.REPLACEMENT_NOT_SINGLE_BLOCK)
        val newSource = source.replaceRange(patch.range.start, patch.range.endExclusive, patch.replacement)
        val updated = codec.parse(newSource)
        val newRange = SourceRange(patch.range.start, patch.range.start + patch.replacement.length)
        if (!preservesOtherBlocks(original, updated, standalone, index, newRange)) {
            return rejected(PatchRejection.OUTSIDE_STRUCTURE_CHANGED)
        }
        return PatchResult.Applied(updated, patch.range, newRange)
    }

    private fun isSingleReplacement(document: NoteDocument): Boolean {
        val block = document.blocks.singleOrNull() ?: return false
        return !block.isOpaque && block.kind != BlockKind.OPAQUE && block.range == SourceRange(0, document.source.length)
    }

    private fun preservesOtherBlocks(
        original: NoteDocument,
        updated: NoteDocument,
        standalone: NoteDocument,
        index: Int,
        replacementRange: SourceRange,
    ): Boolean {
        if (original.blocks.size != updated.blocks.size) return false
        val replacement = updated.blocks[index]
        if (replacement.range != replacementRange || !BlockStructure.equal(standalone.blocks[0], replacement)) {
            return false
        }
        return original.blocks.indices.filter { it != index }.all { i ->
            val before = original.blocks[i]
            val after = updated.blocks[i]
            before.range.textOf(original.source) == after.range.textOf(updated.source) &&
                BlockStructure.equal(before, after)
        }
    }

    private fun rejected(reason: PatchRejection): PatchResult = PatchResult.Rejected(reason)
}
