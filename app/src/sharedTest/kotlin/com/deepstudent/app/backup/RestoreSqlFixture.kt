package com.deepstudent.app.backup

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.api.RestoreAdmission
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.backend.ArchivePreflight
import com.deepstudent.services.backup.data.RestoreCandidateVerifier
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.backup.data.RestoreExecutor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class RestoreSqlFixture(private val root: File, val migrations: MigrationSource, private val manifestTemplate: () -> String) {
    fun reopen() = RestoreSqlFixture(root, migrations, manifestTemplate)

    private val space = FileDataSpace(root.resolve("app").apply { mkdir() })
    val active get() = space.acquireActive().use { it.root }
    val original = active
    private val source = root.resolve("incoming").apply { mkdir() }

    fun incomingFile(id: SharedDatabase) = source.resolve("${id.id}.db")

    fun incoming(id: SharedDatabase, seed: (SqlDatabase) -> Unit) = DatabaseRegistry.openShared(incomingFile(id), id, migrations).use(seed)

    fun open(slot: File, id: SharedDatabase) = DatabaseRegistry.openShared(
        slot.resolve("databases/${id.id}.db"),
        id,
        migrations,
    )

    fun archive(blobs: Map<String, ByteArray> = emptyMap()): File {
        val template = JsonValue.parseObject(manifestTemplate())
        val coverage = template.objectAt("coverage")
        val specs = coverage.objectAt("domains")
        val domains = JsonValue.Object.Builder().putAll(specs)
        val entries = linkedMapOf<String, ByteArray>()
        val files = SharedDatabase.entries.map { id ->
            val name = "${id.id}.db"
            val bytes = source.resolve(name).readBytes().also { entries[name] = it }
            val domain = "database:${id.id}"
            domains.put(domain, JsonValue.Object.Builder().putAll(specs.objectAt(domain)).put("total_size", bytes.size).build())
            entry(name, bytes).put("database_id", id.id).build()
        } + blobs.map { (relative, bytes) ->
            val name = "assets/vfs_blobs/$relative"
            entries[name] = bytes
            entry(name, bytes).build()
        }
        if (blobs.isNotEmpty()) {
            val domain = "asset-root:vfs_blobs"
            val paths = entries.keys.filter { it.startsWith("assets/vfs_blobs/") }
            domains.put(
                domain,
                JsonValue.Object.Builder().putAll(specs.objectAt(domain)).put("status", "complete")
                    .put("paths", JsonValue.array(paths.map(JsonValue::text))).put("file_count", paths.size)
                    .put("total_size", blobs.values.sumOf { it.size }).build(),
            )
        }
        entries["manifest.json"] = JsonValue.Object.Builder().putAll(template)
            .put("files", JsonValue.array(files))
            .put("coverage", JsonValue.Object.Builder().putAll(coverage).put("domains", domains.build()).build())
            .build().literal().toByteArray()
        return root.resolve("recovery.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { out ->
                entries.forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
    }

    fun restore(
        zip: File,
        verifier: (RestorePlan) -> RestoreCandidateVerifier = { ordinaryRestoreVerifier(migrations, it) },
        operationId: String = "host-restore",
        admission: RestoreAdmission = RestoreAdmission.STANDARD,
    ): RestoreExecutionResult {
        val plan = CheckedZipArchive(zip).use {
            when (admission) {
                RestoreAdmission.STANDARD -> ArchivePreflight.inspect(it, Long.MAX_VALUE).plan
                RestoreAdmission.AUTHENTICATED_PACKAGE -> ArchivePreflight.inspectAuthenticated(it, Long.MAX_VALUE).plan
            }
        }
        check(plan.canStage) { plan.problems.toString() }
        return RestoreExecutor(verifier(plan), admission).execute(space, operationId, plan) { CheckedZipArchive(zip) }
    }

    private fun entry(path: String, bytes: ByteArray) = JsonValue.Object.Builder().put("path", path)
        .put("size", bytes.size).put("sha256", Digests.sha256Hex(bytes))
    private fun JsonValue.Object.objectAt(key: String) = this[key] as JsonValue.Object
}
