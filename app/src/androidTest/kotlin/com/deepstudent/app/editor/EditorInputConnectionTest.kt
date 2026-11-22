package com.deepstudent.app.editor

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import com.deepstudent.features.editor.api.editing.Selection
import com.deepstudent.features.editor.backend.application.editing.DefaultEditorSessionFactory
import com.deepstudent.features.editor.ui.EditorContent
import com.deepstudent.features.editor.ui.rememberEditorController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EditorInputConnectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = EditorTestHost()

    @Before fun start() = host.start()
    @After fun stop() = host.close()

    @Test fun composingCommitSelectionAndHistoryGoThroughAndroidInputConnection() {
        val session = DefaultEditorSessionFactory().create("前后")
        host.setContent {
            MaterialTheme { EditorContent(rememberEditorController(session) {}, Modifier.fillMaxSize()) }
        }
        compose.onNodeWithTag("editor-input").performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle {
            val focused = findInputView(host.activity.window.decorView)
            assertNotNull(focused)
            connection = checkNotNull(focused!!.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setSelection(1, 1))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(connection.setComposingText("n", 1)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(connection.setComposingText("ni", 1)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(connection.commitText("你", 1)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("前你后", session.state.source)
            assertEquals(Selection(2, 2), session.state.selection)
            assertEquals(null, session.state.composition)
        }
        compose.onNodeWithTag("editor-undo").performClick()
        compose.runOnIdle { assertEquals("前后", session.state.source) }
        compose.onNodeWithTag("editor-redo").performClick()
        compose.runOnIdle { assertEquals("前你后", session.state.source) }
    }

    @Test fun crossBlockSelectionAndEmojiPasteUseUnnormalizedUtf16Offsets() {
        val original = "# 甲\r\n\r\n第一块\r\n\r\n第二块👩‍👩‍👧‍👦\r\n末尾"
        val session = DefaultEditorSessionFactory().create(original)
        host.setContent {
            MaterialTheme { EditorContent(rememberEditorController(session) {}, Modifier.fillMaxSize()) }
        }
        compose.onNodeWithTag("editor-input").performClick()
        lateinit var connection: InputConnection
        val start = original.indexOf("第一块")
        val end = original.indexOf("\r\n末尾")
        compose.runOnIdle {
            connection = checkNotNull(findInputView(host.activity.window.decorView)?.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setSelection(start, end))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(original.substring(start, end), session.selectedText())
            assertTrue(connection.commitText("新段\r\n\r\n👩‍💻e\u0301", 1))
        }
        compose.waitForIdle()
        val expected = original.substring(0, start) + "新段\r\n\r\n👩‍💻e\u0301" + original.substring(end)
        compose.runOnIdle { assertEquals(expected, session.state.source) }
        compose.onNodeWithTag("editor-undo").performClick()
        compose.runOnIdle { assertEquals(original, session.state.source) }
        compose.onNodeWithTag("editor-redo").performClick()
        compose.runOnIdle { assertEquals(expected, session.state.source) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun undoWhileComposingCreatesOneReversibleTransaction() {
        val session = DefaultEditorSessionFactory().create("")
        host.setContent {
            MaterialTheme { EditorContent(rememberEditorController(session) {}, Modifier.fillMaxSize()) }
        }
        compose.onNodeWithTag("editor-input").performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle {
            connection = checkNotNull(findInputView(host.activity.window.decorView)?.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText("ni", 1))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("ni", session.state.source)
            assertNotNull(session.state.composition)
        }
        compose.onNodeWithTag("editor-input").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.runOnIdle {
            assertEquals("", session.state.source)
            assertEquals(null, session.state.composition)
        }
        compose.onNodeWithTag("editor-input").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
        compose.runOnIdle { assertEquals("ni", session.state.source) }
    }

    private fun findInputView(view: View): View? {
        if (view.onCheckIsTextEditor() && view.hasFocus()) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findInputView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
