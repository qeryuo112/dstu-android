package com.deepstudent.features.editor.backend.application.editing

import com.deepstudent.features.editor.api.SourceRange
import com.deepstudent.features.editor.api.editing.EditCommand
import com.deepstudent.features.editor.api.editing.EditRejection
import com.deepstudent.features.editor.api.editing.EditResult
import com.deepstudent.features.editor.api.editing.EditorInput
import com.deepstudent.features.editor.api.editing.Selection
import com.deepstudent.features.editor.api.editing.TextEdit
import com.deepstudent.features.editor.backend.application.DefaultMarkdownCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EditorSessionTest {
    @Test fun compositionIsOneUndoAndRedoTransaction() {
        val session = DefaultEditorSession("前后")
        session.dispatch(EditCommand.Select(Selection(1, 1)))
        input(session, "前n后", Selection(2, 2), SourceRange(1, 2))
        input(session, "前ni后", Selection(3, 3), SourceRange(1, 3))
        input(session, "前你后", Selection(2, 2), SourceRange(1, 2))
        input(session, "前你好后", Selection(3, 3), null)
        assertEquals("前你好后", session.state.source)
        session.dispatch(EditCommand.Undo)
        assertEquals("前后", session.state.source)
        assertEquals(Selection(1, 1), session.state.selection)
        assertFalse(session.state.canUndo)
        session.dispatch(EditCommand.Redo)
        assertEquals("前你好后", session.state.source)
        assertEquals(Selection(3, 3), session.state.selection)
    }

    @Test fun crossBlockCopyPastePreservesOpaqueContentAndLineEndings() {
        val original = "# 甲\r\n\r\n::: unknown\r\n👩‍👩‍👧‍👦 e\u0301\r\n:::\r\n\r\n末尾"
        val session = DefaultEditorSession(original)
        val start = original.indexOf("甲")
        val end = original.indexOf("末尾")
        session.dispatch(EditCommand.Select(Selection(end, start)))
        val copied = original.substring(start, end)
        assertEquals(copied, session.selectedText())
        val replacement = "多块\r\n\r\n- 新\n- 旧"
        val edit = TextEdit(SourceRange(start, end), copied, replacement)
        assertTrue(session.dispatch(EditCommand.Replace(edit)) is EditResult.Applied)
        assertEquals(original.substring(0, start) + replacement + original.substring(end), session.state.source)
        val codec = DefaultMarkdownCodec()
        assertEquals(session.state.source, codec.serialize(codec.parse(session.state.source)))
        session.dispatch(EditCommand.Undo)
        assertEquals(original, session.state.source)
        assertEquals(Selection(end, start), session.state.selection)
        session.dispatch(EditCommand.Redo)
        assertEquals(original.substring(0, start) + replacement + original.substring(end), session.state.source)
    }

    @Test fun invalidPartialClusterAndStaleEditsNeverChangeState() {
        val original = "A👩‍👩‍👧‍👦e\u0301\r\nB"
        val session = DefaultEditorSession(original)
        for (offset in listOf(2, 3, original.indexOf('\u0301'), original.indexOf('\n'))) {
            val result = session.dispatch(EditCommand.Replace(TextEdit(SourceRange(offset, offset), "", "x")))
            assertEquals(EditRejection.INVALID_RANGE, (result as EditResult.Rejected).reason)
            assertEquals(original, session.state.source)
        }
        val stale = session.dispatch(EditCommand.Replace(TextEdit(SourceRange(0, 1), "B", "x")))
        assertEquals(EditRejection.STALE_SOURCE, (stale as EditResult.Rejected).reason)
        val malformed = session.dispatch(EditCommand.Replace(TextEdit(SourceRange(0, 0), "", "\uD800")))
        assertEquals(EditRejection.INVALID_TEXT, (malformed as EditResult.Rejected).reason)
        session.dispatch(EditCommand.Select(Selection(3, 3)))
        assertEquals(Selection(1, 1), session.state.selection)
    }

    @Test fun partialImeDeletionIsRejectedWithoutLosingTheCluster() {
        val session = DefaultEditorSession("e\u0301👩‍💻")
        session.dispatch(EditCommand.Select(Selection(2, 2)))
        val result = session.dispatch(EditCommand.Input(session.state.source, EditorInput("e👩‍💻", Selection(1, 1), null)))
        assertEquals(EditRejection.INVALID_RANGE, (result as EditResult.Rejected).reason)
        assertEquals("e\u0301👩‍💻", session.state.source)
    }

    @Test fun fixedRandomSequenceUndoToInitialAndRedoToFinal() {
        val original = "# 笔记\r\n\r\n👩‍👩‍👧‍👦 e\u0301\n原文"
        val session = DefaultEditorSession(original, historyLimit = 2000)
        val random = Random(1142026)
        val pieces = listOf("中", "", "\r\n", "👩‍💻", "a\u0301", "\n\n- 项", "🇨🇳", "x")
        val versions = mutableListOf(original)
        repeat(500) {
            val source = session.state.source
            val boundaries = GraphemeBoundaries(source)
            val offsets = (0..source.length).filter(boundaries::contains)
            val first = offsets[random.nextInt(offsets.size)]
            val second = offsets[random.nextInt(offsets.size)]
            val start = minOf(first, second)
            val end = maxOf(first, second)
            val edit = TextEdit(SourceRange(start, end), source.substring(start, end), pieces[random.nextInt(pieces.size)])
            assertTrue(session.dispatch(EditCommand.Replace(edit)) is EditResult.Applied)
            if (session.state.source != source) versions += session.state.source
        }
        val final = session.state.source
        for (expected in versions.dropLast(1).asReversed()) {
            assertTrue(session.state.canUndo)
            session.dispatch(EditCommand.Undo)
            assertEquals(expected, session.state.source)
        }
        assertFalse(session.state.canUndo)
        assertEquals(original, session.state.source)
        for (expected in versions.drop(1)) {
            assertTrue(session.state.canRedo)
            session.dispatch(EditCommand.Redo)
            assertEquals(expected, session.state.source)
        }
        assertFalse(session.state.canRedo)
        assertEquals(final, session.state.source)
    }

    @Test fun finishOrUndoDuringCompositionAndBranchingHistory() {
        val session = DefaultEditorSession("")
        input(session, "n", Selection(1, 1), SourceRange(0, 1))
        session.dispatch(EditCommand.Undo)
        assertEquals("", session.state.source)
        assertTrue(session.state.canRedo)
        session.dispatch(EditCommand.Redo)
        assertEquals("n", session.state.source)
        session.dispatch(EditCommand.Undo)
        session.dispatch(EditCommand.Replace(TextEdit(SourceRange(0, 0), "", "新")))
        assertFalse(session.state.canRedo)
        input(session, "新好", Selection(2, 2), SourceRange(1, 2))
        session.dispatch(EditCommand.FinishComposition)
        session.dispatch(EditCommand.FinishComposition)
        session.dispatch(EditCommand.Undo)
        assertEquals("新", session.state.source)
    }

    @Test fun changedThenRevertedCompositionInvalidatesRedo() {
        val session = DefaultEditorSession("")
        session.dispatch(EditCommand.Replace(TextEdit(SourceRange(0, 0), "", "x")))
        session.dispatch(EditCommand.Undo)
        input(session, "n", Selection(1, 1), SourceRange(0, 1))
        input(session, "", Selection(0, 0), SourceRange(0, 0))
        assertFalse(session.state.canRedo)
        session.dispatch(EditCommand.FinishComposition)
        session.dispatch(EditCommand.Redo)
        assertEquals("", session.state.source)
    }

    private fun input(session: DefaultEditorSession, source: String, selection: Selection, range: SourceRange?) {
        val result = session.dispatch(EditCommand.Input(session.state.source, EditorInput(source, selection, range)))
        assertTrue(result.toString(), result is EditResult.Applied)
    }
}
