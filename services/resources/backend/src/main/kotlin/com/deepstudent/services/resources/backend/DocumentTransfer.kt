package com.deepstudent.services.resources.backend

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.DocumentCancellation
import com.deepstudent.services.resources.api.DocumentCancelledException
import com.deepstudent.services.resources.api.DocumentCleanup
import com.deepstudent.services.resources.api.DocumentExportResult
import com.deepstudent.services.resources.api.DocumentExportSource
import com.deepstudent.services.resources.api.DocumentFailure
import com.deepstudent.services.resources.api.DocumentImportResult
import com.deepstudent.services.resources.api.DocumentImportWriter
import com.deepstudent.services.resources.api.DocumentSource
import com.deepstudent.services.resources.api.DocumentNewDestination
import com.deepstudent.services.resources.api.DocumentPhase
import com.deepstudent.services.resources.api.DocumentProgress
import com.deepstudent.services.resources.api.DocumentProgressObserver
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** No UI lifecycle; the caller owns a worker thread and the cancellation token. */
class DocumentTransfer(private val logger: Logger, private val clock: MonotonicClock) {
    /** Writer is the accepted resource store, not an in-memory stand-in in production. */
    suspend fun <T> import(
        source: DocumentSource,
        writer: DocumentImportWriter<T>,
        cancellation: DocumentCancellation,
        progress: DocumentProgressObserver = DocumentProgressObserver { },
    ): DocumentImportResult<T> = monitorCancellation(cancellation) {
        val trace = DocumentTrace(logger, clock, source.correlationId)
        var phase = DocumentPhase.OPEN_SOURCE
        try {
            trace.emit(phase, "started")
            cancellation.check()
            val raw = source.open(cancellation)
            var storedResult: Pair<T, AssetDigest>? = null
            var operationFailure: Throwable? = null
            try {
                phase = DocumentPhase.COPY
                trace.emit(phase, "started")
                val tracked = DocumentDigestingInput(raw, cancellation, progress)
                val stored = invokeAtomicWriter(writer, tracked, cancellation)
                // The writer must not commit early; this alone cannot repair a broken writer.
                val digest = tracked.finishedDigest ?: throw IOException("writer did not consume source")
                storedResult = stored to digest
            } catch (failure: Throwable) {
                operationFailure = failure
                throw failure
            } finally {
                closeSource(raw, operationFailure, storedResult != null, trace)
            }
            val result = checkNotNull(storedResult)
            // Do not turn an already committed atomic write into a reported cancellation.
            trace.emit(phase, "completed")
            DocumentImportResult.Stored(result.first, result.second)
        } catch (failure: Exception) {
            val wasCancelled = isCancelled(failure, cancellation)
            val reason = failure.toDocumentFailure()
            trace.emit(phase, if (wasCancelled) "cancelled" else "failed", if (wasCancelled) null else reason.name)
            rethrowParentCancellation(failure)
            if (wasCancelled) DocumentImportResult.Cancelled(phase) else DocumentImportResult.Failed(phase, reason)
        }
    }

    suspend fun export(
        source: DocumentExportSource,
        destination: DocumentNewDestination,
        cancellation: DocumentCancellation,
        progress: DocumentProgressObserver = DocumentProgressObserver { },
    ): DocumentExportResult = monitorCancellation(cancellation) {
        val trace = DocumentTrace(logger, clock, destination.correlationId)
        var phase = DocumentPhase.OPEN_DESTINATION
        var claimed = false
        try {
            if (!destination.claim()) {
                trace.emit(phase, "failed", DocumentFailure.INVALID_TARGET.name)
                return@monitorCancellation DocumentExportResult.Failed(phase, DocumentFailure.INVALID_TARGET, DocumentCleanup.NOT_ATTEMPTED)
            }
            claimed = true
            trace.emit(phase, "started")
            cancellation.check()
            val output = destination.openWrite(cancellation)
            phase = DocumentPhase.COPY
            trace.emit(phase, "started")
            val expected = writeExport(source, output, cancellation, progress)
            phase = DocumentPhase.VERIFY
            trace.emit(phase, "started")
            cancellation.check()
            destination.openRead(cancellation).use { input -> verifyReadback(input, expected, cancellation, progress) }
            cancellation.check()
            trace.emit(phase, "completed")
            DocumentExportResult.Verified(expected)
        } catch (failure: Exception) {
            val wasCancelled = isCancelled(failure, cancellation)
            val reason = failure.toDocumentFailure()
            val cleanup = if (claimed) cleanup(destination, trace) else DocumentCleanup.NOT_ATTEMPTED
            trace.emit(phase, if (wasCancelled) "cancelled" else "failed", if (wasCancelled) null else reason.name)
            rethrowParentCancellation(failure)
            if (wasCancelled) DocumentExportResult.Cancelled(phase, cleanup)
            else DocumentExportResult.Failed(phase, reason, cleanup)
        }
    }
}

private fun closeSource(input: java.io.InputStream, original: Throwable?, committed: Boolean, trace: DocumentTrace) {
    try { input.close() } catch (failure: Exception) {
        if (original != null) original.addSuppressed(failure)
        else if (!committed) throw failure
        else trace.emit(DocumentPhase.CLEANUP, "failed", DocumentFailure.IO.name)
    }
}

private suspend fun <T> invokeAtomicWriter(
    writer: DocumentImportWriter<T>, input: java.io.InputStream, cancellation: DocumentCancellation,
): T {
    val child = Job(currentCoroutineContext()[Job])
    val registration = cancellation.onCancel { child.cancel(DocumentCancelledException()) }
    var returned: Result<T>? = null
    return try {
        withContext(child) { writer.importAtomically(input, cancellation).also { returned = Result.success(it) } }
    } catch (failure: CancellationException) {
        // A writer that returned has crossed its own commit boundary even if token cancellation
        // raced the context handoff. Do not present that committed resource as cancelled.
        returned?.getOrThrow() ?: throw failure
    } finally { registration.close(); child.complete() }
}

private fun isCancelled(failure: Exception, token: DocumentCancellation): Boolean =
    failure is CancellationException || token.isCancelled

private fun rethrowParentCancellation(failure: Exception) {
    if (failure is CancellationException && failure !is DocumentCancelledException) throw failure
}

private suspend fun writeExport(
    source: DocumentExportSource, output: com.deepstudent.services.resources.api.DocumentWriteStream,
    cancellation: DocumentCancellation, progress: DocumentProgressObserver,
): AssetDigest {
    var completed = false
    try {
        val tracked = DocumentDigestingOutput(output, cancellation, progress)
        val expected = source.copyTo(tracked, cancellation)
        if (tracked.finish() != expected) throw DocumentDigestMismatch()
        cancellation.check()
        output.close()
        completed = true
        return expected
    } finally {
        if (!completed) try { output.abort() } catch (_: Exception) { }
    }
}

private fun cleanup(destination: DocumentNewDestination, trace: DocumentTrace): DocumentCleanup {
    trace.emit(DocumentPhase.CLEANUP, "started")
    val deleted = try { destination.delete() } catch (_: Exception) { false }
    val result = if (deleted) DocumentCleanup.DELETE_REPORTED else DocumentCleanup.MAY_REMAIN
    trace.emit(DocumentPhase.CLEANUP, "completed", result.name)
    return result
}

private fun verifyReadback(
    input: java.io.InputStream,
    expected: AssetDigest,
    cancellation: DocumentCancellation,
    progress: DocumentProgressObserver,
) {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        cancellation.check()
        // Read at most the expected payload plus one trailing byte to prove EOF.
        val remaining = expected.size - total
        val maximum = if (remaining < buffer.size) remaining.toInt() + 1 else buffer.size
        val count = input.read(buffer, 0, maximum)
        if (count < 0) break
        if (count == 0) throw IOException("zero-byte read")
        if (count > remaining) throw DocumentDigestMismatch()
        cancellation.check()
        hash.update(buffer, 0, count)
        total += count
        progress.report(DocumentPhase.VERIFY, total)
    }
    cancellation.check()
    if (total != expected.size || Digests.toHex(hash.digest()) != expected.sha256) throw DocumentDigestMismatch()
}

/** A progress observer cannot turn successful I/O into a failed transfer. */
private fun DocumentProgressObserver.report(phase: DocumentPhase, bytes: Long) {
    try { onProgress(DocumentProgress(phase, bytes)) } catch (_: Exception) { }
}

private class DocumentDigestMismatch : IOException()

private fun Exception.toDocumentFailure(): DocumentFailure = when (this) {
    is DocumentDigestMismatch -> DocumentFailure.DIGEST_MISMATCH
    is SecurityException -> DocumentFailure.ACCESS_DENIED
    is IOException -> DocumentFailure.IO
    else -> DocumentFailure.OTHER
}

private class DocumentTrace(private val logger: Logger, private val clock: MonotonicClock, correlation: CorrelationId?) {
    private val id = correlation ?: CorrelationId.parse(UUID.randomUUID().toString())
    private val start = clock.nowNanos()

    fun emit(phase: DocumentPhase, state: String, outcome: String? = null) {
        val elapsed = ClockChecks.monotonicElapsedMillis(start, clock.nowNanos()) ?: 0
        val fields = JsonValue.Object.Builder().put("owner", "services/resources/saf")
            .put("phase", phase.name).put("state", state).put("elapsedMs", elapsed)
        if (outcome != null) fields.put("outcome", outcome)
        try {
            logger.log(LogRecord(if (state == "failed") LogLevel.ERROR else LogLevel.INFO,
                "resources.document.transfer", correlationId = id, fields = fields.build()))
        } catch (_: Exception) { }
    }
}

private class DocumentDigestingInput(
    private val source: java.io.InputStream,
    private val token: DocumentCancellation,
    private val observer: DocumentProgressObserver,
) : java.io.InputStream() {
    private val hash = MessageDigest.getInstance("SHA-256")
    private val single = ByteArray(1)
    private var count = 0L
    var finishedDigest: AssetDigest? = null
        private set

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (finishedDigest != null) return -1
        token.check()
        val read = source.read(bytes, offset, minOf(length, 16 * 1024))
        token.check()
        if (read < 0) {
            finishedDigest = AssetDigest(Digests.toHex(hash.digest()), count)
            return -1
        }
        if (read == 0) throw IOException("zero-byte read")
        hash.update(bytes, offset, read)
        count = Math.addExact(count, read.toLong())
        observer.report(DocumentPhase.COPY, count)
        return read
    }
    // Source ownership stays with DocumentTransfer; a consumer cannot close it prematurely.
    override fun close() = Unit
}

/** Cancel both while a writer suspends and while SAF is polling a pipe. */
private suspend fun <T> monitorCancellation(token: DocumentCancellation, action: suspend () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val monitor = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!finished.get()) token.cancel() }
    }
    try { action() } finally { finished.set(true); monitor.cancel() }
}

private class DocumentDigestingOutput(
    private val output: java.io.OutputStream,
    private val token: DocumentCancellation,
    private val observer: DocumentProgressObserver,
) : java.io.OutputStream() {
    private val hash = MessageDigest.getInstance("SHA-256")
    private val single = ByteArray(1)
    private var count = 0L
    private var finished = false
    override fun write(value: Int) { single[0] = value.toByte(); write(single, 0, 1) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        check(!finished)
        var pos = 0
        while (pos < length) {
            token.check()
            val chunk = minOf(length - pos, 16 * 1024)
            output.write(bytes, offset + pos, chunk)
            hash.update(bytes, offset + pos, chunk)
            pos += chunk
            count = Math.addExact(count, chunk.toLong())
            observer.report(DocumentPhase.COPY, count)
        }
    }
    override fun flush() = output.flush()
    override fun close() = Unit // resource writer is not the descriptor owner
    fun finish(): AssetDigest {
        check(!finished)
        finished = true
        token.check()
        return AssetDigest(Digests.toHex(hash.digest()), count)
    }
}
