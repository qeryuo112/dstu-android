package com.deepstudent.app.workspace

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.features.chat.api.ChatStreamPhase
import com.deepstudent.features.chat.api.ChatStreamRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ChatCancellationDeviceTest {
    @Test fun explicitCancelPreservesPartialTextAndDoesNotAffectOtherTask(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "s117-chat-cancel-").toFile()
        try {
            ChatSsePeer().use { first ->
                ChatSsePeer().use { second ->
                    WorkspaceChatRuntime(root, context).use { owner ->
                        val streams = owner.streams
                        val task = streams.start(ChatStreamRequest(first.url, "first"))
                        val other = streams.start(ChatStreamRequest(second.url, "second"))
                        withTimeout(10_000) { streams.observe(task).first { it.text == "中文" } }
                        withTimeout(10_000) { streams.observe(other).first { it.text == "中文" } }
                        streams.cancel(task)
                        val cancelled = withTimeout(10_000) { streams.observe(task).first { it.phase == ChatStreamPhase.Cancelled } }
                        assertEquals("中文", cancelled.text)
                        second.finish()
                        val completed = withTimeout(10_000) { streams.observe(other).first { it.phase == ChatStreamPhase.Completed } }
                        assertEquals("中文👩‍💻", completed.text)
                        assertNull(owner.chatRepository.load("sess_${task.value.removePrefix("task_")}"))
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
