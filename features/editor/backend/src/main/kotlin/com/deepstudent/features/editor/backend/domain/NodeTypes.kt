package com.deepstudent.features.editor.backend.domain

import com.deepstudent.features.editor.api.BlockKind
import com.deepstudent.features.editor.api.InlineKind
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal object NodeTypes {
    private val blockKinds = mapOf(
        MarkdownElementTypes.PARAGRAPH to BlockKind.PARAGRAPH,
        MarkdownElementTypes.ATX_1 to BlockKind.HEADING,
        MarkdownElementTypes.ATX_2 to BlockKind.HEADING,
        MarkdownElementTypes.ATX_3 to BlockKind.HEADING,
        MarkdownElementTypes.ATX_4 to BlockKind.HEADING,
        MarkdownElementTypes.ATX_5 to BlockKind.HEADING,
        MarkdownElementTypes.ATX_6 to BlockKind.HEADING,
        MarkdownElementTypes.SETEXT_1 to BlockKind.HEADING,
        MarkdownElementTypes.SETEXT_2 to BlockKind.HEADING,
        MarkdownElementTypes.UNORDERED_LIST to BlockKind.LIST,
        MarkdownElementTypes.ORDERED_LIST to BlockKind.LIST,
        MarkdownElementTypes.LIST_ITEM to BlockKind.LIST_ITEM,
        MarkdownElementTypes.BLOCK_QUOTE to BlockKind.QUOTE,
        MarkdownElementTypes.CODE_FENCE to BlockKind.CODE,
        MarkdownElementTypes.CODE_BLOCK to BlockKind.CODE,
        GFMElementTypes.TABLE to BlockKind.TABLE,
        GFMElementTypes.HEADER to BlockKind.TABLE_ROW,
        GFMElementTypes.ROW to BlockKind.TABLE_ROW,
        GFMTokenTypes.CELL to BlockKind.TABLE_CELL,
        MarkdownTokenTypes.HORIZONTAL_RULE to BlockKind.THEMATIC_BREAK,
        MarkdownElementTypes.HTML_BLOCK to BlockKind.HTML,
        MarkdownElementTypes.LINK_DEFINITION to BlockKind.REFERENCE_DEFINITION,
        GFMElementTypes.BLOCK_MATH to BlockKind.MATH,
    )

    private val inlineKinds = mapOf(
        MarkdownTokenTypes.TEXT to InlineKind.TEXT,
        MarkdownTokenTypes.WHITE_SPACE to InlineKind.TEXT,
        MarkdownTokenTypes.EOL to InlineKind.TEXT,
        MarkdownElementTypes.STRONG to InlineKind.STRONG,
        MarkdownElementTypes.EMPH to InlineKind.EMPHASIS,
        GFMElementTypes.STRIKETHROUGH to InlineKind.STRIKETHROUGH,
        MarkdownElementTypes.CODE_SPAN to InlineKind.CODE,
        MarkdownElementTypes.INLINE_LINK to InlineKind.LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK to InlineKind.LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK to InlineKind.LINK,
        MarkdownElementTypes.AUTOLINK to InlineKind.LINK,
        GFMTokenTypes.GFM_AUTOLINK to InlineKind.LINK,
        MarkdownElementTypes.IMAGE to InlineKind.IMAGE,
        GFMElementTypes.INLINE_MATH to InlineKind.MATH,
        MarkdownTokenTypes.HTML_TAG to InlineKind.OPAQUE,
        MarkdownTokenTypes.BAD_CHARACTER to InlineKind.OPAQUE,
    )

    fun block(type: IElementType): BlockKind? = blockKinds[type]

    fun inline(type: IElementType): InlineKind = inlineKinds[type] ?: InlineKind.TOKEN
}
