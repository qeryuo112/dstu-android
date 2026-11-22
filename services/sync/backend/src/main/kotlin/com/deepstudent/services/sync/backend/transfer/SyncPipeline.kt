package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.error.errorFromThrowable
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import com.deepstudent.services.sync.backend.SyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

class SyncPipeline(
    private val changes: ChangeTransfer,
    private val blobs: BlobTransfer,
    private val minReader: suspend () -> Int,
    private val publishManifest: suspend () -> Unit = {},
    private val logger: Logger = NoopLogger,
) : SyncTransfer {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SyncTransferState(SyncTransferPhase.IDLE))
    override val state: StateFlow<SyncTransferState> = mutableState

    override suspend fun synchronize(): SyncResult<Unit> = mutex.withLock {
        val correlation = UUID.randomUUID().toString()
        val start = System.nanoTime()
        var stage = SyncTransferPhase.IDLE
        var unexpectedFailure = false
        try {
            val reader = minReader()
            changes.resumePending()
            stage = SyncTransferPhase.BLOBS
            phase(stage, correlation)
            blobs.synchronize()
            stage = SyncTransferPhase.DOWNLOAD
            phase(stage, correlation)
            changes.download()
            stage = SyncTransferPhase.BLOBS
            phase(stage, correlation)
            blobs.synchronize()
            stage = SyncTransferPhase.UPLOAD
            phase(stage, correlation)
            changes.upload(reader)
            publishManifest()
            stage = SyncTransferPhase.COMPLETE
            phase(stage, correlation)
            SyncResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, correlation, SyncError.CANCELLED)
            throw cancelled
        } catch (failure: SyncFailure) {
            failed(correlation, failure.error)
        } catch (_: IOException) {
            // Remote providers translate transport failures to SyncFailure; raw I/O here is local storage.
            failed(correlation, SyncError.STORAGE)
        } catch (failure: Exception) {
            // Preserve the original cause for callers; only a sanitized diagnostic enters the log.
            unexpectedFailure = true
            unexpected(correlation, stage, failure)
            throw failure
        } finally {
            finished(correlation, stage, start, unexpectedFailure)
        }
    }

    private suspend fun phase(phase: SyncTransferPhase, correlation: String) {
        currentCoroutineContext().ensureActive()
        mutableState.value = SyncTransferState(phase, correlation)
        logger.info(
            "sync.transfer.phase",
            JsonValue.Object.Builder().put("owner", "sync")
                .put("correlationId", correlation).put("phase", phase.name).build(),
        )
    }

    private fun failed(correlation: String, error: SyncError): SyncResult<Unit> {
        mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, correlation, error)
        return SyncResult.Failure(error)
    }

    private fun unexpected(correlation: String, stage: SyncTransferPhase, failure: Exception) {
        mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, correlation)
        val fields = JsonValue.Object.Builder().put("owner", "sync")
            .put("correlationId", correlation).put("stage", stage.name)
            .put("error", "UNEXPECTED").build()
        try {
            val diagnostic = if (failure is DstuException) {
                DstuError.internal("UNEXPECTED", "operation failed")
            } else {
                errorFromThrowable(failure)
            }
            logger.error("sync.transfer.unexpected", diagnostic, fields)
        } catch (_: Exception) {
            // Diagnostics must not replace the original failure.
        }
    }

    private fun finished(correlation: String, stage: SyncTransferPhase, start: Long, unexpected: Boolean) {
        val state = mutableState.value
        val fields = JsonValue.Object.Builder().put("owner", "sync")
            .put("correlationId", correlation).put("phase", state.phase.name)
            .put("stage", stage.name)
            .put("error", if (unexpected) "UNEXPECTED" else state.error?.name ?: "none")
            .put("elapsedMs", (System.nanoTime() - start) / 1_000_000).build()
        try {
            logger.info("sync.transfer.finished", fields)
        } catch (_: Exception) {
            // Diagnostics must not change the synchronization result.
        }
    }
}
