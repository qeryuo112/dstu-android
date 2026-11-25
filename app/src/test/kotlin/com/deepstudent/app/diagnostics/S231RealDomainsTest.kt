package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.backend.application.NoteRepository
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.platform.database.DatabaseLog
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkException
import com.deepstudent.platform.security.SecurityAudit
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.RestorePlanSource
import com.deepstudent.services.backup.data.RestoreAudit
import com.deepstudent.services.backup.data.RestoreCandidateVerifier
import com.deepstudent.services.backup.data.RestoreError
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.backup.data.RestoreExecutor
import com.deepstudent.services.resources.api.CreateAttachment
import com.deepstudent.services.resources.backend.AttachmentRepository
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.RuntimeController
import com.deepstudent.services.runtime.backend.TaskIdSource
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.data.SqlTaskLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class S231RealDomainsTest {
    @Test
    fun realTaskHandlerFailureProducesDurableErrorAndOpaqueTaskCorrelation() = runBlocking {
        val dir = Files.createTempDirectory("s231-task").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = S231DiagnosticBuffer()
        try {
            DatabaseRegistry.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                val clock = WallClock { Instant.parse("2026-11-25T00:00:00Z") }
                val kind = TaskKind.parse("s231.fail")
                val supervisor = TaskSupervisor(
                    scope,
                    ledger,
                    LeaseManager(ledger, clock, "test-owner", Duration.ofMinutes(2)),
                    HandlerRegistry(mapOf(kind to RuntimeTaskHandler { _, _ -> error("SECRET_TASK_FAILURE") })),
                    clock,
                    sink,
                )
                val controller = RuntimeController(ledger, supervisor, TaskIdSource { TaskId.parse("RAW_PRIVATE_TASK") }, clock, 5)
                val task = controller.submit(TaskSubmission(kind, correlationId = CorrelationId.parse("RAW_PRIVATE_CORRELATION")))
                withTimeout(10_000) {
                    while (sink.snapshot().entries.none { it.event == S231Event.RUNTIME_FINISHED }) delay(10)
                }
                assertEquals(RuntimeState.FAILED, controller.snapshot(task.taskId)?.state)
                assertEquals("TASK_FAILED", controller.snapshot(task.taskId)?.execution?.error?.code)
                val record = sink.snapshot().entries.last()
                assertEquals(S231ErrorCode.TASK_FAILED, record.error)
                assertEquals(S231Stage.COMPLETE, record.location.stage)
                assertEquals(1, record.task)
                assertEquals(1, record.correlation)
                assertEquals(S231State.FAILED, record.progress.state)
                val text = S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8)
                listOf("SECRET_TASK_FAILURE", "RAW_PRIVATE_TASK", "RAW_PRIVATE_CORRELATION").forEach { assertFalse(text.contains(it)) }
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun realNotesAndResourcesValidationEmitDomainBoundCodes() = runBlocking {
        val sink = S231DiagnosticBuffer()
        val notes = NotesService(unused(NoteRepository::class.java), sink)
        val resources = AttachmentService(unused(AttachmentRepository::class.java), sink)
        try {
            notes.create(CreateNote(" ", "SECRET_NOTE_BODY"))
            error("expected failure")
        } catch (_: DstuException) { }
        try {
            resources.create(CreateAttachment("", "text/plain", "SECRET_BYTES".toByteArray()))
            error("expected failure")
        } catch (
            _: DstuException,
        ) { }
        assertEquals(listOf(S231ErrorCode.INVALID_NOTE_TITLE, S231ErrorCode.INVALID_ATTACHMENT), sink.snapshot().entries.map { it.error })
        assertEquals(listOf(S231Owner.NOTES, S231Owner.RESOURCES), sink.snapshot().entries.map { it.location.owner })
        assertFalse(S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8).contains("SECRET"))
    }

    @Test
    fun realRestoreAndDatabaseFailuresReachTypedProjection() {
        val root = Files.createTempDirectory("s231-real-domains").toFile()
        val sink = S231DiagnosticBuffer()
        try {
            val verifier = object : RestoreCandidateVerifier {
                override fun verifyDatabases(candidate: File) = Unit
                override fun verifyResources(candidate: File) = Unit
            }
            val plan = RestorePlan(null, RestorePlanSource(), emptyList(), emptyList(), null, emptyList(), false)
            val restore = RestoreExecutor(verifier, audit = RestoreAudit { sink.log(S231DomainEvents.restore(it)) })
            val result = restore.execute(FileDataSpace(root), "private-operation", plan) { error("archive must not open") }
            assertTrue(result is RestoreExecutionResult.Failed && result.error == RestoreError.INVALID_PLAN)
            val backup = sink.snapshot().entries.last()
            assertEquals(S231ErrorCode.INVALID_PLAN, backup.error)
            assertEquals(S231Stage.PREPARE, backup.location.phase)
            val databaseAudit = DatabaseLog { sink.log(S231DomainEvents.database(it)) }
            DatabaseRegistry.openLocal(root.resolve("local.db"), log = databaseAudit).use { database ->
                try {
                    database.execute("this is not SQL")
                    error("expected failure")
                } catch (_: Exception) { }
            }
            assertTrue(sink.snapshot().entries.any { it.error == S231ErrorCode.DATABASE_OPERATION_FAILED })
            assertFalse(S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8).contains("private-operation"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun realSecurityPreflightAuditProjectsFixedCodeAndPhase() {
        val sink = S231DiagnosticBuffer()
        val crypto = DsbkCrypto.production(SecurityAudit { sink.log(S231DomainEvents.security(it)) })
        try {
            crypto.preflight(ByteArrayInputStream("SECRET_INVALID_HEADER".toByteArray()))
            error("expected failure")
        } catch (
            _: DsbkException,
        ) { }
        val entry = sink.snapshot().entries.last()
        assertEquals(S231ErrorCode.HEADER_INVALID, entry.error)
        assertEquals(S231Stage.HEADER, entry.location.phase)
        assertTrue(entry.correlation != null)
        assertFalse(S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8).contains("SECRET"))
    }

    @Test
    fun unrelatedFeatureCannotClaimOtherDomainsFixedCode() {
        val sink = S231DiagnosticBuffer()
        val secret = com.deepstudent.core.common.error.DstuError.internal("INVALID_NOTE_TITLE", "SECRET")
        sink.log(LogRecord(LogLevel.ERROR, "chat.operation", error = secret))
        assertEquals(S231ErrorCode.OTHER, sink.snapshot().entries.single().error)
    }

    private fun <T> unused(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ ->
            error("repository must not be called on validation failure")
        },
    )
}
