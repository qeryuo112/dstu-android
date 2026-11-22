package com.deepstudent.app.backup

import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkError
import com.deepstudent.platform.security.DsbkStagingArea
import com.deepstudent.services.backup.api.ArchiveEntryKind
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CancellationException

/** Caller must run full authenticated-source preflight and throw unless every required check passes. */
internal fun interface PortableArchiveVerifier {
    fun verify(archive: CheckedZipArchive, checkCancelled: () -> Unit)
}

internal enum class PortableFileError(val fixedMessage: String) {
    CANCELLED("portable backup operation cancelled"),
    FILE_INVALID("portable backup private file is invalid"),
    ZIP_INVALID("portable backup ZIP is invalid"),
    OUTER_MANIFEST_INVALID("portable backup outer manifest is invalid"),
    ORIGINAL_MANIFEST_INVALID("portable backup original manifest is invalid"),
    PAYLOAD_MISMATCH("portable backup payload does not match the outer manifest"),
    ENTRY_CONFLICT("portable backup contains conflicting entries"),
    LAYER_VIOLATION("portable backup contains an entry in the wrong layer"),
    LIMIT_EXCEEDED("portable backup exceeds its resource budget"),
    AUTHENTICATION_FAILED("portable backup authentication failed"),
    V1_CONVERSION_REQUIRED("legacy portable payload requires desktop conversion"),
    CRYPTO_REJECTED("portable backup cryptography rejected input"),
    VERIFICATION_FAILED("portable backup authenticated-source validation failed"),
    SOURCE_CHANGED("portable backup file changed during processing"),
    IO_FAILED("portable backup file operation failed"),
}

internal class PortableFileException(val error: PortableFileError, val cryptoError: DsbkError? = null) : Exception(error.fixedMessage)

internal data class PortableBackupEvent(
    val taskId: String, val operation: String, val phase: String, val status: String,
    val elapsedMillis: Long, val error: PortableFileError? = null,
)

/**
 * Private-file transport only. No DataSpace, KeyStore or code execution is touched.
 * Password arrays remain caller-owned. Outer ZIP data is NOT password-encrypted.
 * Supplying a verifier is mandatory: authentication alone never authorizes a restore.
 */
internal class PortableBackupFiles(
    private val privateDirectory: File,
    staging: DsbkStagingArea,
    crypto: DsbkCrypto,
    private val maxArchiveBytes: Long,
    private val maxInnerBytes: Long,
    private val maxContainerBytes: Long,
    private val record: (PortableBackupEvent) -> Unit = {},
) {
    private val crypt = PortableCryptoFiles(crypto, staging, maxInnerBytes, maxContainerBytes)

    /** Source and target are distinct direct children of privateDirectory. Old target survives failure. */
    fun importPortableZip(
        source: File, target: File, password: CharArray, verifier: PortableArchiveVerifier,
        cancelled: () -> Boolean = { false },
    ): File = PortableOperation("import", cancelled, record).run { check, cleanupFailed ->
        val input = privateFile(source, mustExist = true)
        val output = privateFile(target, mustExist = false)
        requireDifferent(input, output)
        val before = digestFile(input, maxArchiveBytes, check)
        PortableScratch.create(privateDirectory, cleanupFailed).use { scratch ->
            val candidate = scratch.file("reconstructed.zip")
            CheckedZipArchive(input).use { outer ->
                val entries = portableCatalog(input, outer, maxArchiveBytes)
                val inner = unsealLayer(entries, scratch, password, check)
                mergeLayers(entries, inner, candidate, check)
            }
            verifyCandidate(candidate, verifier, check)
            if (digestFile(input, maxArchiveBytes, check) != before) throw PortableFileException(PortableFileError.SOURCE_CHANGED)
            publish(candidate, output, check)
        }
        output
    }

    /** The verifier checks source content for packaging only; it does not authorize activation. */
    fun exportPortableZip(
        source: File, target: File, password: CharArray, verifier: PortableArchiveVerifier,
        cancelled: () -> Boolean = { false },
    ): File = PortableOperation("export", cancelled, record).run { check, cleanupFailed ->
        val input = privateFile(source, mustExist = true)
        val output = privateFile(target, mustExist = false)
        requireDifferent(input, output)
        val before = digestFile(input, maxArchiveBytes, check)
        PortableScratch.create(privateDirectory, cleanupFailed).use { scratch ->
            val candidate = CheckedZipArchive(input).use { sourceZip ->
                verifyArchive(sourceZip, verifier, check)
                makePortable(sourceZip, input, scratch, password, check)
            }
            if (digestFile(input, maxArchiveBytes, check) != before) throw PortableFileException(PortableFileError.SOURCE_CHANGED)
            publish(candidate, output, check)
        }
        output
    }

    private fun unsealLayer(
        entries: List<PortableEntry>, scratch: PortableScratch, password: CharArray, check: () -> Unit,
    ): File {
        val declaration = manifest(entries, check).payloadDeclaration()
        if (declaration.size > maxContainerBytes || privateDirectory.usableSpace < declaration.size) {
            throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
        }
        validateOuter(entries)
        val payload = findFile(entries, PORTABLE_PAYLOAD)
        val encrypted = scratch.file("payload.dsbk")
        val observed = encrypted.outputStream().buffered().use { out ->
            payload.archive.open(payload.name).use { copyPortable(it, out, declaration.size, check) }
        }
        if (observed != declaration) throw PortableFileException(PortableFileError.PAYLOAD_MISMATCH)
        val inner = scratch.file("inner.zip")
        crypt.decrypt(encrypted, inner, password, check)
        return inner
    }

    private fun mergeLayers(entries: List<PortableEntry>, innerFile: File, candidate: File, check: () -> Unit) {
        CheckedZipArchive(innerFile).use { inner ->
            val innerEntries = portableCatalog(innerFile, inner, maxArchiveBytes)
            val original = manifest(innerEntries, check)
            original.requireOriginalPolicy()
            validateInner(innerEntries)
            val outerFiles = entries.filterNot { it.name in ignoredOuter }
            val innerFiles = innerEntries.filterNot { it.name == PORTABLE_MANIFEST }
            val combined = outerFiles + innerFiles
            verifyNoCollisions(combined)
            writePortableZip(candidate, combined, original.bytes, maxArchiveBytes, check)
        }
    }

    private fun makePortable(
        sourceZip: CheckedZipArchive, input: File, scratch: PortableScratch, password: CharArray, check: () -> Unit,
    ): File {
        val entries = portableCatalog(input, sourceZip, maxArchiveBytes, desktopRatios = false)
        val original = manifest(entries, check)
        original.requireOriginalPolicy()
        if (entries.any { it.name == PORTABLE_PAYLOAD }) throw PortableFileException(PortableFileError.ENTRY_CONFLICT)
        val inner = scratch.file("inner.zip")
        val secretFiles = entries.filter { it.item.kind == ArchiveEntryKind.FILE && isPortableExcluded(it.name) }
        writePortableZip(inner, secretFiles, original.bytes, maxInnerBytes, check)
        val encrypted = scratch.file("payload.dsbk")
        crypt.encrypt(inner, encrypted, password, check)
        val payload = digestFile(encrypted, maxContainerBytes, check)
        val outerEntries = entries.filterNot { it.name in ignoredOuter || isPortableExcluded(it.name) }
        val candidate = scratch.file("candidate.zip")
        writePortableOuter(candidate, outerEntries, original.desktopOuter(payload), encrypted, payload, maxArchiveBytes, check)
        if (candidate.length() > maxArchiveBytes) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
        CheckedZipArchive(candidate).use { portableCatalog(candidate, it, maxArchiveBytes).also(::validateOuter) }
        return candidate
    }

    private val ignoredOuter = setOf(PORTABLE_MANIFEST, PORTABLE_PAYLOAD, PORTABLE_CHECKSUMS)

    /** Invoke at app startup: only scratch directories not locked by a live operation are removed. */
    fun cleanupAbandoned(): Int {
        validateDirectory()
        return PortableScratch.cleanupAbandoned(privateDirectory)
    }

    private fun verifyCandidate(file: File, verifier: PortableArchiveVerifier, check: () -> Unit) {
        val before = digestFile(file, maxArchiveBytes, check)
        CheckedZipArchive(file).use { archive ->
            portableCatalog(file, archive, maxArchiveBytes)
            verifyArchive(archive, verifier, check)
        }
        if (digestFile(file, maxArchiveBytes, check) != before) throw PortableFileException(PortableFileError.SOURCE_CHANGED)
    }

    private fun verifyArchive(archive: CheckedZipArchive, verifier: PortableArchiveVerifier, check: () -> Unit) {
        try { verifier.verify(archive, check); check() }
        catch (error: CancellationException) { throw error }
        catch (error: InterruptedException) { throw error }
        catch (_: Exception) { throw PortableFileException(PortableFileError.VERIFICATION_FAILED) }
    }

    private fun manifest(entries: List<PortableEntry>, check: () -> Unit): PortableManifest =
        PortableManifest.parse(readPortableEntry(findFile(entries, PORTABLE_MANIFEST), PORTABLE_MAX_MANIFEST, check), check)

    private fun findFile(entries: List<PortableEntry>, name: String): PortableEntry = entries.singleOrNull {
        it.name == name && it.item.kind == ArchiveEntryKind.FILE
    } ?: throw PortableFileException(PortableFileError.ZIP_INVALID)

    private fun validateOuter(entries: List<PortableEntry>) {
        if (entries.any { isPortableExcluded(it.name) }) throw PortableFileException(PortableFileError.LAYER_VIOLATION)
    }

    private fun validateInner(entries: List<PortableEntry>) {
        val files = entries.filter { it.item.kind == ArchiveEntryKind.FILE }.map { it.name }
        for (entry in entries) {
            if (entry.name == PORTABLE_MANIFEST && entry.item.kind == ArchiveEntryKind.FILE) continue
            if (!isPortableExcluded(entry.name) || entry.item.kind == ArchiveEntryKind.DIRECTORY && files.none { it.startsWith("${entry.name}/") }) {
                throw PortableFileException(PortableFileError.LAYER_VIOLATION)
            }
        }
    }

    private fun validateDirectory() {
        if (maxArchiveBytes <= 0 || maxInnerBytes <= 0 || maxContainerBytes <= 0 || Files.isSymbolicLink(privateDirectory.toPath()) ||
            !Files.isDirectory(privateDirectory.toPath(), NOFOLLOW_LINKS)
        ) throw PortableFileException(PortableFileError.FILE_INVALID)
    }

    private fun privateFile(file: File, mustExist: Boolean): File {
        validateDirectory()
        val result = file.absoluteFile
        if (result.parentFile?.canonicalFile != privateDirectory.canonicalFile || Files.isSymbolicLink(result.toPath()) ||
            result.name == ".portable.guard" || result.name.startsWith(".portable-work-") ||
            result.isDirectory || mustExist && !Files.isRegularFile(result.toPath(), NOFOLLOW_LINKS)
        ) throw PortableFileException(PortableFileError.FILE_INVALID)
        return result
    }

    private fun requireDifferent(source: File, target: File) {
        if (source.canonicalFile == target.canonicalFile || target.exists() && Files.isSameFile(source.toPath(), target.toPath())) {
            throw PortableFileException(PortableFileError.FILE_INVALID)
        }
    }

    private fun publish(candidate: File, target: File, check: () -> Unit) {
        check()
        FileChannel.open(candidate.toPath(), WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        check()
        Files.move(candidate.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

}
