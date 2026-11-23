package com.deepstudent.app.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.MainActivity
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NoteImportPanelDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = DstuApplication.from(instrumentation.targetContext)

    @Test(timeout = 60_000)
    fun workspaceImportUsesRuntimeAndShowsARealTaskNotification() = runBlocking {
        check(app.packageName == "com.deepstudent.app.s2device")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val activity = androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        val notifications = app.getSystemService(NotificationManager::class.java)
        try {
            StreamFixturePeer().use { peer ->
                awaitTag("workspace-open-note-import")
                compose.onNodeWithTag("workspace-open-note-import").performClick()
                awaitTag("note-import-title")
                compose.onNodeWithTag("note-import-title").performTextInput("S223 real import")
                compose.onNodeWithTag("note-import-url").performTextInput(peer.url)
                compose.onNodeWithTag("note-import-submit").performClick()
                awaitTag("note-import-task")
                compose.waitUntil(15_000) { notifications.activeNotifications.any { it.id == 7411 } }
                val id = app.foregroundDuties.duties.value.single().taskId
                val controller = app.runtime().controller
                assertEquals(RuntimeState.RUNNING, controller.snapshot(id)?.state)
                assertTrue(notifications.activeNotifications.any { it.id == 7411 && it.tag == id.value })
                peer.complete()
                awaitRuntime { controller.snapshot(id)?.state == RuntimeState.COMPLETED }
                assertTrue(app.storage().notes.list().any { it.title == "S223 real import" && it.content.contains("segment") })
                awaitRuntime { notifications.activeNotifications.none { it.id == 7410 || it.id == 7411 } }
            }
        } finally {
            activity.close()
        }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
}

internal suspend fun awaitRuntime(condition: suspend () -> Boolean) = kotlinx.coroutines.withTimeout(15_000) {
    while (!condition()) kotlinx.coroutines.delay(30)
}
