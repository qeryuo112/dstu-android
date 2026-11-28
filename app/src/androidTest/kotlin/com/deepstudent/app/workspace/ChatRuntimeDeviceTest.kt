package com.deepstudent.app.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.features.chat.api.ChatStreamPhase
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.data.SqlChatRepository
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.data.SqlTaskLedger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatRuntimeDeviceTest {
    @Test fun realSseWritesPartialAndTerminalCheckpointToLocalLedger(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("s117-chat").also { it.mkdirs() }
        val scratch = java.nio.file.Files.createTempDirectory(root.toPath(), "turn-").toFile()
        try {
            val task = ChatSsePeer().use { peer ->
                WorkspaceChatRuntime(scratch, context).use { owner ->
                    val streams = owner.streams
                    val current = streams.start(ChatStreamRequest(peer.url, "test"))
                    val partial = withTimeout(10_000) { streams.observe(current).first { it.text.isNotEmpty() } }
                    assertTrue(partial.text.startsWith("中文"))
                    peer.finish()
                    val completed = withTimeout(10_000) { streams.observe(current).first { it.phase == ChatStreamPhase.Completed } }
                    assertEquals("中文👩‍💻", completed.text)
                    assertTrue(owner.runtime.events(current).isNotEmpty())
                    current
                }
            }
            DatabaseFactory.openLocal(File(scratch, "chat-runtime.db")).use { database ->
                val restored = checkNotNull(SqlTaskLedger(database).snapshot(task))
                assertEquals(RuntimeState.COMPLETED, restored.state)
                assertTrue(restored.execution.checkpoint!!.literal().contains("中文👩‍💻"))
            }
            openWorkspaceChatDatabase(context, scratch).use { database ->
                val chat = checkNotNull(SqlChatRepository(database).load("sess_${task.value.removePrefix("task_")}"))
                assertEquals(listOf("user", "assistant"), chat.messages.map { it.role })
                val blocks = chat.blocks.associateBy { it.id }
                assertEquals(listOf("test", "中文👩‍💻"), chat.messages.map { blocks.getValue(it.blockIds.single()).details.content })
            }
        } finally {
            scratch.deleteRecursively()
        }
    }
}
