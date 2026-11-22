package com.deepstudent.app.workspace

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.SystemWallClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

internal class WorkspaceChatRuntime(root: File) : AutoCloseable {
    private val database = DatabaseFactory.openLocal(File(root, "chat-runtime.db"))
    private val network = OkHttpNetworkTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = SqlTaskLedger(database)
    private val registry = HandlerRegistry(mapOf(RuntimeChatStreams.KIND to ChatStreamHandler(NetworkChatStream(network, true))))
    private val lease = LeaseManager(ledger, SystemWallClock, "s117-workspace", Duration.ofSeconds(30))
    private val supervisor = TaskSupervisor(scope, ledger, lease, registry, SystemWallClock, AndroidOperationLogger)
    private val runtime = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("task_${UUID.randomUUID()}") }, SystemWallClock)
    val streams = RuntimeChatStreams(runtime, runtime, runtime)
    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        network.close()
        database.close()
    }
}
