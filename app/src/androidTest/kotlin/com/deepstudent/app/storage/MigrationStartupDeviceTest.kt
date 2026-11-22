package com.deepstudent.app.storage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.editor.EditorTestHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Leaves the real MainActivity content and the installed data set untouched. */
@RunWith(AndroidJUnit4::class)
class MigrationStartupDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun ordinaryMainActivityEntersWorkspaceWithGovernedDatabase() {
        val host = EditorTestHost()
        try {
            host.start()
            compose.waitUntil(60_000) {
                compose.onAllNodesWithTag("native-workspace").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("native-workspace").assertIsDisplayed()
            compose.onNodeWithTag("migration-maintenance").assertDoesNotExist()
        } finally {
            host.close()
        }
    }
}
