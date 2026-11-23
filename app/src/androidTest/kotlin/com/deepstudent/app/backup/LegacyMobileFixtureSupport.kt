package com.deepstudent.app.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.backup.api.RestorePlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Each test owns its own files, databases, queue and DataSpace, but uses the real resolver/provider. */
internal class LegacyMobileFixtureSupport : AutoCloseable {
    private val runner = InstrumentationRegistry.getInstrumentation()
    private val base = runner.targetContext
    private val root = File(base.cacheDir.canonicalFile, "s210-preview-fixtures").apply { mkdirs() }
    val scratch: File = Files.createTempDirectory(root.toPath(), "s212-legacy-").toFile()
    val target: Context = object : ContextWrapper(base) {
        private val directory = scratch.resolve("target").apply { mkdir() }
        override fun getFilesDir() = directory.resolve("files").apply { mkdir() }
        override fun getCacheDir() = directory.resolve("cache").apply { mkdir() }
        override fun getNoBackupFilesDir() = directory.resolve("no-backup").apply { mkdir() }
    }
    val expected: JSONObject = runner.context.assets.open("legacy-mobile-expected.json")
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    val seeds: JSONObject get() = expected.getJSONObject("seeds")
    val reader = SafBackupPreviewReader(target)

    fun original(kind: String): File {
        val record = expected.getJSONObject("files").getJSONObject(kind)
        val name = File(record.getString("file")).name
        val destination = scratch.resolve(name)
        runner.context.assets.open(name).use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        assertEquals(record.getLong("bytes"), destination.length())
        assertEquals(record.getString("sha256"), Digests.sha256Hex(destination.readBytes()))
        return destination
    }

    fun uri(file: File): Uri = FileProvider.getUriForFile(base, "${base.packageName}.debug.documents", file)

    suspend fun seedLocalState(): LegacyLocalState {
        val noteAndRoot = MainStorageResources.open(target).use { storage ->
            storage.activeRoot.resolve("local-state.txt").writeText("local-only")
            storage.notes.create(CreateNote("old-device-note", "old device data")).id to storage.activeRoot
        }
        val audit = noteAndRoot.second.resolve("databases/audit.db")
        DatabaseRegistry.openLocal(audit).use { db ->
            db.execute("CREATE TABLE local_audit (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("INSERT INTO local_audit (id, value) VALUES ('local-only', 'never-replaced')")
        }
        target.noBackupFilesDir.resolve("local-installation.txt").writeText("keep-installation")
        assertNoIdentityState()
        return LegacyLocalState(noteAndRoot.first, noteAndRoot.second)
    }

    fun assertNoIdentityState() {
        assertFalse(target.noBackupFilesDir.resolve("sync/device-id").exists())
        assertFalse(target.noBackupFilesDir.resolve("sync/configuration.json").exists())
    }

    suspend fun assertOldDataRemains(state: LegacyLocalState, activeIsOld: Boolean) {
        MainStorageResources.open(target).use { storage ->
            if (activeIsOld) {
                assertEquals(state.root.canonicalFile, storage.activeRoot.canonicalFile)
                assertEquals("old device data", storage.notes.get(state.noteId)?.content)
            } else {
                assertNotEquals(state.root.canonicalFile, storage.activeRoot.canonicalFile)
                assertNull(storage.notes.get(state.noteId))
            }
            assertEquals("local-only", storage.activeRoot.resolve("local-state.txt").readText())
        }
        assertEquals("local-only", state.root.resolve("local-state.txt").readText())
        DatabaseRegistry.openShared(state.root.resolve("databases/vfs.db"), SharedDatabase.VFS, BundledMigrationSource(target)).use { db ->
            val old = db.query(
                "SELECT r.data FROM notes n JOIN resources r ON r.id=n.resource_id WHERE n.id=?",
                listOf(SqlValue.Text(state.noteId)),
            ) { it.text(0) }
            assertEquals(listOf("old device data"), old)
        }
        assertEquals("keep-installation", target.noBackupFilesDir.resolve("local-installation.txt").readText())
        assertNoIdentityState()
    }

    fun assertSourceBinding(file: File, plan: RestorePlan) {
        ZipFile(file).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes() }
            assertEquals(Digests.sha256Hex(manifest), plan.manifestSha256)
            plan.files.forEach { planned ->
                val entry = checkNotNull(zip.getEntry(planned.archivePath))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                assertEquals(planned.size, bytes.size.toLong())
                assertEquals(planned.sha256, Digests.sha256Hex(bytes))
            }
        }
    }

    /** Produces a negative test ZIP only; originals remain byte-for-byte frozen assets. */
    fun alterArchive(
        source: File,
        name: String,
        omitBlob: Boolean = false,
        sourceVersion: Long? = null,
        corruptBlob: Boolean = false,
    ): File {
        val destination = scratch.resolve(name)
        ZipFile(source).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            if (omitBlob) removeBlobDeclaration(manifest)
            sourceVersion?.let { manifest.getJSONObject("schema_versions").put("vfs", it) }
            val blobPath = "assets/vfs_blobs/${seeds.getString("blobRelativePath")}"
            ZipOutputStream(destination.outputStream()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (omitBlob && entry.name == blobPath) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    when {
                        entry.name == "manifest.json" && (omitBlob || sourceVersion != null) ->
                            out.write(manifest.toString().toByteArray(Charsets.UTF_8))

                        entry.name == blobPath && corruptBlob -> {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            bytes[0] = (bytes[0].toInt() xor 1).toByte()
                            out.write(bytes)
                        }

                        else -> zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
        return destination
    }

    private fun removeBlobDeclaration(manifest: JSONObject) {
        val files = manifest.getJSONArray("files")
        val blobPath = "assets/vfs_blobs/${seeds.getString("blobRelativePath")}"
        val indices = (0 until files.length()).filter { files.getJSONObject(it).getString("path") == blobPath }
        check(indices.size == 1)
        files.remove(indices.single())
        manifest.getJSONObject("coverage").getJSONObject("domains").getJSONObject("asset-root:vfs_blobs")
            .put("status", "empty").put("paths", JSONArray()).put("file_count", 0).put("total_size", 0)
    }

    fun copySidecarAndCheckFrozenDigest(): File {
        val record = expected.getJSONObject("sidecar")
        val name = File(record.getString("file")).name
        val file = scratch.resolve(name)
        runner.context.assets.open(name).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        assertEquals(record.getString("sha256"), Digests.sha256Hex(file.readBytes()))
        return file
    }

    override fun close() {
        check(scratch.deleteRecursively())
    }
}

internal data class LegacyLocalState(val noteId: String, val root: File)
