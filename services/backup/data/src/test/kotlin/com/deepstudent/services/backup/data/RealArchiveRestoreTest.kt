package com.deepstudent.services.backup.data

import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.backend.ArchivePreflight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Frozen desktop-generated v3 manifest with synthetic bytes; the ZIP itself is test-generated. */
class RealArchiveRestoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun productionPreflightAndCheckedZipFeedExecutorWithoutAlternativeParser() {
        val manifest = requireNotNull(javaClass.getResourceAsStream("/fixtures/desktop-v3-full.json")).use { it.readBytes() }
        val payloads = listOf("vfs", "chat_v2", "mistakes", "llm_usage").associate { "$it.db" to "sqlite:$it".toByteArray() }
        val zipFile = temp.newFile("synthetic.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for ((name, bytes) in payloads + ("manifest.json" to manifest)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val preview = CheckedZipArchive(zipFile).use { ArchivePreflight.inspect(it, Long.MAX_VALUE).plan }
        assertTrue(preview.problems.toString(), preview.canStage)
        val root = temp.newFolder()
        val space = FileDataSpace(root)
        seed(space)
        val result = RestoreExecutor(noOpVerifier).execute(space, "real-zip", preview) { CheckedZipArchive(zipFile) }
        assertEquals(RestoreExecutionResult.Committed("real-zip", CommitDisposition.NEWLY_COMMITTED), result)
        for (name in listOf("vfs", "chat_v2", "mistakes", "llm_usage")) {
            assertEquals("sqlite:$name", space.current("databases/$name.db"))
        }
        assertEquals("device-local", space.current("databases/runtime.db"))
        assertEquals("old-vfs", File(root, "active/databases/vfs.db").readText())
    }
}
