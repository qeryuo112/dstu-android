package com.deepstudent.app.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.chat.api.ChatStreamPhase
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.backend.application.ChatStreamHandler
import com.deepstudent.features.chat.backend.application.RuntimeChatStreams
import com.deepstudent.features.chat.data.NetworkChatStream
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.RuntimeController
import com.deepstudent.services.runtime.backend.TaskIdSource
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.data.SqlTaskLedger
import com.deepstudent.app.storage.AndroidOperationLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatRuntimeDeviceTest {
    @Test fun realSseWritesPartialAndTerminalCheckpointToLocalLedger(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "s1-17-chat-${UUID.randomUUID()}.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var task: TaskId? = null
        ChatSsePeer().use { peer ->
            OkHttpNetworkTransport().use { network ->
                DatabaseFactory.openLocal(file).use { database ->
                    val ledger = SqlTaskLedger(database)
                    val registry = HandlerRegistry(mapOf(RuntimeChatStreams.KIND to ChatStreamHandler(NetworkChatStream(network, true))))
                    val lease = LeaseManager(ledger, SystemWallClock, "s117-device", Duration.ofSeconds(30))
                    val supervisor = TaskSupervisor(scope, ledger, lease, registry, SystemWallClock, AndroidOperationLogger)
                    val controller = RuntimeController(ledger, supervisor,
                        TaskIdSource { TaskId.parse("task_${UUID.randomUUID()}") }, SystemWallClock)
                    val streams = RuntimeChatStreams(controller, controller, controller)
                    val currentTask = streams.start(ChatStreamRequest(peer.url, "test"))
                    task = currentTask
                    val partial = withTimeout(10_000) { streams.observe(currentTask).first { it.text.isNotEmpty() } }
                    assertTrue(partial.text.startsWith("中文"))
                    peer.finish()
                    val completed = withTimeout(10_000) { streams.observe(currentTask).first { it.phase == ChatStreamPhase.Completed } }
                    assertEquals("中文👩‍💻", completed.text)
                    assertTrue(controller.events(currentTask).isNotEmpty())
                }
            }
        }
        scope.cancel()
        DatabaseFactory.openLocal(file).use { database ->
            val restored = SqlTaskLedger(database).snapshot(checkNotNull(task))!!
            assertEquals(com.deepstudent.services.runtime.api.RuntimeState.COMPLETED, restored.state)
            assertTrue(restored.execution.checkpoint!!.literal().contains("中文👩‍💻"))
        }
        file.delete()
    }
}
