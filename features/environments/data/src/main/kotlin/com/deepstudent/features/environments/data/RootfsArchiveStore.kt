package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest
import com.deepstudent.features.environments.backend.RootfsArchiveStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal class FileRootfsArchiveStore(
    private val paths: EnvironmentPaths,
) : RootfsArchiveStore {
    override suspend fun ensureVerified(
        manifest: RootfsManifest,
        source: RootfsArchiveSource?,
        onProgress: (EnvironmentProgress) -> Unit,
    ): String {
        val archive = paths.archive(manifest)
        if (archive.isFile && verify(archive, manifest)) {
            onProgress(
                EnvironmentProgress(
                    stage = EnvironmentStage.ARCHIVE_VERIFY,
                    processedBytes = manifest.archiveBytes,
                    totalBytes = manifest.archiveBytes,
                ),
            )
            return manifest.sha256
        }
        if (archive.exists()) {
            Files.deleteIfExists(archive.toPath())
        }
        if (source == null) {
            throw EnvironmentCommandException(
                EnvironmentFailure(
                    EnvironmentFailureCode.ARCHIVE_MISSING,
                    "verified rootfs archive is unavailable",
                    recoverable = true,
                ),
            )
        }
        copyVerified(manifest, source, archive, onProgress)
        return manifest.sha256
    }

    private suspend fun copyVerified(
        manifest: RootfsManifest,
        source: RootfsArchiveSource,
        destination: File,
        onProgress: (EnvironmentProgress) -> Unit,
    ) {
        val partial = paths.archivePart(manifest)
        Files.deleteIfExists(partial.toPath())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.open().use { input ->
                Files.newOutputStream(
                    partial.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    copyPayload(input, output, manifest, digest, onProgress)
                    output.flush()
                }
            }
            FileChannel.open(partial.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            coroutineContext.ensureActive()
            commitVerified(partial, destination, manifest, digest.digest())
            EnvironmentDataLog.event(
                "archive-verified",
                "manifest=${manifest.id} bytes=${manifest.archiveBytes} sha=${manifest.sha256.take(12)}",
            )
        } finally {
            Files.deleteIfExists(partial.toPath())
        }
    }

    private suspend fun copyPayload(
        input: InputStream,
        output: OutputStream,
        manifest: RootfsManifest,
        digest: MessageDigest,
        onProgress: (EnvironmentProgress) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var processed = 0L
        var reported = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            processed += count
            if (processed > manifest.archiveBytes) {
                throw EnvironmentCommandException(
                    EnvironmentFailure(
                        EnvironmentFailureCode.INVALID_ARCHIVE,
                        "rootfs archive exceeds the manifest size",
                        recoverable = true,
                    ),
                )
            }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            if (processed - reported >= REPORT_BYTES) {
                onProgress(
                    EnvironmentProgress(
                        EnvironmentStage.ARCHIVE_COPY,
                        processedBytes = processed,
                        totalBytes = manifest.archiveBytes,
                    ),
                )
                reported = processed
            }
        }
        onProgress(
            EnvironmentProgress(
                EnvironmentStage.ARCHIVE_COPY,
                processedBytes = processed,
                totalBytes = manifest.archiveBytes,
            ),
        )
        return processed
    }

    private fun commitVerified(
        partial: File,
        destination: File,
        manifest: RootfsManifest,
        digest: ByteArray,
    ) {
        val hash = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (partial.length() != manifest.archiveBytes || hash != manifest.sha256) {
            throw EnvironmentCommandException(
                EnvironmentFailure(
                    EnvironmentFailureCode.HASH_MISMATCH,
                    "rootfs archive hash does not match the manifest",
                    recoverable = true,
                ),
            )
        }
        Files.move(
            partial.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private suspend fun verify(file: File, manifest: RootfsManifest): Boolean {
        if (file.length() != manifest.archiveBytes) return false
        return sha256(file) == manifest.sha256
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_BYTES = 4L * 1024L * 1024L
    }
}
