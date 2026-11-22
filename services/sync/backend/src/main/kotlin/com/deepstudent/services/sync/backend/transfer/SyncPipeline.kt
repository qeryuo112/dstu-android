package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.api.SyncTransferState
import com.deepstudent.services.sync.backend.SyncFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        try {
            val reader = minReader()
            changes.resumePending()
            phase(SyncTransferPhase.BLOBS, correlation)
            blobs.synchronize()
            phase(SyncTransferPhase.DOWNLOAD, correlation)
            changes.download()
            phase(SyncTransferPhase.BLOBS, correlation)
            blobs.synchronize()
            phase(SyncTransferPhase.UPLOAD, correlation)
            changes.upload(reader)
            publishManifest()
            phase(SyncTransferPhase.COMPLETE, correlation)
            SyncResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, correlation, SyncError.CANCELLED)
            throw cancelled
        } catch (failure: SyncFailure) {
            failed(correlation, failure.error)
        } catch (_: Exception) {
            failed(correlation, SyncError.STORAGE)
        } finally {
            logger.info("sync.transfer.finished", JsonValue.Object.Builder().put("owner", "sync")
                .put("correlationId", correlation).put("phase", mutableState.value.phase.name)
                .put("error", mutableState.value.error?.name ?: "none")
                .put("elapsedMs", (System.nanoTime() - start) / 1_000_000).build())
        }
    }

    private suspend fun phase(phase: SyncTransferPhase, correlation: String) {
        currentCoroutineContext().ensureActive()
        mutableState.value = SyncTransferState(phase, correlation)
        logger.info("sync.transfer.phase", JsonValue.Object.Builder().put("owner", "sync")
            .put("correlationId", correlation).put("phase", phase.name).build())
    }

    private fun failed(correlation: String, error: SyncError): SyncResult<Unit> {
        mutableState.value = SyncTransferState(SyncTransferPhase.FAILED, correlation, error)
        return SyncResult.Failure(error)
    }
}
