package com.deepstudent.app.runtime

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.features.chat.backend.application.ChatTurnRecoveryHandler
import com.deepstudent.features.chat.data.SqlChatTurnStore
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryActions
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryCoordinator
import com.deepstudent.services.runtime.backend.leaseToken
import com.deepstudent.services.runtime.data.SqlTaskLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class ChatTurnRecoveryIntegrationTest {
    private val root = Files.createTempDirectory("s303-chat-recovery").toFile()
    private val runtimeFile = File(root, "runtime.db")
    private val chatFile = File(root, "chat_v2.db")
    private val project = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { project.resolve(it).readBytes() }
    private val start = Instant.parse("2026-11-26T00:00:00Z")
    private val lease = Duration.ofSeconds(30)

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun interruptedTurnReadsSharedPartialThenSettlesBlockBeforeLedgerInterruption() = runBlocking {
        val taskId = TaskId.parse("task_crash_app")
        DatabaseFactory.openShared(chatFile, SharedDatabase.CHAT_V2, source).use { chat ->
            val store = SqlChatTurnStore(chat)
            val request = ChatTurnRequest(taskId, 1, ChatTurnContract.sessionId(taskId), "https://example.test/v1", "prompt")
            store.begin(request, start)
            store.append(request, "durable text", start.plusSeconds(1), start.plusSeconds(1))
            // Crash after shared partial commit; no runtime checkpoint exists.
        }
        DatabaseFactory.openLocal(runtimeFile).use { runtime ->
            val ledger = SqlTaskLedger(runtime)
            ledger.create(taskId, TaskSubmission(ChatTurnContract.KIND), start)
            val claim = ledger.apply(taskId, LedgerCommand.Claim("main:dead", start.plusSeconds(10)), start) as LedgerResult.Applied
            val token = requireNotNull(claim.snapshot.leaseToken())
            ledger.apply(taskId, LedgerCommand.Transition(token, RuntimeState.RUNNING), start.plusSeconds(1))
        }
        DatabaseFactory.openShared(chatFile, SharedDatabase.CHAT_V2, source).use { chat ->
            val store = SqlChatTurnStore(chat)
            DatabaseFactory.openLocal(runtimeFile).use { runtime ->
                val clock = WallClock { start.plusSeconds(40) }
                val ledger = SqlTaskLedger(runtime)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val recoveryHandler = ChatTurnRecoveryHandler(store, clock)
                val supervisor = TaskSupervisor(
                    scope,
                    ledger,
                    LeaseManager(ledger, clock, "main:recovery", lease),
                    HandlerRegistry(
                        mapOf(
                            ChatTurnContract.KIND to
                                com.deepstudent.services.runtime.api.RuntimeTaskHandler { _, _ ->
                                    error("must not execute")
                                },
                        ),
                        mapOf(ChatTurnContract.KIND to store),
                    ),
                    clock,
                    NoopLogger,
                )
                try {
                    val report = RecoveryCoordinator(
                        RecoveryActions(ledger, supervisor, clock, "main:recovery", lease, DeadOwnerPredicate { true }),
                        listOf(RecoveryRegistration(ChatTurnContract.KIND, store, recoveryHandler)),
                        RecoveryPublisher { },
                        NoopLogger,
                        MonotonicClock { 0 },
                    ).recover()
                    assertEquals(1, report.interrupted)
                    val snapshot = requireNotNull(ledger.snapshot(taskId))
                    assertEquals(RuntimeState.INTERRUPTED, snapshot.state)
                    assertNull(snapshot.execution.businessFactId)
                    assertEquals("durable text", store.loadInFlight(taskId, 1, ChatTurnContract.sessionId(taskId))!!.text)
                    assertEquals("error", store.loadInFlight(taskId, 1, ChatTurnContract.sessionId(taskId))!!.blockStatus)
                    assertNull(store.find(taskId, 1))
                } finally {
                    runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
                }
            }
        }
    }
}
