package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskSubmission
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    @Test fun realTaskCommitsNoteAndFactBeforeCompletedProjection(): Unit = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DstuApplication
        val runtime = application.runtime()
        val key = "device-note-${System.nanoTime()}"
        val submission = TaskSubmission(MainRuntimeResources.NOTE_CREATE,
            JsonValue.obj("title" to JsonValue.text("任务笔记"), "content" to JsonValue.text("持久内容 🧪")), dedupeKey = key)
        val first = runtime.controller.submit(submission)
        val duplicate = runtime.controller.submit(submission)
        assertEquals(first.taskId, duplicate.taskId)
        withTimeout(10000) {
            while (runtime.controller.snapshot(first.taskId)?.state != RuntimeState.COMPLETED) delay(20)
        }
        val snapshot = checkNotNull(runtime.controller.snapshot(first.taskId))
        assertNotNull(snapshot.execution.businessFactId)
        val storage = application.storage()
        assertEquals("持久内容 🧪", storage.notes.get(checkNotNull(snapshot.execution.receipt))?.content)
        assertNotNull(runtime.completionSource.find(first.taskId, snapshot.ownership.generation))
        assertEquals(1, runtime.controller.events(first.taskId).count { it.state == RuntimeState.COMPLETED })
        storage.notes.delete(checkNotNull(snapshot.execution.receipt))
    }
}
