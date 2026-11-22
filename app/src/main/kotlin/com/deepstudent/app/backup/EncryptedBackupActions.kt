package com.deepstudent.app.backup

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkStagingArea
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.backend.ArchivePreflight
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class EncryptedBackupIntent(val outputName: String) {
    IMPORT("verified.zip"), EXPORT("export.dsbk"), EXPORT_PORTABLE("export.zip"),
}

internal class EncryptedBackupPrepared(
    val directory: File,
    val artifact: File,
    val trace: EncryptedBackupTrace,
    val proof: ValidatedBackupZip? = null,
    val plan: RestorePlan? = null,
) : AutoCloseable {
    override fun close() {
        runCatching { removeOwnedTree(directory.toPath()) }.onFailure { trace.log("cleanup", "failed", "IO_FAILED") }
    }
}

internal interface EncryptedBackupActions {
    suspend fun prepare(source: Uri, intent: EncryptedBackupIntent, password: CharArray): EncryptedBackupPrepared
    suspend fun schedule(prepared: EncryptedBackupPrepared): Boolean
    suspend fun export(prepared: EncryptedBackupPrepared, destination: Uri)
}

internal class SafEncryptedBackupActions(
    private val context: Context,
    private val scratch: EncryptedBackupScratch,
    private val restore: BackupRestoreCoordinator,
    private val security: suspend () -> SecurityPorts,
) : EncryptedBackupActions {
    override suspend fun prepare(source: Uri, intent: EncryptedBackupIntent, password: CharArray): EncryptedBackupPrepared {
        var owned: EncryptedBackupPrepared? = null
        try {
            return prepareOnIo(source, intent, password) { owned = it }
        } catch (error: Exception) {
            owned?.close()
            throw error
        }
    }

    private suspend fun prepareOnIo(
        source: Uri, intent: EncryptedBackupIntent, password: CharArray, takeOwnership: (EncryptedBackupPrepared) -> Unit,
    ): EncryptedBackupPrepared = withContext(Dispatchers.IO) {
            val ports = security()
            val trace = EncryptedBackupTrace()
            val check = { coroutineContext.ensureActive() }
            val operation = scratch.open()
            val input = operation.resolve("picked")
            try {
                trace.log("copy", "started")
                copyBackupSource(context, source, input, check, maxBytes = DsbkCrypto.DEFAULT_MAX_CONTAINER_BYTES)
                trace.log("copy", "succeeded")
                val work = BackupFileOperation(operation, ports.dsbk, trace, check)
                work.prepare(input, intent, password).also { takeOwnership(it); check() }
            } catch (error: Exception) {
                runCatching { removeOwnedTree(operation.toPath()) }
                trace.log("prepare", if (error is CancellationException) "cancelled" else "failed", failureCode(error))
                throw error
            }
        }

    override suspend fun schedule(prepared: EncryptedBackupPrepared): Boolean {
        val plan = checkNotNull(prepared.plan)
        val proof = checkNotNull(prepared.proof)
        return restore.scheduleAuthenticated(proof, plan, prepared.trace.operationId).also {
            prepared.trace.log("schedule", if (it) "succeeded" else "rejected", restore.status.value.name)
        }
    }

    override suspend fun export(prepared: EncryptedBackupPrepared, destination: Uri) = withContext(Dispatchers.IO) {
        check(prepared.plan == null && destination.scheme == "content")
        try {
            prepared.trace.log("write", "started")
            writeNewDocument(prepared.artifact, destination) { coroutineContext.ensureActive() }
            prepared.trace.log("write", "succeeded")
        } catch (error: Exception) {
            prepared.trace.log("write", if (error is CancellationException) "cancelled" else "failed", failureCode(error))
            throw error
        }
    }

    private fun writeNewDocument(file: File, dest: Uri, checkCancelled: () -> Unit) {
        try {
            val length = Files.size(file.toPath())
            val descriptor = context.contentResolver.openFileDescriptor(dest, "w") ?: throw IOException("output unavailable")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                val copied = file.inputStream().use { copyBounded(it, output, length, checkCancelled) }
                if (copied != length) throw IOException("short backup write")
                output.flush()
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, dest) }
            throw error
        }
    }

    private fun failureCode(error: Exception): String = when (error) {
        is EncryptedBackupFileException -> error.error.name
        is PortableFileException -> error.error.name
        is CancellationException -> "CANCELLED"
        else -> "IO_FAILED"
    }
}

/** One operation owns its private directory, limits, and authentication-to-preview transition. */
internal class BackupFileOperation(
    private val directory: File,
    private val crypto: DsbkCrypto,
    private val trace: EncryptedBackupTrace,
    private val check: () -> Unit,
) {
    fun prepare(input: File, intent: EncryptedBackupIntent, password: CharArray): EncryptedBackupPrepared {
        val target = directory.resolve(intent.outputName)
        val capacity = minOf(DsbkCrypto.DEFAULT_MAX_CONTAINER_BYTES, directory.usableSpace - 16L * 1024 * 1024)
        if (capacity <= 0) throw IOException("backup staging capacity")
        DsbkStagingArea(directory.resolve("tmp"), capacity).use { staging ->
            val plaintext = minOf(capacity, DsbkCrypto.DEFAULT_MAX_INPUT_BYTES)
            val engine = EncryptedBackupFiles(directory, staging, crypto, plaintext, capacity)
            val portable = PortableBackupFiles(directory, staging, crypto, plaintext, plaintext, capacity, ::recordPortable)
            return when (intent) {
                EncryptedBackupIntent.IMPORT -> unlock(engine, portable, input, target, password)
                EncryptedBackupIntent.EXPORT -> seal(input, target) {
                    engine.encryptZip(engine.validateExportZip(input, ::cancelled), target, password, ::cancelled)
                }
                EncryptedBackupIntent.EXPORT_PORTABLE -> seal(input, target) {
                    portable.exportPortableZip(input, target, password, contentVerifier, ::cancelled)
                }
            }
        }
    }

    private fun recordPortable(event: PortableBackupEvent) {
        trace.log("portable_${event.operation}_${event.phase}", event.status, event.error?.name ?: "none")
    }

    private fun unlock(
        files: EncryptedBackupFiles, portable: PortableBackupFiles, input: File, target: File, password: CharArray,
    ): EncryptedBackupPrepared {
        trace.log("authenticate", "started")
        val magic = input.inputStream().buffered().use { ByteArray(4) { _ -> it.read().toByte() } }
        val proof = if (crypto.isEncryptedBackup(magic)) {
            files.decryptZip(input, target, password, ::cancelled)
        } else {
            portable.importPortableZip(input, target, password, contentVerifier, ::cancelled)
            files.validateAuthenticatedZip(target, ::cancelled)
        }
        val plan = CheckedZipArchive(target).use { ArchivePreflight.inspectAuthenticated(it, directory.usableSpace, check).plan }
        if (!plan.canStage) throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_INVALID)
        Files.deleteIfExists(input.toPath())
        trace.log("authenticate", "verified")
        return EncryptedBackupPrepared(directory, target, trace, proof, plan)
    }

    private fun seal(input: File, target: File, write: () -> Unit): EncryptedBackupPrepared {
        trace.log("seal", "started")
        write()
        Files.deleteIfExists(input.toPath())
        trace.log("seal", "verified")
        return EncryptedBackupPrepared(directory, target, trace)
    }

    private val contentVerifier = PortableArchiveVerifier { archive, cancel ->
        val plan = ArchivePreflight.inspectAuthenticated(archive, directory.usableSpace, cancel).plan
        if (!plan.canStage) throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_INVALID)
    }

    private fun cancelled(): Boolean {
        return try { check(); false } catch (_: CancellationException) { true }
    }
}

internal class EncryptedBackupTrace {
    val operationId: UUID = UUID.randomUUID()
    private val started = SystemClock.elapsedRealtime()
    fun log(phase: String, state: String, code: String = "none") {
        Log.i("DstuBackupCrypto", "event=backup.encrypted owner=app.backup correlationId=$operationId phase=$phase " +
            "state=$state elapsedMs=${SystemClock.elapsedRealtime() - started} code=$code")
    }
}
