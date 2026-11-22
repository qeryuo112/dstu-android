package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentStage
import com.deepstudent.features.environments.api.RootfsManifest
import com.deepstudent.features.environments.backend.RootfsExtractor
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import kotlin.coroutines.coroutineContext

internal class TarXzRootfsExtractor(
    private val paths: EnvironmentPaths,
) : RootfsExtractor {
    override suspend fun extract(
        manifest: RootfsManifest,
        archiveToken: String,
        generationId: String,
        onProgress: (EnvironmentProgress) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val archive = requireArchive(manifest, archiveToken)
        val generationDirectory = paths.stagingGeneration(generationId)
        SafeFileTree.deleteRecursivelyNoFollow(generationDirectory)
        val root = paths.stagingRoot(generationId)
        Files.createDirectories(root.toPath())
        val policy = RootfsExtractionPolicy(manifest)
        try {
            val processedBytes = readEntries(archive, root, manifest, policy, onProgress)
            policy.finish()
            RootfsMarker.write(root, manifest, generationId)
            reportFinal(manifest, policy, processedBytes, onProgress)
            generationId
        } catch (failure: Exception) {
            SafeFileTree.deleteRecursivelyNoFollow(generationDirectory)
            throw failure
        }
    }

    private fun requireArchive(manifest: RootfsManifest, archiveToken: String): File {
        require(archiveToken == manifest.sha256) { "archive token does not match manifest" }
        val archive = paths.archive(manifest)
        if (archive.isFile) return archive
        throw EnvironmentCommandException(
            EnvironmentFailure(
                EnvironmentFailureCode.ARCHIVE_MISSING,
                "verified rootfs archive is unavailable",
                recoverable = true,
            ),
        )
    }

    private suspend fun readEntries(
        archive: File,
        root: File,
        manifest: RootfsManifest,
        policy: RootfsExtractionPolicy,
        onProgress: (EnvironmentProgress) -> Unit,
    ): Long {
        val reporter = ExtractionProgressReporter(manifest, onProgress)
        XZInputStream(BufferedInputStream(archive.inputStream(), BUFFER_BYTES), 64 * 1024).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    val action = policy.accept(entry.toDescriptor())
                    reporter.add(applyEntry(root, action, tar), policy.entriesSeen)
                }
            }
        }
        return reporter.processedBytes
    }

    private fun reportFinal(
        manifest: RootfsManifest,
        policy: RootfsExtractionPolicy,
        processedBytes: Long,
        onProgress: (EnvironmentProgress) -> Unit,
    ) {
        onProgress(
            EnvironmentProgress(
                stage = EnvironmentStage.EXTRACT,
                completedEntries = policy.entriesSeen,
                totalEntries = manifest.entryCount,
                processedBytes = processedBytes,
                totalBytes = manifest.extractionBytesBudget,
            ),
        )
    }

    private fun TarArchiveEntry.toDescriptor(): RootfsEntryDescriptor {
        val type = when {
            isDirectory -> RootfsEntryType.DIRECTORY
            isSymbolicLink -> RootfsEntryType.SYMLINK
            isLink -> RootfsEntryType.HARDLINK
            isFile -> RootfsEntryType.REGULAR
            else -> RootfsEntryType.SPECIAL
        }
        return RootfsEntryDescriptor(
            name = name,
            type = type,
            size = size,
            linkName = linkName,
            executable = (mode and 0b001001001) != 0,
        )
    }

    private fun applyEntry(root: File, action: RootfsEntryAction, tar: TarArchiveInputStream): Long =
        when (action) {
            is RootfsEntryAction.Directory -> {
                SafeFileTree.ensureDirectory(resolve(root, action.relative))
                0L
            }
            is RootfsEntryAction.Regular -> writeRegular(root, action, tar)
            is RootfsEntryAction.Symlink -> {
                val link = resolve(root, action.relative)
                requireParentDirectory(link)
                require(!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) { "duplicate symlink" }
                Files.createSymbolicLink(link, File(action.target).toPath())
                0L
            }
            is RootfsEntryAction.Hardlink -> {
                val link = resolve(root, action.relative)
                val target = resolve(root, action.target)
                requireParentDirectory(link)
                require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    "hardlink target is unavailable"
                }
                Files.createLink(link, target)
                0L
            }
            RootfsEntryAction.Skip -> 0L
        }

    private fun writeRegular(
        root: File,
        action: RootfsEntryAction.Regular,
        tar: TarArchiveInputStream,
    ): Long {
        val target = resolve(root, action.relative)
        requireParentDirectory(target)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "duplicate regular member" }
        var remaining = action.size
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (remaining > 0) {
                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) error("truncated rootfs member")
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        target.toFile().setExecutable(action.executable, false)
        return action.size
    }

    private fun requireParentDirectory(path: Path) {
        val parent = path.parent ?: error("rootfs path has no parent")
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "rootfs parent directory is missing or linked"
        }
    }

    private fun resolve(root: File, relative: List<String>): Path {
        var path = root.toPath()
        relative.forEach { component ->
            require(component.isNotEmpty() && component != "." && component != "..")
            path = path.resolve(component)
        }
        return path
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_BYTES = 4L * 1024L * 1024L
    }
}

private class ExtractionProgressReporter(
    private val manifest: RootfsManifest,
    private val onProgress: (EnvironmentProgress) -> Unit,
) {
    var processedBytes = 0L
        private set
    private var lastReportedBytes = -REPORT_BYTES

    fun add(bytes: Long, entriesSeen: Int) {
        processedBytes += bytes
        if (processedBytes - lastReportedBytes < REPORT_BYTES &&
            entriesSeen != manifest.entryCount
        ) return
        report(entriesSeen)
        lastReportedBytes = processedBytes
    }

    private fun report(entriesSeen: Int) {
        onProgress(
            EnvironmentProgress(
                stage = EnvironmentStage.EXTRACT,
                completedEntries = entriesSeen,
                totalEntries = manifest.entryCount,
                processedBytes = processedBytes,
                totalBytes = manifest.extractionBytesBudget,
            ),
        )
    }

    private companion object {
        const val REPORT_BYTES = 4L * 1024L * 1024L
    }
}
