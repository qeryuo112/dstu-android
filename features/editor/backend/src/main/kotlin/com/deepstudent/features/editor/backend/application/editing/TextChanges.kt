package com.deepstudent.features.editor.backend.application.editing

import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.api.editing.TextEdit

/** Finds the smallest common replacement whose boundaries are valid in both versions. */
internal fun textChange(before: String, after: String): TextEdit {
    val oldBoundaries = GraphemeBoundaries(before)
    val newBoundaries = GraphemeBoundaries(after)
    val raw = changedSpan(before, after)
    var start = raw.start
    while (!oldBoundaries.contains(start) || !newBoundaries.contains(start)) start--
    var oldEnd = raw.oldEnd
    var newEnd = raw.newEnd
    while (!oldBoundaries.contains(oldEnd) || !newBoundaries.contains(newEnd)) {
        oldEnd++
        newEnd++
    }
    return TextEdit(SourceRange(start, oldEnd), before.substring(start, oldEnd), after.substring(start, newEnd))
}

internal data class ChangedSpan(val start: Int, val oldEnd: Int, val newEnd: Int)

internal fun changedSpan(before: String, after: String): ChangedSpan {
    var start = 0
    while (start < minOf(before.length, after.length) && before[start] == after[start]) start++
    var oldEnd = before.length
    var newEnd = after.length
    while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return ChangedSpan(start, oldEnd, newEnd)
}

internal fun TextEdit.inverse(): TextEdit = TextEdit(
    SourceRange(range.start, range.start + replacement.length), replacement, expected,
)

internal fun TextEdit.applyTo(source: String): String =
    source.replaceRange(range.start, range.endExclusive, replacement)

internal fun validUtf16(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char.isHighSurrogate()) {
            if (index == text.length || !text[index].isLowSurrogate()) return false
            index++
        } else if (char.isLowSurrogate()) {
            return false
        }
    }
    return true
}
