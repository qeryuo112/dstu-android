package com.deepstudent.services.backup.data

import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.api.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

internal val manifestBytes = "{\"version\":\"3.0.0\",\"synthetic\":true}".toByteArray()
internal val vfsBytes = "synthetic-new-vfs".toByteArray()
internal val blobBytes = "synthetic-blob".toByteArray()
internal fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun fixturePlan(): RestorePlan = RestorePlan(
    snapshotKind = SnapshotKind.FULL,
    source = RestorePlanSource("synthetic-backup", sha(manifestBytes)),
    domains = listOf(
        DomainPreview("database:vfs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, vfsBytes.size.toLong()),
        DomainPreview("asset-root:vfs_blobs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, blobBytes.size.toLong()),
    ),
    files = listOf(
        PlannedFile("vfs.db", "databases/vfs.db", vfsBytes.size.toLong(), sha(vfsBytes)),
        PlannedFile("assets/vfs_blobs/aa/new", "vfs_blobs/aa/new", blobBytes.size.toLong(), sha(blobBytes)),
    ),
    requiredBytes = 1024,
    problems = emptyList(),
    canStage = true,
)

internal class MemoryArchive(
    private val values: Map<String, ByteArray> = mapOf("manifest.json" to manifestBytes, "vfs.db" to vfsBytes, "assets/vfs_blobs/aa/new" to blobBytes),
    private val extra: List<ArchiveEntry> = emptyList(),
) : RestoreArchive {
    override fun inspect() = ArchiveCatalog(values.map { ArchiveEntry(it.key, ArchiveEntryKind.FILE, it.value.size.toLong()) } + extra)
    override fun open(path: String) = ByteArrayInputStream(values.getValue(path))
    override fun close() = Unit
}

internal val noOpVerifier = object : RestoreCandidateVerifier {
    override fun verifyDatabases(candidate: File) = Unit
    override fun verifyResources(candidate: File) = Unit
}

internal fun executor(
    plan: RestorePlan = fixturePlan(),
    verifier: RestoreCandidateVerifier = noOpVerifier,
    hook: RestoreCheckpointHook = RestoreCheckpointHook.NONE,
    audit: RestoreAudit = RestoreAudit.NONE,
) = RestoreExecutor(verifier, ArchivePlanReader { _, _, _ -> plan }, audit, hook)

internal fun seed(space: FileDataSpace) {
    space.acquireActive().use { lease ->
        fun write(path: String, text: String) {
            val file = File(lease.root, path)
            requireNotNull(file.parentFile).mkdirs()
            file.writeText(text)
        }
        write("databases/vfs.db", "old-vfs")
        write("databases/vfs.db-wal", "old-vfs-wal")
        write("databases/vfs.db-shm", "old-vfs-shm")
        write("databases/chat_v2.db", "old-chat")
        write("databases/chat_v2.db-wal", "old-chat-wal")
        write("databases/chat_v2.db-shm", "old-chat-shm")
        write("databases/runtime.db", "device-local")
        write("databases/runtime.db-wal", "device-local-wal")
        write("vfs_blobs/legacy/unreferenced", "old-unreferenced")
        write("chat_attachments/local", "device-attachment")
    }
}

internal fun FileDataSpace.current(path: String): String? = acquireActive().use { lease ->
    File(lease.root, path).takeIf(File::exists)?.readText()
}
