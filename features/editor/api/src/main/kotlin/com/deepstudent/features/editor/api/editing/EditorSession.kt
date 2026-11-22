package com.deepstudent.features.editor.api.editing

import com.deepstudent.features.editor.api.SourceRange

/** Direction is retained; all positions are offsets in the unnormalized UTF-16 source. */
data class Selection(val anchor: Int, val focus: Int) {
    init { require(anchor >= 0 && focus >= 0) }

    val range: SourceRange get() = SourceRange(minOf(anchor, focus), maxOf(anchor, focus))
    val collapsed: Boolean get() = anchor == focus
}

data class EditorState(
    val source: String,
    val selection: Selection,
    val composition: SourceRange? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** Replace exactly this source range, provided its original spelling still matches. */
data class TextEdit(val range: SourceRange, val expected: String, val replacement: String)

/**
 * A complete platform snapshot. Selection and composition refer to [source], not the old state.
 * In-bounds selection/composition endpoints may be normalized to grapheme boundaries.
 */
data class EditorInput(val source: String, val selection: Selection, val composition: SourceRange?)

sealed interface EditCommand {
    data class Replace(val edit: TextEdit, val selectionAfter: Selection? = null) : EditCommand
    data class Select(val selection: Selection) : EditCommand
    data class Input(val expectedSource: String, val value: EditorInput) : EditCommand
    data object FinishComposition : EditCommand
    data object Undo : EditCommand
    data object Redo : EditCommand
}

enum class EditRejection { INVALID_RANGE, INVALID_TEXT, STALE_SOURCE, INVALID_SELECTION, INVALID_COMPOSITION }

sealed interface EditResult {
    val state: EditorState
    data class Applied(override val state: EditorState) : EditResult
    data class Rejected(val reason: EditRejection, override val state: EditorState) : EditResult
}

/**
 * Single-owner, synchronous session. Save [EditorState.source]; it includes provisional composing text.
 * The host must finish composition before replacing/disposing the session. Finishing closes the history group;
 * it does not ask the Android IME to commit a candidate.
 * A changed composition enters history as one transaction; redo is discarded on its first text change.
 */
interface EditorSession {
    val state: EditorState
    fun dispatch(command: EditCommand): EditResult
    fun selectedText(): String
}

fun interface EditorSessionFactory {
    fun create(source: String): EditorSession
}
