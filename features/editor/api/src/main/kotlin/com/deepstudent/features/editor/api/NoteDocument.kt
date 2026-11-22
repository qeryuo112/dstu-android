package com.deepstudent.features.editor.api

import java.util.Collections

enum class BlockKind {
    PARAGRAPH, HEADING, LIST, LIST_ITEM, QUOTE, CODE, TABLE, TABLE_ROW, TABLE_CELL,
    THEMATIC_BREAK, HTML, REFERENCE_DEFINITION, MATH, OPAQUE,
}

enum class InlineKind {
    TEXT, STRONG, EMPHASIS, STRIKETHROUGH, CODE, LINK, IMAGE, MATH, TOKEN, OPAQUE,
}

/** Ranges always refer to the enclosing document's unnormalized source. */
class InlineNode(
    val kind: InlineKind,
    val syntax: String,
    val range: SourceRange,
    children: List<InlineNode> = emptyList(),
) {
    val children: List<InlineNode> = Collections.unmodifiableList(ArrayList(children))
}

/** The root blocks are the only replaceable units; nested blocks describe their contents. */
class BlockNode(
    val kind: BlockKind,
    val syntax: String,
    val range: SourceRange,
    blocks: List<BlockNode> = emptyList(),
    inlines: List<InlineNode> = emptyList(),
    val isOpaque: Boolean = false,
) {
    val blocks: List<BlockNode> = Collections.unmodifiableList(ArrayList(blocks))
    val inlines: List<InlineNode> = Collections.unmodifiableList(ArrayList(inlines))
}

/** Immutable source snapshot. Blocks and ranges are valid only for this snapshot. */
class NoteDocument(val source: String, blocks: List<BlockNode>) {
    val blocks: List<BlockNode> = Collections.unmodifiableList(ArrayList(blocks))
}
