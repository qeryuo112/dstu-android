package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncFailure
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.util.UUID

class SyncPipelineTest {
    @Test fun expectedStorageFailureReturnsStorageAndRecordsCorrelationAndStage() = runBlocking {
        val logger = RecordingLogger()
        val pipeline = pipeline(Provider { throw SyncFailure(SyncError.STORAGE) }, logger)

        assertThat(pipeline.synchronize()).isEqualTo(SyncResult.Failure(SyncError.STORAGE))

        val state = pipeline.state.value
        assertThat(state.phase).isEqualTo(SyncTransferPhase.FAILED)
        assertThat(state.error).isEqualTo(SyncError.STORAGE)
        val correlation = requireNotNull(state.correlationId)
        UUID.fromString(correlation)
        val phase = logger.single("sync.transfer.phase")
        assertThat(phase.correlationId?.value).isEqualTo(correlation)
        assertThat(phase.field("phase")).isEqualTo("BLOBS")
        val finished = logger.single("sync.transfer.finished")
        assertThat(finished.correlationId?.value).isEqualTo(correlation)
        assertThat(finished.field("phase")).isEqualTo("FAILED")
        assertThat(finished.field("stage")).isEqualTo("BLOBS")
        assertThat(finished.field("error")).isEqualTo("STORAGE")
        assertThat(logger.records.map { it.event }).doesNotContain("sync.transfer.unexpected")
    }

    @Test fun expectedStorageIoFailureReturnsStorageWithoutUnexpectedClassification() = runBlocking {
        val secret = "token=io-secret"
        val logger = RecordingLogger()
        val pipeline = pipeline(Provider { throw IOException("local storage failed $secret") }, logger)

        assertThat(pipeline.synchronize()).isEqualTo(SyncResult.Failure(SyncError.STORAGE))

        val state = pipeline.state.value
        assertThat(state.phase).isEqualTo(SyncTransferPhase.FAILED)
        assertThat(state.error).isEqualTo(SyncError.STORAGE)
        val correlation = requireNotNull(state.correlationId)
        val finished = logger.single("sync.transfer.finished")
        assertThat(finished.correlationId?.value).isEqualTo(correlation)
        assertThat(finished.field("stage")).isEqualTo("BLOBS")
        assertThat(finished.field("error")).isEqualTo("STORAGE")
        assertThat(logger.records.map { it.event }).doesNotContain("sync.transfer.unexpected")
        assertThat(logger.contains(secret)).isFalse()
    }

    @Test fun cancellationIsRethrownAndRecordsCancelledStage() = runBlocking {
        val secret = "token=cancel-secret"
        val expected = CancellationException("cancelled $secret")
        val logger = RecordingLogger()
        val pipeline = pipeline(Provider { throw expected }, logger)

        val actual = expectFailure { pipeline.synchronize() }

        assertThat(actual).isSameInstanceAs(expected)
        val state = pipeline.state.value
        assertThat(state.phase).isEqualTo(SyncTransferPhase.FAILED)
        assertThat(state.error).isEqualTo(SyncError.CANCELLED)
        val correlation = requireNotNull(state.correlationId)
        val finished = logger.single("sync.transfer.finished")
        assertThat(finished.correlationId?.value).isEqualTo(correlation)
        assertThat(finished.field("stage")).isEqualTo("BLOBS")
        assertThat(finished.field("error")).isEqualTo("CANCELLED")
        assertThat(logger.records.map { it.event }).doesNotContain("sync.transfer.unexpected")
        assertThat(logger.contains(secret)).isFalse()
    }

    @Test fun unknownProgrammingFailureIsRethrownWithSafeDiagnostic() = runBlocking {
        val secret = "token=super-secret"
        val expected = IllegalStateException("unexpected programming failure $secret")
        val logger = RecordingLogger()
        val pipeline = pipeline(
            Provider { prefix ->
                if (prefix == "data_governance/manifests") expected else null
            },
            logger,
        )

        val actual = expectFailure { pipeline.synchronize() }

        assertThat(actual).isSameInstanceAs(expected)
        val state = pipeline.state.value
        assertThat(state.phase).isEqualTo(SyncTransferPhase.FAILED)
        assertThat(state.error).isNull()
        val correlation = requireNotNull(state.correlationId)
        val unexpected = logger.single("sync.transfer.unexpected")
        assertThat(unexpected.level).isEqualTo(LogLevel.ERROR)
        assertThat(unexpected.correlationId?.value).isEqualTo(correlation)
        assertThat(unexpected.field("stage")).isEqualTo("DOWNLOAD")
        assertThat(unexpected.field("error")).isEqualTo("UNEXPECTED")
        val error = requireNotNull(unexpected.error)
        assertThat(error.code).isEqualTo("UNEXPECTED")
        assertThat(error.details["causeClass"]).isEqualTo("java.lang.IllegalStateException")
        val finished = logger.single("sync.transfer.finished")
        assertThat(finished.correlationId?.value).isEqualTo(correlation)
        assertThat(finished.field("phase")).isEqualTo("FAILED")
        assertThat(finished.field("stage")).isEqualTo("DOWNLOAD")
        assertThat(finished.field("error")).isEqualTo("UNEXPECTED")
        assertThat(logger.contains(secret)).isFalse()
    }

    @Test fun unexpectedDstuExceptionMessageIsNotLogged() = runBlocking {
        val secret = "token=dstu-secret"
        val expected = DstuException(DstuError.internal("UNEXPECTED", "sensitive $secret"))
        val logger = RecordingLogger()
        val pipeline = pipeline(
            Provider { prefix ->
                if (prefix == "data_governance/manifests") expected else null
            },
            logger,
        )

        val actual = expectFailure { pipeline.synchronize() }

        assertThat(actual).isSameInstanceAs(expected)
        val unexpected = logger.single("sync.transfer.unexpected")
        val error = requireNotNull(unexpected.error)
        assertThat(error.code).isEqualTo("UNEXPECTED")
        assertThat(error.message).isEqualTo("operation failed")
        assertThat(error.details).isEmpty()
        assertThat(logger.contains(secret)).isFalse()
    }

    @Test fun successfulPipelineSharesCorrelationAcrossStages() = runBlocking {
        val logger = RecordingLogger()
        val pipeline = pipeline(Provider(), logger)

        assertThat(pipeline.synchronize()).isEqualTo(SyncResult.Success(Unit))

        val state = pipeline.state.value
        assertThat(state.phase).isEqualTo(SyncTransferPhase.COMPLETE)
        assertThat(state.error).isNull()
        val correlation = requireNotNull(state.correlationId)
        UUID.fromString(correlation)
        val phases = logger.records.filter { it.event == "sync.transfer.phase" }
        assertThat(phases.map { it.field("phase") }).containsExactly("BLOBS", "DOWNLOAD", "BLOBS", "UPLOAD", "COMPLETE").inOrder()
        assertThat(phases.all { it.correlationId?.value == correlation }).isTrue()
        val finished = logger.single("sync.transfer.finished")
        assertThat(finished.correlationId?.value).isEqualTo(correlation)
        assertThat(finished.field("phase")).isEqualTo("COMPLETE")
        assertThat(finished.field("stage")).isEqualTo("COMPLETE")
        assertThat(finished.field("error")).isEqualTo("none")
        assertThat(logger.records.map { it.event }).doesNotContain("sync.transfer.unexpected")
    }

    private fun pipeline(provider: ObjectProvider, logger: Logger = NoopLogger): SyncPipeline = SyncPipeline(
        changes = ChangeTransfer(provider, Compression, State(), Domain(), "local"),
        blobs = BlobTransfer(provider, Blobs()),
        minReader = { 3 },
        publishManifest = {},
        logger = logger,
    )

    private suspend fun expectFailure(block: suspend () -> Any?): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("expected failure")
    }

    private fun LogRecord.field(name: String): String? = (fields[name] as? JsonValue.Text)?.value

    private class RecordingLogger : Logger {
        val records = mutableListOf<LogRecord>()

        override fun log(record: LogRecord) {
            records += record
        }

        fun single(event: String): LogRecord = records.single { it.event == event }

        fun contains(text: String): Boolean = records.any { record ->
            record.event.contains(text) ||
                record.fields.literal().contains(text) ||
                record.error?.code?.contains(text) == true ||
                record.error?.message?.contains(text) == true ||
                record.error?.details?.values?.any { it.contains(text) } == true
        }
    }

    private class Provider(private val listFailure: (String) -> Throwable? = { null }) : ObjectProvider {
        private val objects = linkedMapOf<String, ByteArray>()

        override suspend fun get(key: String): ByteArray? = objects[key]?.copyOf()

        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            if (ifAbsent && key in objects) throw SyncFailure(SyncError.ALREADY_EXISTS)
            objects[key] = value.copyOf()
        }

        override suspend fun list(prefix: String): List<String> {
            listFailure(prefix)?.let { throw it }
            return objects.keys.filter { it.startsWith(prefix) }.sorted()
        }

        override suspend fun delete(key: String) {
            objects.remove(key)
        }
    }

    private class State : SyncUploadState {
        private var sequence = 0L

        override fun published(): Long = sequence

        override fun markPublished(seq: Long) {
            sequence = seq
        }
    }

    private class Domain : DomainSyncAdapter {
        override fun pending(): List<ByteArray> = emptyList()

        override fun markPublished(changeLogIds: List<Long>, version: Long) = Unit

        override fun cursor(device: String): Long = 0L

        override fun apply(device: String, seq: Long, changes: List<ByteArray>) = Unit
    }

    private class Blobs : SyncBlobs {
        override fun list(): List<SyncBlob> = emptyList()

        override fun read(blob: SyncBlob): ByteArray? = null

        override fun install(blob: SyncBlob, bytes: ByteArray) = Unit
    }

    private object Compression : SyncCompression {
        override fun encode(bytes: ByteArray): ByteArray = bytes

        override fun decode(bytes: ByteArray): ByteArray = bytes
    }
}
