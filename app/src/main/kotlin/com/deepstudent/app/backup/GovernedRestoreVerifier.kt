package com.deepstudent.app.backup

import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.data.RestoreCandidateVerifier
import com.deepstudent.services.resources.data.assets.readVfsRestoreBlobs
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Only the isolated candidate is opened or upgraded. No production database leases are reopened here. */
internal class GovernedRestoreVerifier(
    private val source: MigrationSource,
    plan: RestorePlan,
    private val legacy: LegacyRestoreChecks? = null,
    private val prepareDatabase: (SharedDatabase, SqlDatabase) -> Unit = { _, _ -> },
) : RestoreCandidateVerifier {
    private val blobs = RestoreBlobFiles(plan.files.filter { it.slotRelativePath.startsWith("vfs_blobs/") }
        .associateBy { it.slotRelativePath })

    override fun verifyDatabases(candidate: File) {
        SharedDatabase.entries.forEach { id ->
            if (legacy != null) legacy.checkDatabase(id) { verifyDatabase(candidate, id) }
            else verifyDatabase(candidate, id)
        }
    }

    private fun verifyDatabase(candidate: File, id: SharedDatabase) {
        DatabaseRegistry.openShared(database(candidate, id), id, source).use { database ->
            legacy?.checkSourceVersion(id, database.migrationReport)
            prepareDatabase(id, database)
            val healthy = database.query("PRAGMA integrity_check") { row -> check(row.text(0) == "ok"); true }
            check(healthy == listOf(true))
            database.query<Unit>("PRAGMA foreign_key_check") { error("invalid foreign key") }
        }
    }

    override fun verifyResources(candidate: File) {
        if (legacy != null) legacy.checkResources { verifyVfs(candidate) }
        else verifyVfs(candidate)
    }

    private fun verifyVfs(candidate: File) {
        val vfs = SharedDatabase.VFS
        val root = candidate.resolve("vfs_blobs").toPath()
        val entries = DatabaseRegistry.openShared(database(candidate, vfs), vfs, source).use { database ->
            database.readSnapshot {
                legacy?.checkReferences(this)
                readVfsRestoreBlobs(this)
            }
        }
        entries.forEach { blobs.verify(root, it) }
    }

    private fun database(candidate: File, id: SharedDatabase) = candidate.resolve("databases/${id.id}.db").also {
        check(Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.length() > 0)
    }
}
