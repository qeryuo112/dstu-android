package com.deepstudent.app.backup

import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.backend.ArchivePreflight
import com.deepstudent.services.resources.api.CreateAttachment
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Safe cache-only App composition with four real migrated databases and a real referenced VFS blob. */
@RunWith(AndroidJUnit4::class)
class ColdBackupRestoreDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    private val runner = InstrumentationRegistry.getInstrumentation()
    private val base = runner.targetContext
    private val root = File(base.cacheDir.canonicalFile, "s210-preview-fixtures").apply { mkdirs() }
    private val fixture = Files.createTempDirectory(root.toPath(), "s211-cold-").toFile()
    private val target = context("target")
    private val incoming = context("incoming")
    private val payload = ByteArray(1536 * 1024) { (it % 97).toByte() }

    @After fun clean() {
        check(fixture.deleteRecursively())
    }

    @Test fun confirmedQueueActivatesOnlyAtColdBoundaryAndPreservesOldSlotAndDeviceLocalFiles(): Unit = runBlocking {
        val old = MainStorageResources.open(target).use { storage ->
            storage.activeRoot.resolve("device-local.txt").writeText("keep")
            storage.notes.create(CreateNote("old", "old"))
        }
        val pack = backup(withBlob = true)
        val orchestrator = schedule(pack.zip)
        assertEquals(BackupRestoreUiStatus.QUEUED, orchestrator.status.value)
        MainStorageResources.open(target).use { assertEquals("old", it.notes.get(old.id)?.content) }
        val fresh = BackupRestoreCoordinator(target)
        fresh.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.APPLIED, fresh.status.value)
        MainStorageResources.open(target).use { storage ->
            assertEquals("restored", storage.notes.get(pack.noteId)?.content)
            assertArrayEquals(payload, storage.attachments.get(pack.attachmentId)?.bytes)
            assertEquals("keep", storage.activeRoot.resolve("device-local.txt").readText())
            assertNull(storage.notes.get(old.id))
        }
        assertTrue(target.filesDir.resolve("active/databases/vfs.db").isFile)
        val another = BackupRestoreCoordinator(target)
        another.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.APPLIED, another.status.value)
        assertFalse(target.noBackupFilesDir.resolve("backup-restore/pending").exists())
        assertFalse(target.noBackupFilesDir.resolve("backup-restore/confirmed.zip").exists())
    }

    @Test fun sourceDatabaseWithAnUndeliveredBlobFailsBeforeChangingActiveData(): Unit = runBlocking {
        val old = MainStorageResources.open(target).use { it.notes.create(CreateNote("old", "still here")) }
        val pack = backup(withBlob = false)
        assertEquals(BackupRestoreUiStatus.QUEUED, schedule(pack.zip).status.value)
        val fresh = BackupRestoreCoordinator(target)
        fresh.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.FAILED, fresh.status.value)
        MainStorageResources.open(target).use {
            assertEquals(target.filesDir.resolve("active").canonicalFile, it.activeRoot)
            assertEquals("still here", it.notes.get(old.id)?.content)
        }
    }

    @Test fun authenticatedMixedBackupRestoresDataAndLeavesDesktopKeysAndScriptsInOriginal(): Unit = runBlocking {
        val old = MainStorageResources.open(target).use { it.notes.create(CreateNote("old", "before import")) }
        val pack = backup(withBlob = true, desktopExtras = true)
        CheckedZipArchive(pack.zip).use { assertFalse(ArchivePreflight.inspect(it, Long.MAX_VALUE).plan.canStage) }
        val incomingFile = fixture.resolve("desktop-full.dsbk")
        val crypto = DsbkCrypto.production()
        val password = "public-test-passphrase".toCharArray()
        try {
            pack.zip.inputStream().use { input ->
                incomingFile.outputStream().use { output -> crypto.encryptV2(input, output, password) }
            }
            val packageOperation = BackupFileOperation(fixture, crypto, EncryptedBackupTrace()) { }
            val negative = assertThrows(EncryptedBackupFileException::class.java) {
                packageOperation.prepare(incomingFile, EncryptedBackupIntent.IMPORT, "wrong".toCharArray())
            }
            assertEquals(EncryptedBackupFileError.AUTHENTICATION_FAILED, negative.error)
            assertFalse(fixture.resolve("verified.zip").exists())
            assertEquals("before import", MainStorageResources.open(target).use { it.notes.get(old.id)?.content })
            val prepared = packageOperation.prepare(incomingFile, EncryptedBackupIntent.IMPORT, password)
            assertDesktopOnlyRoutes(prepared)
            val coordinator = BackupRestoreCoordinator(target).also { it.beforeBusinessOpen() }
            assertTrue(
                coordinator.scheduleAuthenticated(
                    checkNotNull(prepared.proof),
                    checkNotNull(prepared.plan),
                    prepared.trace.operationId,
                ),
            )
            val request = PendingBackupRestore(target.noBackupFilesDir.resolve("backup-restore")).request()
            assertEquals(prepared.trace.operationId.toString(), request?.id)
            assertEquals("before import", MainStorageResources.open(target).use { it.notes.get(old.id)?.content })
            assertMixedRestore(pack)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun assertDesktopOnlyRoutes(prepared: EncryptedBackupPrepared) {
        val plan = checkNotNull(prepared.plan)
        assertTrue(plan.problems.toString(), plan.canStage)
        assertEquals(
            setOf("crypto", "agents", "asset-root:workspaces"),
            plan.domains.filter { it.action == DomainAction.RETAIN_IN_SOURCE }.map { it.id }.toSet(),
        )
        assertTrue(plan.files.none { it.archivePath == SECRET_PATH || it.archivePath == SCRIPT_PATH })
    }

    private suspend fun assertMixedRestore(pack: FixturePack) {
        val fresh = BackupRestoreCoordinator(target).also { it.beforeBusinessOpen() }
        assertEquals(BackupRestoreUiStatus.APPLIED, fresh.status.value)
        MainStorageResources.open(target).use { storage ->
            assertEquals("restored", storage.notes.get(pack.noteId)?.content)
            assertArrayEquals(payload, storage.attachments.get(pack.attachmentId)?.bytes)
            assertTrue(storage.activeRoot.walkTopDown().none { it.name == ".master_key" || it.name == "imported-fixture.js" })
        }
        CheckedZipArchive(pack.zip).use { archive ->
            assertArrayEquals("desktop-key-fixture".toByteArray(), archive.open(SECRET_PATH).use { it.readBytes() })
            assertArrayEquals("desktop-script-fixture".toByteArray(), archive.open(SCRIPT_PATH).use { it.readBytes() })
        }
        assertFalse(target.noBackupFilesDir.resolve("backup-restore/confirmed.zip").exists())
    }

    private suspend fun schedule(zip: File): BackupRestoreCoordinator {
        val uri = FileProvider.getUriForFile(base, "${base.packageName}.debug.documents", zip)
        val plan = SafBackupPreviewReader(target).read(uri)
        assertTrue(plan.problems.toString(), plan.canStage)
        return BackupRestoreCoordinator(target).also { coordinator ->
            coordinator.beforeBusinessOpen()
            coordinator.schedule(uri, plan)
        }
    }

    private suspend fun backup(withBlob: Boolean, desktopExtras: Boolean = false): FixturePack {
        val added = MainStorageResources.open(incoming).use { storage ->
            val note = storage.notes.create(CreateNote("restored", "restored"))
            val attachment = storage.attachments.create(CreateAttachment("artifact.bin", "application/octet-stream", payload))
            storage.notes.linkAttachment(note.id, attachment.resourceId)
            note.id to attachment.id
        }
        val active = incoming.filesDir.resolve("active")
        val content = linkedMapOf<String, ByteArray>()
        val manifest = JSONObject(runner.context.assets.open("desktop-v3-full.json").bufferedReader().use { it.readText() })
        val files = manifest.getJSONArray("files")
        val domains = manifest.getJSONObject("coverage").getJSONObject("domains")
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val name = entry.getString("database_id")
            val db = SharedDatabase.entries.single { it.id == name }
            val path = entry.getString("path")
            val file = active.resolve("databases/$path")
            DatabaseRegistry.openShared(file, db, BundledMigrationSource(base)).use {
                manifest.getJSONObject("schema_versions").put(name, checkNotNull(it.migrationReport).toVersion)
            }
            val bytes = file.readBytes()
            content[path] = bytes
            entry.put("sha256", Digests.sha256Hex(bytes)).put("size", bytes.size)
            domains.getJSONObject("database:$name").put("total_size", bytes.size)
        }
        if (withBlob) addBlob(active, content, manifest, domains)
        if (desktopExtras) {
            manifest.put("key_policy", "included_local")
            addDesktopOnly(manifest, content, SECRET_PATH, "desktop-key-fixture", "crypto")
            addDesktopOnly(manifest, content, SCRIPT_PATH, "desktop-script-fixture", "asset-root:workspaces", "agents")
        }
        content["manifest.json"] = manifest.toString().toByteArray()
        val zip = File(fixture, "recovery.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            content.forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return FixturePack(zip, added.first, added.second)
    }

    private fun addDesktopOnly(
        manifest: JSONObject,
        content: MutableMap<String, ByteArray>,
        path: String,
        value: String,
        vararg ids: String,
    ) {
        val bytes = value.toByteArray().also { content[path] = it }
        manifest.getJSONArray("files").put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", Digests.sha256Hex(bytes)))
        val domains = manifest.getJSONObject("coverage").getJSONObject("domains")
        ids.forEach { id ->
            domains.getJSONObject(id).put("status", "complete").put("paths", org.json.JSONArray().put(path))
                .put("file_count", 1).put("total_size", bytes.size)
        }
    }

    private fun addBlob(active: File, content: MutableMap<String, ByteArray>, manifest: JSONObject, domains: JSONObject) {
        val root = active.resolve("vfs_blobs")
        val file = root.walkTopDown().single { it.isFile && !it.name.startsWith('.') }
        val path = "assets/vfs_blobs/${file.relativeTo(root).invariantSeparatorsPath}"
        val bytes = file.readBytes()
        content[path] = bytes
        manifest.getJSONArray("files").put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", Digests.sha256Hex(bytes)))
        domains.getJSONObject("asset-root:vfs_blobs").put("status", "complete")
            .put("paths", org.json.JSONArray().put(path)).put("file_count", 1).put("total_size", bytes.size)
    }

    private fun context(name: String) = object : ContextWrapper(base) {
        private val directory = fixture.resolve(name).apply { mkdir() }
        override fun getFilesDir() = directory.resolve("files").apply { mkdir() }
        override fun getCacheDir() = directory.resolve("cache").apply { mkdir() }
        override fun getNoBackupFilesDir() = directory.resolve("no-backup").apply { mkdir() }
    }

    private data class FixturePack(val zip: File, val noteId: String, val attachmentId: String)

    private companion object {
        const val SECRET_PATH = "crypto/.master_key"
        const val SCRIPT_PATH = "assets/workspaces/agents/imported-fixture.js"
    }
}
