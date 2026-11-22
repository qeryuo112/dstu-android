package com.deepstudent.app.workspace

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.AndroidOperationLogger
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
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCancellationDeviceTest {
    @Test fun explicitCancelPreservesPartialTextAndDoesNotAffectOtherTask(): Unit = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "s117-chat-cancel-${UUID.randomUUID()}")
        root.mkdirs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            DatabaseFactory.openLocal(File(root, "runtime.db")).use { db ->
                OkHttpNetworkTransport().use { network ->
                    ChatSsePeer().use { first ->
                        ChatSsePeer().use { second ->
                            val ledger = SqlTaskLedger(db)
                            val registry = HandlerRegistry(mapOf(RuntimeChatStreams.KIND to ChatStreamHandler(NetworkChatStream(network, true))))
                            val leases = LeaseManager(ledger, SystemWallClock, "s117-cancel", Duration.ofSeconds(30))
                            val supervisor = TaskSupervisor(scope, ledger, leases, registry, SystemWallClock, AndroidOperationLogger)
                            val runtime = RuntimeController(ledger, supervisor,
                                TaskIdSource { TaskId.parse("task_${UUID.randomUUID()}") }, SystemWallClock)
                            val streams = RuntimeChatStreams(runtime, runtime, runtime)
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
                        }
                    }
                }
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
