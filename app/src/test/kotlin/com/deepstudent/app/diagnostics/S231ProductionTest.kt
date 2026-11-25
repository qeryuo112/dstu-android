package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.error.asCancellationError
import com.deepstudent.core.common.error.errorFromThrowable
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.ConfigurationStore
import com.deepstudent.services.sync.backend.DeviceIdentity
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.SyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S231ProductionTest {
    @Test
    fun realPairingServiceEmitsFailureThroughTheSameDiagnosticSink() = runBlocking {
        val sink = S231DiagnosticBuffer()
        val source = FailingSource(SyncError.INVALID_REPOSITORY)
        val service = PairingService(source, source, source, logger = sink)
        val secret = "SECRET_USERNAME_PASSWORD_BODY_PATH"
        val result = service.pair(PairingMode.JOIN, PairingRequest("https://$secret.invalid", secret, secret, secret.toCharArray()))
        assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), result)
        val entries = sink.snapshot().entries
        assertEquals(S231Event.SYNC_CONFIGURATION_STARTED, entries.first().event)
        assertEquals(S231Event.SYNC_CONFIGURATION_FINISHED, entries.last().event)
        assertEquals(entries.first().correlation, entries.last().correlation)
        assertEquals(S231ErrorCode.INVALID_REPOSITORY, entries.last().error)
        val output = S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8)
        assertTrue(output.contains("error=INVALID_REPOSITORY"))
        assertFalse(output.contains(secret))
    }

    @Test
    fun everyListedSyncCodeIsEmittedByRealPairingFailurePath() = runBlocking {
        val codes = S231ErrorCode.entries.filter { candidate ->
            SyncError.entries.any { it.name == candidate.name } && candidate != S231ErrorCode.CANCELLED
        }
        assertTrue(codes.isNotEmpty())
        for (code in codes) {
            val source = FailingSource(SyncError.valueOf(code.name))
            val sink = S231DiagnosticBuffer()
            val service = PairingService(source, source, source, logger = sink)
            val result = service.current()
            assertEquals(SyncResult.Failure(SyncError.valueOf(code.name)), result)
            assertEquals(code, sink.snapshot().entries.last().error)
        }
    }

    @Test
    fun realCoreErrorFactoriesAreSafeThroughLogger() {
        val sink = S231DiagnosticBuffer()
        val fields = JsonValue.Object.Builder().put("owner", "runtime").build()
        val unexpected = errorFromThrowable(IllegalArgumentException("SECRET"))
        val cancelled = CancellationException("SECRET").asCancellationError()
        sink.log(LogRecord(LogLevel.ERROR, "runtime.finished", fields = fields, error = unexpected))
        sink.log(LogRecord(LogLevel.WARN, "runtime.finished", fields = fields, error = cancelled))
        assertEquals(listOf(S231ErrorCode.UNEXPECTED, S231ErrorCode.CANCELLED), sink.snapshot().entries.map { it.error })
        assertFalse(S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8).contains("SECRET"))
    }

    @Test
    fun ringIsBoundedAndCorrelationsAndLibrariesAreOpaqueAndStable() {
        val sink = S231DiagnosticBuffer()
        val fields = JsonValue.Object.Builder().put("owner", "sync").put("libraryId", "private-library-token")
            .put("stage", "APPLY").put("database", "vfs").put("status", "FAILED")
            .put("authorization", "Bearer SECRET").build()
        repeat(140) {
            sink.log(
                LogRecord(
                    LogLevel.ERROR,
                    "sync.transfer.finished",
                    TaskId.parse("private-task"),
                    CorrelationId.parse("private-correlation"),
                    fields,
                ),
            )
        }
        val snapshot = sink.snapshot()
        assertEquals(128, snapshot.entries.size)
        assertTrue(snapshot.entries.all { it.task == 1 && it.correlation == 1 && it.library == 1 })
        assertEquals(S231Stage.APPLY, snapshot.entries.first().location.stage)
        val text = S231TextBundle.create(snapshot).toString(Charsets.UTF_8)
        listOf("private-library-token", "private-task", "private-correlation", "Bearer SECRET").forEach {
            assertFalse(text.contains(it))
        }
    }

    @Test
    fun unlistedRealSyncErrorIsVisibleOnlyAsOther() = runBlocking {
        val sink = S231DiagnosticBuffer()
        val source = FailingSource(SyncError.CREDENTIAL_KEY_LOST)
        val service = PairingService(source, source, source, logger = sink)
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_KEY_LOST), service.current())
        val snapshot = sink.snapshot()
        assertEquals(S231ErrorCode.OTHER, snapshot.entries.last().error)
        val output = S231TextBundle.create(snapshot).toString(Charsets.UTF_8)
        assertTrue(output.contains("error=OTHER"))
        assertFalse(output.contains("CREDENTIAL_KEY_LOST"))
    }

    @Test
    fun manyDistinctIdentifiersKeepRecentAliasesAvailable() {
        val sink = S231DiagnosticBuffer()
        repeat(1100) { index ->
            sink.log(LogRecord(LogLevel.INFO, "sync.transfer.phase", taskId = TaskId.parse("task-$index")))
        }
        val recent = sink.snapshot().entries
        assertEquals(S231Snapshot.MAX_RECORDS, recent.size)
        assertTrue(recent.all { it.task != null })
        assertEquals(1100, recent.last().task)
        sink.log(LogRecord(LogLevel.INFO, "sync.transfer.phase", taskId = TaskId.parse("task-1099")))
        assertEquals(1100, sink.snapshot().entries.last().task)
    }

    private class FailingSource(private val failure: SyncError) :
        ConfigurationStore,
        DeviceIdentity,
        ProviderFactory,
        ObjectProvider {
        override fun read(): SyncConfiguration? = throw SyncFailure(failure)
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) = Unit
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = Unit
        override fun clear() = Unit
        override fun getOrCreate() = "private-device"
        override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider = this
        override suspend fun get(key: String): ByteArray? = throw SyncFailure(failure)
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) = Unit
        override suspend fun list(prefix: String): List<String> = emptyList()
        override suspend fun delete(key: String) = Unit
    }
}
