package com.deepstudent.features.editor.backend.application

import com.deepstudent.features.editor.api.BlockPatch
import com.deepstudent.features.editor.api.MarkdownCodec
import com.deepstudent.features.editor.api.NoteDocument
import com.deepstudent.features.editor.api.PatchResult
import com.deepstudent.features.editor.backend.domain.MarkdownTree
import com.deepstudent.features.editor.backend.domain.OpaqueExtensions
import com.deepstudent.features.editor.backend.domain.ParserSource
import com.deepstudent.features.editor.backend.domain.SourceText
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** GFM source positions are translated back from normalized newlines before they are exposed. */
class DefaultMarkdownCodec : MarkdownCodec {
    override fun parse(source: String): NoteDocument {
        SourceText.requireValid(source)
        val normalized = ParserSource(source)
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(normalized.text)
        val blocks = OpaqueExtensions.protect(source, MarkdownTree(normalized).roots(tree))
        return NoteDocument(source, blocks)
    }

    override fun parseUtf8(source: ByteArray): NoteDocument = parse(SourceText.decode(source))

    override fun serialize(document: NoteDocument): String = document.source.also(SourceText::requireValid)

    override fun serializeUtf8(document: NoteDocument): ByteArray = SourceText.encode(serialize(document))

    override fun replaceBlock(document: NoteDocument, patch: BlockPatch): PatchResult =
        BlockReplacement(this).apply(document, patch)
}
