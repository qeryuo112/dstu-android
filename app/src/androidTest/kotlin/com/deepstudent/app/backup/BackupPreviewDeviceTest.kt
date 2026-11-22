package com.deepstudent.app.backup

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.RestoreProblemCode
import com.deepstudent.services.backup.api.SnapshotKind
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPreviewDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(context.cacheDir, "s210-preview-fixtures").apply { mkdirs() }
    private val scratch = Files.createTempDirectory(root.toPath(), "s210-").toFile()
    private val reader = SafBackupPreviewReader(context)

    @After fun removeFixture() { check(scratch.deleteRecursively()) }

    @Test fun fullDesktopV3HasPerDomainCoverageButDoesNotActivate(): Unit = runBlocking {
        val plan = reader.read(uri(zip("full")))
        assertEquals(SnapshotKind.FULL, plan.snapshotKind)
        assertEquals(21, plan.domains.size)
        assertEquals(4, plan.domains.count { it.action == DomainAction.STAGE_IN_SLOT })
        assertTrue(plan.problems.toString(), plan.canStage)
        assertFalse(plan.canSwitchSlot)
    }

    @Test fun partialDesktopV3ShowsExcludedDomainAndCannotReplaceSlot(): Unit = runBlocking {
        val plan = reader.read(uri(zip("partial")))
        assertEquals(SnapshotKind.PARTIAL_OVERLAY, plan.snapshotKind)
        assertEquals(21, plan.domains.size)
        assertEquals(DomainAction.EXCLUDED, plan.domains.single { it.id == "database:chat_v2" }.action)
        assertTrue(plan.problems.any { it.code == RestoreProblemCode.PARTIAL_NOT_SLOTABLE })
        assertFalse(plan.canStage)
    }

    @Test fun sameSizeChangedBytesAndMissingDomainNeverBecomeStageable(): Unit = runBlocking {
        val changed = reader.read(uri(zip("full", changeVfs = true)))
        assertFalse(changed.canStage)
        assertTrue(changed.problems.any { it.code == RestoreProblemCode.HASH_MISMATCH })
        val missing = reader.read(uri(zip("full", omitChat = true)))
        assertFalse(missing.canStage)
        assertTrue(missing.problems.any { it.code == RestoreProblemCode.MISSING_ARCHIVE_ENTRY })
    }

    @Test fun frozenV3LegacyDataIsReadWithoutAutomaticallyRestoringAudit(): Unit = runBlocking {
        val name = "legacy-mobile-standard-standard-v1.zip"
        val file = File(scratch, name)
        instrumentation.context.assets.open(name).use { source -> file.outputStream().use { source.copyTo(it) } }
        val plan = reader.read(uri(file))
        assertEquals(SnapshotKind.FULL, plan.snapshotKind)
        assertEquals(DomainAction.PRESERVE_INACTIVE, plan.domains.single { it.id == "audit" }.action)
        assertTrue(plan.problems.toString(), plan.canStage)
        assertFalse(plan.canSwitchSlot)
    }

    private fun uri(file: File) = FileProvider.getUriForFile(context, "com.deepstudent.app.debug.documents", file)

    private fun zip(kind: String, changeVfs: Boolean = false, omitChat: Boolean = false): File {
        val source = instrumentation.context.assets.open("desktop-v3-$kind.json").use { it.readBytes() }
        val entries = if (kind == "full") listOf("vfs", "llm_usage", "chat_v2", "mistakes")
            .filterNot { it == "chat_v2" && omitChat }
            .associate { "$it.db" to (if (it == "vfs" && changeVfs) "sQlite:vfs" else "sqlite:$it").toByteArray() }
        else mapOf("vfs.db" to "sqlite:vfs".toByteArray(), "assets/vfs_blobs/ab/desktop.txt" to "desktop-asset".toByteArray())
        return File(scratch, "$kind.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { stream ->
                (entries + ("manifest.json" to source)).forEach { (name, bytes) ->
                    stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
                }
            }
        }
    }
}
