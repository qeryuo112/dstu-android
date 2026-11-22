package com.deepstudent.app.backup

import com.deepstudent.platform.security.DsbkError
import com.deepstudent.platform.security.DsbkException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Correlates the transport operation without logging passwords, paths, or archive contents. */
internal class PortableOperation(
    private val name: String,
    private val cancelled: () -> Boolean,
    private val record: (PortableBackupEvent) -> Unit,
) {
    private val started = System.nanoTime()
    private val id = UUID.randomUUID().toString()
    private var wasCancelled = false
    private val check: () -> Unit = {
        if (Thread.currentThread().isInterrupted || cancelled()) {
            wasCancelled = true
            throw CancellationException()
        }
    }

    fun <T> run(body: (() -> Unit, () -> Unit) -> T): T {
        emit("started")
        try {
            check()
            return body(check) { emit("cleanup-pending", PortableFileError.IO_FAILED) }.also { emit("succeeded") }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val mapped = failure(error)
            emit("failed", mapped.error)
            throw mapped
        }
    }

    private fun failure(error: Exception): PortableFileException = when {
        wasCancelled || error is CancellationException || error is InterruptedException -> PortableFileException(PortableFileError.CANCELLED)
        error is PortableFileException -> error
        error is DsbkException -> cryptoFailure(error)
        else -> PortableFileException(PortableFileError.IO_FAILED)
    }

    private fun cryptoFailure(error: DsbkException): PortableFileException {
        val code = when (error.error) {
            DsbkError.AUTHENTICATION_FAILED -> PortableFileError.AUTHENTICATION_FAILED
            DsbkError.V1_CONVERSION_REQUIRED -> PortableFileError.V1_CONVERSION_REQUIRED
            else -> PortableFileError.CRYPTO_REJECTED
        }
        return PortableFileException(code, error.error)
    }

    private fun emit(status: String, error: PortableFileError? = null) {
        try { record(PortableBackupEvent(id, name, "portable-files", status, (System.nanoTime() - started) / 1_000_000, error)) }
        catch (_: Exception) { /* Logging cannot change whether a file was atomically published. */ }
    }
}
