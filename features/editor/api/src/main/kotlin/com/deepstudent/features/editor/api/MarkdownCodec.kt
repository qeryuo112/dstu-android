package com.deepstudent.features.editor.api

/** A whole root block replacement, with an optimistic check against its original spelling. */
data class BlockPatch(val range: SourceRange, val expected: String, val replacement: String)

enum class PatchRejection {
    INVALID_RANGE, STALE_SOURCE, NOT_ROOT_BLOCK, OPAQUE_BLOCK, INVALID_REPLACEMENT,
    REPLACEMENT_NOT_SINGLE_BLOCK, OUTSIDE_STRUCTURE_CHANGED,
}

sealed interface PatchResult {
    data class Applied(
        val document: NoteDocument,
        val previousRange: SourceRange,
        val replacementRange: SourceRange,
    ) : PatchResult

    data class Rejected(val reason: PatchRejection) : PatchResult
}

/** Parsing and serialization do not normalize source, including line endings or Unicode. */
interface MarkdownCodec {
    fun parse(source: String): NoteDocument
    fun parseUtf8(source: ByteArray): NoteDocument
    fun serialize(document: NoteDocument): String
    fun serializeUtf8(document: NoteDocument): ByteArray
    fun replaceBlock(document: NoteDocument, patch: BlockPatch): PatchResult
}
