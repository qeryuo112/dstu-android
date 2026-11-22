package com.deepstudent.features.editor.backend.application.editing

import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.api.editing.Selection
import com.deepstudent.features.editor.api.isTextBoundary
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale

/** ICU4J is fixed so JVM and Android use the same Unicode grapheme rules. */
internal class GraphemeBoundaries(private val text: String) {
    private val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT).also { it.setText(text) }

    fun contains(offset: Int): Boolean = isTextBoundary(text, offset) && iterator.isBoundary(offset)

    fun contains(range: SourceRange): Boolean = contains(range.start) && contains(range.endExclusive)

    fun contains(selection: Selection): Boolean = contains(selection.anchor) && contains(selection.focus)

    fun normalize(selection: Selection): Selection {
        if (selection.collapsed) {
            val caret = nearest(selection.anchor)
            return Selection(caret, caret)
        }
        val low = floor(selection.range.start)
        val high = ceil(selection.range.endExclusive)
        return if (selection.anchor < selection.focus) Selection(low, high) else Selection(high, low)
    }

    fun normalize(range: SourceRange): SourceRange = SourceRange(floor(range.start), ceil(range.endExclusive))

    fun floor(offset: Int): Int = if (contains(offset)) offset else iterator.preceding(offset)

    fun ceil(offset: Int): Int = if (contains(offset)) offset else iterator.following(offset)

    private fun nearest(offset: Int): Int {
        val before = floor(offset)
        val after = ceil(offset)
        return if (offset - before <= after - offset) before else after
    }
}
