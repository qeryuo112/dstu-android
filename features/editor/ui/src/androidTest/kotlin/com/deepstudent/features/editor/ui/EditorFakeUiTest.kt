package com.deepstudent.features.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.deepstudent.features.editor.api.editing.EditCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EditorFakeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = EditorTestHost()

    @Before fun start() = host.start()
    @After fun stop() = host.close()

    @Test fun contentUsesOnlyInjectedSessionAndReportsChanges() {
        val fake = FakeEditorSession()
        val changes = mutableListOf<String>()
        host.setContent {
            MaterialTheme {
                val controller = rememberEditorController(fake, changes::add)
                EditorContent(controller, Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("editor-input").assertIsDisplayed().performClick().performTextInput("中文🙂")
        compose.runOnIdle {
            assertEquals("中文🙂", fake.state.source)
            assertEquals("中文🙂", changes.last())
            assertTrue(fake.commands.any { it is EditCommand.Input })
        }
        compose.onNodeWithTag("editor-undo").performClick()
        compose.runOnIdle {
            assertEquals("", fake.state.source)
            assertEquals("", changes.last())
            assertTrue(fake.commands.any { it == EditCommand.Undo })
        }
    }
}
