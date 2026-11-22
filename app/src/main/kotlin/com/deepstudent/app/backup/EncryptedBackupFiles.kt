package com.deepstudent.app.backup

import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkError
import com.deepstudent.platform.security.DsbkException
import com.deepstudent.platform.security.DsbkPreflight
import com.deepstudent.platform.security.DsbkSink
import com.deepstudent.platform.security.DsbkStagingArea
import com.deepstudent.services.backup.backend.ArchivePreflight
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Fixed, payload-free errors. No underlying exception or path is included in diagnostics. */
internal enum class EncryptedBackupFileError(val fixedMessage: String) {
    CANCELLED("encrypted backup operation cancelled"),
    FILE_INVALID("encrypted backup private file is invalid"),
    ZIP_INVALID("backup ZIP cannot be staged"),
    ZIP_CHANGED("backup ZIP changed after validation"),
    V1_CONVERSION_REQUIRED("legacy encrypted backup requires desktop conversion"),
    AUTHENTICATION_FAILED("encrypted backup authentication failed"),
    CRYPTO_REJECTED("encrypted backup cryptography rejected input"),
    IO_FAILED("encrypted backup file operation failed"),
}

internal class EncryptedBackupFileException(
    val error: EncryptedBackupFileError,
    val cryptoError: DsbkError? = null,
) : Exception(error.fixedMessage)

/** A proof is owned by one adapter and must be rechecked against the bytes actually encrypted. */
internal class ValidatedBackupZip internal constructor(
    internal val owner: Any,
    internal val file: File,
    internal val size: Long,
    internal val sha256: ByteArray,
    internal val authenticated: Boolean = false,
)

private enum class ZipCheck { STANDARD, EXPORT, AUTHENTICATED }

/**
 * Isolated private-file adapter. It never touches the active DataSpace or stores/logs a password.
 * The caller owns and clears the supplied CharArray. This component does not make snapshots;
 * it consumes an already complete ZIP, preflights it, and verifies its bytes again while encrypting.
 * All paths must be direct children of [privateDirectory], not external SAF paths.
 */
internal class EncryptedBackupFiles(
    private val privateDirectory: File,
    private val staging: DsbkStagingArea,
    private val crypto: DsbkCrypto,
    private val maxPlaintextBytes: Long,
    private val maxContainerBytes: Long,
) {
    private val owner = Any()

    fun preflight(source: File, cancelled: () -> Boolean = { false }): DsbkPreflight = operation(cancelled) { token ->
        val file = privateFile(source)
        Files.newByteChannel(file.toPath(), setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
            crypto.preflight(token.wrap(java.nio.channels.Channels.newInputStream(channel)), channel.size(),
                maxPlaintextBytes, maxContainerBytes)
        }
    }

    fun validateZip(source: File, cancelled: () -> Boolean = { false }): ValidatedBackupZip = operation(cancelled) { token ->
        verifyZip(privateFile(source), token)
    }

    // Only call after password authentication and strictly checked portable reconstruction.
    fun validateAuthenticatedZip(source: File, cancelled: () -> Boolean = { false }): ValidatedBackupZip = operation(cancelled) { token ->
        verifyZip(privateFile(source), token, ZipCheck.AUTHENTICATED)
    }

    // Structural validation for export never creates a restore-admission token.
    fun validateExportZip(source: File, cancelled: () -> Boolean = { false }): ValidatedBackupZip = operation(cancelled) { token ->
        verifyZip(privateFile(source), token, ZipCheck.EXPORT)
    }

    /**
     * Authentication writes only to an unpublished private file. The final name is atomically
     * replaced only after authenticated ZIP validation; wrong passwords preserve any old target.
     */
    fun decryptZip(
        source: File,
        target: File,
        password: CharArray,
        cancelled: () -> Boolean = { false },
    ): ValidatedBackupZip = operation(cancelled) { token ->
        val inputFile = privateFile(source)
        val outputFile = privateFile(target)
        requireDifferent(inputFile, outputFile)
        val temporary = Files.createTempFile(privateDirectory.toPath(), ".authenticated-", ".zip").toFile()
        try {
            // Use one open file handle for both header and ciphertext; a pathname replacement
            // between the two must not cause the preflight to inspect a different file.
            Files.newByteChannel(inputFile.toPath(), setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
                val length = channel.size()
                val header = token.wrap(java.nio.channels.Channels.newInputStream(channel))
                crypto.preflight(header, length, maxPlaintextBytes, maxContainerBytes)
                channel.position(0)
                val sink = staging.createSink(temporary, maxPlaintextBytes)
                try {
                    crypto.decrypt(token.wrap(java.nio.channels.Channels.newInputStream(channel)), password, sink,
                        maxPlaintextBytes, maxContainerBytes)
                } finally {
                    sink.abort()
                }
                if (channel.size() != length) throw EncryptedBackupFileException(EncryptedBackupFileError.FILE_INVALID)
            }
            val proof = verifyZip(temporary, token, ZipCheck.AUTHENTICATED)
            token.check()
            Files.move(temporary.toPath(), outputFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            ValidatedBackupZip(owner, outputFile, proof.size, proof.sha256, authenticated = true)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    /** Encrypt a previously validated ZIP with v2; publication happens after input hash verification. */
    fun encryptZip(
        proof: ValidatedBackupZip,
        target: File,
        password: CharArray,
        cancelled: () -> Boolean = { false },
    ) = operation(cancelled) { token ->
        if (proof.owner !== owner) throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_CHANGED)
        val inputFile = privateFile(proof.file)
        val outputFile = privateFile(target)
        requireDifferent(inputFile, outputFile)
        val sink = staging.createSink(outputFile, maxContainerBytes)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = HashingInput(openInput(inputFile, token), digest)
            input.use {
                crypto.encryptV2(it, SinkOutput(sink, token), password, maxPlaintextBytes)
            }
            token.check()
            if (input.count != proof.size || !MessageDigest.isEqual(digest.digest(), proof.sha256)) {
                throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_CHANGED)
            }
            sink.commit()
        } finally {
            sink.abort()
        }
    }

    private fun verifyZip(file: File, token: CancelToken, check: ZipCheck = ZipCheck.STANDARD): ValidatedBackupZip {
        val before = digest(file, token)
        if (before.first > maxPlaintextBytes) throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_INVALID)
        val plan = try {
            CheckedZipArchive(file).use { archive ->
                if (check == ZipCheck.STANDARD) ArchivePreflight.inspect(archive, Long.MAX_VALUE) { token.check() }.plan
                else ArchivePreflight.inspectAuthenticated(archive, Long.MAX_VALUE) { token.check() }.plan
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_INVALID)
        }
        if (!plan.canStage) throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_INVALID)
        val after = digest(file, token)
        if (before.first != after.first || !MessageDigest.isEqual(before.second, after.second)) {
            throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_CHANGED)
        }
        return ValidatedBackupZip(owner, file, after.first, after.second, check == ZipCheck.AUTHENTICATED)
    }

    private fun digest(file: File, token: CancelToken): Pair<Long, ByteArray> {
        val hash = MessageDigest.getInstance("SHA-256")
        val stream = HashingInput(openInput(file, token), hash)
        stream.use { input ->
            val buffer = ByteArray(32 * 1024)
            while (input.read(buffer) != -1) {
                if (input.count > maxPlaintextBytes) throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_INVALID)
            }
        }
        return stream.count to hash.digest()
    }

    private fun privateFile(file: File): File {
        if (maxPlaintextBytes <= 0 || maxContainerBytes <= 0 || Files.isSymbolicLink(privateDirectory.toPath()) ||
            !privateDirectory.isDirectory || Files.isSymbolicLink(file.toPath()) || file.isDirectory ||
            file.absoluteFile.parentFile?.canonicalFile != privateDirectory.canonicalFile
        ) throw EncryptedBackupFileException(EncryptedBackupFileError.FILE_INVALID)
        return file.absoluteFile
    }

    private fun requireDifferent(source: File, target: File) {
        if (source.canonicalFile == target.canonicalFile ||
            target.exists() && Files.isSameFile(source.toPath(), target.toPath())
        ) throw EncryptedBackupFileException(EncryptedBackupFileError.FILE_INVALID)
    }

    private fun openInput(file: File, token: CancelToken): InputStream =
        token.wrap(Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS))

    private fun <T> operation(cancelled: () -> Boolean, block: (CancelToken) -> T): T {
        val token = CancelToken(cancelled)
        try {
            token.check()
            return block(token)
        } catch (error: EncryptedBackupFileException) {
            throw error
        } catch (error: DsbkException) {
            if (token.wasCancelled) throw EncryptedBackupFileException(EncryptedBackupFileError.CANCELLED)
            throw EncryptedBackupFileException(encryptedError(error.error), error.error)
        } catch (_: CancellationException) {
            throw EncryptedBackupFileException(EncryptedBackupFileError.CANCELLED)
        } catch (_: Exception) {
            throw EncryptedBackupFileException(if (token.wasCancelled) EncryptedBackupFileError.CANCELLED else EncryptedBackupFileError.IO_FAILED)
        }
    }

    private fun encryptedError(error: DsbkError): EncryptedBackupFileError = when (error) {
        DsbkError.V1_CONVERSION_REQUIRED -> EncryptedBackupFileError.V1_CONVERSION_REQUIRED
        DsbkError.AUTHENTICATION_FAILED -> EncryptedBackupFileError.AUTHENTICATION_FAILED
        else -> EncryptedBackupFileError.CRYPTO_REJECTED
    }
}

private class CancelToken(private val cancelled: () -> Boolean) {
    var wasCancelled = false
        private set

    fun check() {
        if (cancelled()) {
            wasCancelled = true
            throw CancellationException()
        }
    }

    fun wrap(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int { check(); return super.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check()
            return `in`.read(bytes, offset, length)
        }
    }
}

private class HashingInput(source: InputStream, private val digest: MessageDigest) : FilterInputStream(source) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) { digest.update(it.toByte()); count++ } }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
        if (it > 0) { digest.update(bytes, offset, it); count += it }
    }
}

private class SinkOutput(private val sink: DsbkSink, private val token: CancelToken) : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) { token.check(); sink.write(bytes, offset, length) }
}
