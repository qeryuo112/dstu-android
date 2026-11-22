package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class RootfsExtractionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun verifiesAndExtractsSmallRootfsToIsolatedGeneration() = kotlinx.coroutines.runBlocking {
        val bytes = archive(
            listOf(
                Entry.Directory("ubuntu-test/"),
                Entry.Directory("ubuntu-test/etc/"),
                Entry.File(
                    "ubuntu-test/etc/os-release",
                    "ID=ubuntu\nVERSION_ID=24.04\n".toByteArray(),
                ),
            ),
        )
        val manifest = manifest(bytes, entryCount = 3)
        val paths = EnvironmentPaths(File(temporary.root, "environment")).also {
            it.ensureDirectories()
        }
        val progress = AtomicReference<EnvironmentProgress>()
        val token = FileRootfsArchiveStore(paths).ensureVerified(
            manifest,
            RootfsArchiveSource { ByteArrayInputStream(bytes) },
        ) { progress.set(it) }

        assertEquals(manifest.sha256, token)
        val generation = TarXzRootfsExtractor(paths).extract(
            manifest,
            token,
            "generation-1",
        ) { progress.set(it) }
        val extracted = File(paths.stagingRoot(generation), "etc/os-release")

        assertEquals("ID=ubuntu\nVERSION_ID=24.04\n", extracted.readText())
        assertTrue(RootfsMarker.verify(paths.stagingRoot(generation), manifest, generation))
        assertFalse(File(paths.generationRoot(generation).parent, "active").exists())
        assertEquals(3, progress.get().completedEntries)
    }

    @Test
    fun rejectsAnEntryThatTraversesAnEarlierSymlinkAndRemovesStaging() =
        kotlinx.coroutines.runBlocking {
            val bytes = archive(
                listOf(
                    Entry.Directory("ubuntu-test/"),
                    Entry.Symlink("ubuntu-test/link", "usr/bin"),
                    Entry.File("ubuntu-test/link/outside", "not-written".toByteArray()),
                ),
            )
            val manifest = manifest(bytes, entryCount = 3)
            val paths = EnvironmentPaths(File(temporary.root, "environment")).also {
                it.ensureDirectories()
            }
            val token = FileRootfsArchiveStore(paths).ensureVerified(
                manifest,
                RootfsArchiveSource { ByteArrayInputStream(bytes) },
            ) { }

            val failure = runCatching {
                TarXzRootfsExtractor(paths).extract(manifest, token, "generation-bad") { }
            }.exceptionOrNull()

            assertTrue(failure is EnvironmentCommandException)
            assertEquals(
                EnvironmentFailureCode.LINK_ESCAPE,
                (failure as EnvironmentCommandException).failure.code,
            )
            assertFalse(paths.stagingGeneration("generation-bad").exists())
            assertFalse(File(paths.generationRoot("generation-bad"), "link/outside").exists())
        }

    @Test
    fun rejectsHashMismatchAndDeletesPartialArchive() = kotlinx.coroutines.runBlocking {
        val bytes = archive(listOf(Entry.Directory("ubuntu-test/")))
        val manifest = manifest(bytes, entryCount = 1, sha256 = "0".repeat(64))
        val paths = EnvironmentPaths(File(temporary.root, "environment")).also {
            it.ensureDirectories()
        }

        val failure = runCatching {
            FileRootfsArchiveStore(paths).ensureVerified(
                manifest,
                RootfsArchiveSource { ByteArrayInputStream(bytes) },
            ) { }
        }.exceptionOrNull()

        assertTrue(failure is EnvironmentCommandException)
        assertEquals(
            EnvironmentFailureCode.HASH_MISMATCH,
            (failure as EnvironmentCommandException).failure.code,
        )
        assertFalse(paths.archive(manifest).exists())
        assertFalse(paths.archivePart(manifest).exists())
    }

    @Test
    fun rejectedActivationKeepsPreviousGenerationAndCanResumeAtomicMove() = kotlinx.coroutines.runBlocking {
        val bytes = archive(listOf(Entry.Directory("ubuntu-test/")))
        val manifest = manifest(bytes, 1)
        val paths = EnvironmentPaths(File(temporary.root, "activation")).also { it.ensureDirectories() }
        val activator = FileRootfsActivator(paths)
        paths.stagingRoot("generation-good").mkdirs()
        RootfsMarker.write(paths.stagingRoot("generation-good"), manifest, "generation-good")
        assertEquals("generation-good", activator.activate(manifest, "generation-good"))
        // Repeating the last atomic step after a process interruption is idempotent.
        assertEquals("generation-good", activator.activate(manifest, "generation-good"))
        paths.stagingRoot("generation-bad").mkdirs()
        assertTrue(runCatching { activator.activate(manifest, "generation-bad") }.isFailure)
        assertEquals("generation-good", activator.currentActive(manifest))
        assertTrue(paths.generationRoot("generation-good").isDirectory)
    }

    private fun manifest(
        archive: ByteArray,
        entryCount: Int,
        sha256: String = hashOf(archive),
    ) = RootfsManifest(
        id = "ubuntu-test",
        archive = com.deepstudent.features.environments.api.RootfsArchiveSpec(
            name = "ubuntu-test.tar.xz",
            sha256 = sha256,
            bytes = archive.size.toLong(),
            root = "ubuntu-test",
            entryCount = entryCount,
        ),
        budget = com.deepstudent.features.environments.api.RootfsResourceBudget(
            extractionBytes = 1024 * 1024,
            minimumFreeBytes = 2 * 1024 * 1024,
        ),
        platform = com.deepstudent.features.environments.api.RootfsPlatformIdentity("ubuntu", "24.04"),
    )

    private fun archive(entries: List<Entry>): ByteArray {
        val bytes = ByteArrayOutputStream()
        XZOutputStream(bytes, LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { entry ->
                    val header = when (entry) {
                        is Entry.Directory -> TarArchiveEntry(entry.name, TarConstants.LF_DIR)
                        is Entry.File -> TarArchiveEntry(entry.name).apply { size = entry.content.size.toLong() }
                        is Entry.Symlink -> TarArchiveEntry(entry.name, TarConstants.LF_SYMLINK).apply {
                            linkName = entry.target
                        }
                    }
                    tar.putArchiveEntry(header)
                    if (entry is Entry.File) tar.write(entry.content)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return bytes.toByteArray()
    }

    private fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private sealed interface Entry {
        val name: String

        data class Directory(override val name: String) : Entry

        data class File(override val name: String, val content: ByteArray) : Entry

        data class Symlink(override val name: String, val target: String) : Entry
    }
}
