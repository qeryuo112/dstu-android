package com.deepstudent.app.backup

import com.deepstudent.services.backup.api.RestoreAdmission
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

internal data class BackupRestoreRequest(
    val id: String, val manifestHash: String, val admission: RestoreAdmission = RestoreAdmission.STANDARD,
    val continuity: Boolean = false,
)

/** App-private command journal: the durable request is published only after the input copy. */
internal class PendingBackupRestore(private val root: File) {
    val input = root.resolve("confirmed.zip")
    val scratch = root.resolve("staging.zip.tmp")
    private val pending = root.resolve("pending")
    private val receipt = root.resolve("receipt")

    fun prepare() {
        Files.createDirectories(root.toPath())
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        sync(checkNotNull(root.parentFile))
        check(!Files.exists(pending.toPath(), NOFOLLOW_LINKS))
        Files.deleteIfExists(scratch.toPath())
    }

    fun request(): BackupRestoreRequest? = read(pending)?.let { text ->
        val parts = text.split('\n')
        check(parts.size == 4 && parts.last().isEmpty())
        check(UUID.fromString(parts[1]).toString() == parts[1] && parts[2].matches(Regex("[0-9a-f]{64}")))
        val version = parts.first()
        val admission = when (version) {
            "DSTU-RESTORE-1" -> RestoreAdmission.STANDARD
            "DSTU-VERIFIED-RESTORE-1" -> RestoreAdmission.AUTHENTICATED_PACKAGE
            CONTINUITY_VERSION -> RestoreAdmission.STANDARD
            else -> error("Unknown restore journal")
        }
        BackupRestoreRequest(parts[1], parts[2], admission, continuity = version == CONTINUITY_VERSION)
    }

    fun publish(request: BackupRestoreRequest) {
        move(scratch, input)
        sync(root)
        val version = when (request.admission) {
            RestoreAdmission.STANDARD -> "DSTU-RESTORE-1"
            RestoreAdmission.AUTHENTICATED_PACKAGE -> "DSTU-VERIFIED-RESTORE-1"
        }
        check(!request.continuity || request.admission == RestoreAdmission.STANDARD)
        val marker = if (request.continuity) CONTINUITY_VERSION else version
        write(pending, "$marker\n${request.id}\n${request.manifestHash}\n")
    }

    /** A crash between staging and publishing a command must not leave a plaintext orphan. */
    fun removeUnqueuedInput() {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && Files.notExists(pending.toPath(), NOFOLLOW_LINKS))
        val removedScratch = Files.deleteIfExists(scratch.toPath())
        val removedInput = Files.deleteIfExists(input.toPath())
        if (removedInput || removedScratch) sync(root)
    }

    fun record(code: String) {
        require(code.matches(Regex("[A-Z_]+")))
        write(receipt, code)
        Files.deleteIfExists(pending.toPath())
        sync(root)
        Files.deleteIfExists(input.toPath())
        sync(root)
    }

    fun lastReceipt(): String? = read(receipt)?.also { check(it.matches(Regex("[A-Z_]+"))) }

    private fun read(file: File): String? {
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..512)
        return file.readText(Charsets.US_ASCII)
    }

    private fun write(file: File, value: String) {
        val temp = Files.createTempFile(root.toPath(), "journal-", ".tmp").toFile()
        try {
            FileOutputStream(temp).use { it.write(value.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            move(temp, file)
            sync(root)
        } finally { Files.deleteIfExists(temp.toPath()) }
    }

    private fun sync(dir: File) {
        FileChannel.open(dir.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private companion object { const val CONTINUITY_VERSION = "DSTU-CONTINUITY-RESTORE-1" }
}
