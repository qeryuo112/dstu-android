package com.deepstudent.services.resources.api

import com.deepstudent.core.common.id.CorrelationId
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Explicit transfer owner. Run transfers off the main thread and call [cancel] from any thread.
 * Android descriptors and the formal resource writer must both honour this owner. Cancellation
 * of a screen observer is deliberately not the same thing as cancelling the transfer.
 */
class DocumentCancellation {
    private val lock = Any()
    private var cancelled = false
    private val callbacks = mutableSetOf<() -> Unit>()

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    fun cancel() {
        val pending = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { try { it() } catch (_: Exception) { } }
    }

    fun check() { if (isCancelled) throw DocumentCancelledException() }

    /** Invokes immediately if already cancelled; close the registration after the operation. */
    fun onCancel(callback: () -> Unit): AutoCloseable {
        val invokeNow = synchronized(lock) {
            if (cancelled) true else { callbacks.add(callback); false }
        }
        if (invokeNow) callback()
        return AutoCloseable { synchronized(lock) { callbacks.remove(callback) } }
    }
}

class DocumentCancelledException : CancellationException("document transfer cancelled")

enum class DocumentPhase { OPEN_SOURCE, OPEN_DESTINATION, COPY, VERIFY, CLEANUP }

data class DocumentProgress(val phase: DocumentPhase, val bytes: Long)

fun interface DocumentProgressObserver { fun onProgress(progress: DocumentProgress) }

/** A bounded-stream producer. The returned stream belongs to the transfer and is closed by it. */
fun interface DocumentSource {
    val correlationId: CorrelationId? get() = null
    fun open(cancellation: DocumentCancellation): InputStream
}

/**
 * The authoritative S2.06 resource copies itself to this output without buffering the whole
 * resource. Return the resource's expected size and hash after copying, or throw on absence.
 * The transfer owns the destination stream; the source must not close it.
 */
fun interface DocumentExportSource {
    suspend fun copyTo(output: OutputStream, cancellation: DocumentCancellation): AssetDigest
}

/**
 * A one-shot destination created by a system ACTION_CREATE_DOCUMENT flow. Implementations must
 * reject reuse. Only this newly selected destination may be deleted by a failed transfer.
 * Read-back is mandatory: closing an arbitrary provider's pipe is not a remote-commit receipt.
 */
interface DocumentNewDestination {
    val correlationId: CorrelationId? get() = null
    fun claim(): Boolean
    fun openWrite(cancellation: DocumentCancellation): DocumentWriteStream
    fun openRead(cancellation: DocumentCancellation): InputStream
    /** False means deletion was refused or unavailable and the partial document may remain. */
    fun delete(): Boolean
}

/** On failure or cancellation, signal the provider instead of closing as a successful write. */
abstract class DocumentWriteStream : OutputStream() { abstract fun abort() }

/**
 * This is the S2.06 port boundary, not a substitute store. It must consume the supplied source
 * through EOF, and atomically commit only after EOF succeeds. A read failure or cancellation
 * observed before the commit boundary must not publish a resource. Once committed, cancellation
 * cannot roll it back. The caller supplies the real accepted S2.06 implementation.
 */
fun interface DocumentImportWriter<T> {
    suspend fun importAtomically(source: InputStream, cancellation: DocumentCancellation): T
}

sealed interface DocumentImportResult<out T> {
    data class Stored<T>(val resource: T, val digest: AssetDigest) : DocumentImportResult<T>
    data class Failed(val phase: DocumentPhase, val failure: DocumentFailure) : DocumentImportResult<Nothing>
    data class Cancelled(val phase: DocumentPhase) : DocumentImportResult<Nothing>
}

enum class DocumentFailure { ACCESS_DENIED, IO, DIGEST_MISMATCH, INVALID_TARGET, OTHER }
/** DELETE_REPORTED is only the provider's return value, not a remote atomicity guarantee. */
enum class DocumentCleanup { NOT_ATTEMPTED, DELETE_REPORTED, MAY_REMAIN }

sealed interface DocumentExportResult {
    data class Verified(val digest: AssetDigest) : DocumentExportResult
    data class Failed(
        val phase: DocumentPhase,
        val failure: DocumentFailure,
        val cleanup: DocumentCleanup,
    ) : DocumentExportResult
    data class Cancelled(val phase: DocumentPhase, val cleanup: DocumentCleanup) : DocumentExportResult
}
