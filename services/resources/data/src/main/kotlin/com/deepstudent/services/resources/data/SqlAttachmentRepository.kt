package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.AttachmentContent
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.CreateAttachment
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.api.WorkspaceFileArea
import com.deepstudent.services.resources.api.WorkspaceFileIds
import com.deepstudent.services.resources.api.WorkspaceFileWrite
import com.deepstudent.services.resources.api.WorkspaceFiles
import com.deepstudent.services.resources.backend.AttachmentRepository
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.resources.backend.ResourcePhase
import com.deepstudent.services.resources.backend.ResourceTrace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

class SqlAttachmentRepository internal constructor(
    private val database: SqlDatabase,
    blobsRoot: File,
    private val clock: WallClock,
    private val faults: ResourceFaults,
    space: AvailableSpace,
) : AttachmentRepository,
    WorkspaceFiles {
    constructor(database: SqlDatabase, blobsRoot: File, clock: WallClock) :
        this(database, blobsRoot, clock, ResourceFaults.NONE, AvailableSpace.REAL)

    private val blobs = BlobFiles(blobsRoot, faults, space)

    override suspend fun create(command: CreateAttachment): AttachmentMetadata = createStream(
        StreamAttachmentRequest(command.name, command.mediaType, command.size.toLong()),
        ByteArrayInputStream(command.bytes),
        {},
    )

    override suspend fun createStream(
        request: StreamAttachmentRequest,
        input: InputStream,
        onProgress: (Long) -> Unit,
    ): AttachmentMetadata = save(AttachmentWrite(request), input, onProgress)

    private suspend fun save(write: AttachmentWrite, input: InputStream, onProgress: (Long) -> Unit): AttachmentMetadata {
        val request = write.source
        val context = currentCoroutineContext()
        val trace = context[ResourceTrace]
        val limit = if (request.mediaType.startsWith("image/", true)) AttachmentService.IMAGE_LIMIT else AttachmentService.FILE_LIMIT
        phase(trace, ResourcePhase.PREFLIGHT) {
            context.ensureActive()
            validateRequest(request, limit)
            val uncertain = blobs.locked { database.transaction { blobs.recover { hash, path -> hasBlobOwner(hash, path) } } }
            if (uncertain > 0) trace?.recoverableCleanup(ownershipDiagnostic())
        }
        val prepared = phase(trace, ResourcePhase.PREPARE) {
            blobs.prepare(input, request.expectedSize, limit.toLong(), { context.ensureActive() }, onProgress)
        }
        return storePrepared(write, prepared, context, trace)
    }

    private fun storePrepared(
        write: AttachmentWrite,
        prepared: PreparedBlob,
        context: kotlin.coroutines.CoroutineContext,
        trace: ResourceTrace?,
    ): AttachmentMetadata {
        val state = CommitState()
        var operationFailure: Throwable? = null
        try {
            return try {
                blobs.locked { commitAndClose(write, prepared, context, trace, state) }
            } catch (failure: Exception) {
                // Lock-release failures after commit cannot turn a committed write into a reported failure.
                val result = state.committed ?: throw failure
                trace?.recoverableCleanup(failure)
                result
            }
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            if (!state.closed) closePrepared(prepared, state, operationFailure, trace)
        }
    }

    private fun commitAndClose(
        write: AttachmentWrite,
        prepared: PreparedBlob,
        context: kotlin.coroutines.CoroutineContext,
        trace: ResourceTrace?,
        state: CommitState,
    ): AttachmentMetadata {
        var original: Throwable? = null
        try {
            return commitPrepared(write, prepared, context, trace, state)
        } catch (failure: Throwable) {
            original = failure
            throw failure
        } finally {
            try {
                closePrepared(prepared, state, original, trace)
            } finally {
                state.closed = true
            }
        }
    }

    private fun commitPrepared(
        write: AttachmentWrite,
        prepared: PreparedBlob,
        context: kotlin.coroutines.CoroutineContext,
        trace: ResourceTrace?,
        state: CommitState,
    ): AttachmentMetadata {
        try {
            val result = phase(trace, ResourcePhase.DATABASE) {
                val committed = database.transaction { registerPrepared(write, prepared, context, trace) }
                // The transaction has returned: its commit is the irreversible success boundary.
                state.committed = committed
                committed
            }
            try {
                faults.hit(ResourceCut.AFTER_COMMIT_CLEANUP, prepared.size)
                settle(trace, prepared)
            } catch (failure: Exception) {
                trace?.recoverableCleanup(failure)
            }
            return result
        } catch (failure: Throwable) {
            if (state.committed == null) {
                try {
                    settle(trace, prepared)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    private fun SqlOperations.registerPrepared(
        write: AttachmentWrite,
        prepared: PreparedBlob,
        context: kotlin.coroutines.CoroutineContext,
        trace: ResourceTrace?,
    ): AttachmentMetadata {
        context.ensureActive()
        val edit = write.workspace
        val current = edit?.let { workspaceEditRow(it) }
        val existing = attachmentByHash(prepared.hash)
        validateWorkspaceHash(edit, existing)
        if (current?.storage?.sha256 == prepared.hash) return current.metadata()
        val shouldBlob = prepared.size >= AttachmentService.INLINE_LIMIT || existing?.storage?.blobHash != null
        val previousPath = if (shouldBlob) blobPath(prepared.hash) else null
        val path = if (shouldBlob) phase(trace, ResourcePhase.PUBLISH) { blobs.publish(prepared, previousPath) } else null
        context.ensureActive()
        faults.hit(ResourceCut.BEFORE_DATABASE, prepared.size)
        val now = clock.now()
        val result = if (edit == null && existing != null) {
            reuse(existing, write.source.name, now)
        } else {
            storeFile(write, prepared, path, now)
        }
        faults.hit(ResourceCut.BEFORE_COMMIT, prepared.size)
        context.ensureActive()
        return result
    }

    private fun SqlOperations.storeFile(write: AttachmentWrite, prepared: PreparedBlob, path: String?, now: Instant): AttachmentMetadata {
        val request = write.source
        val id = write.workspace?.id ?: newId("att")
        val inlineBytes = if (path == null) Files.readAllBytes(prepared.path) else null
        val value = PreparedAttachment(
            identity = AttachmentIdentity(id, newId("res")),
            name = request.name,
            mediaType = request.mediaType,
            hash = prepared.hash,
            size = prepared.size,
            inlineBytes = inlineBytes,
            blobPath = path,
            nowMillis = now.toEpochMilli(),
        )
        val resourceId = registerResource(value)
        if (write.workspace?.expected == null) {
            insertFile(this, value, resourceId, now)
        } else {
            repointWorkspaceFile(value, resourceId, now)
        }
        return (attachmentById(id) ?: corruptAttachment()).metadata()
    }

    private fun closePrepared(prepared: PreparedBlob, state: CommitState, original: Throwable?, trace: ResourceTrace?) {
        if (state.committed != null) {
            try {
                prepared.close()
            } catch (failure: Exception) {
                trace?.recoverableCleanup(failure)
            }
        } else {
            try {
                prepared.close()
            } catch (cleanup: Throwable) {
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun settle(trace: ResourceTrace?, prepared: PreparedBlob) {
        if (!prepared.pending) return
        phase(trace, ResourcePhase.CLEANUP) {
            faults.hit(ResourceCut.BEFORE_SETTLE, prepared.size)
            val settled = database.transaction { blobs.settle(prepared) { hash, path -> hasBlobOwner(hash, path) } }
            if (!settled) trace?.recoverableCleanup(ownershipDiagnostic())
        }
    }

    override suspend fun get(id: String, includeDeleted: Boolean): AttachmentContent? {
        val row = database.attachmentById(id) ?: return null
        if (row.times.deletedAt != null && !includeDeleted) return null
        val result = readAttachment(database, row, blobs)
        if (result.status != AttachmentVerification.VALID) corruptAttachment()
        return AttachmentContent(row.metadata(), result.bytes ?: corruptAttachment())
    }

    override suspend fun copyTo(
        id: String,
        output: OutputStream,
        includeDeleted: Boolean,
        onProgress: (Long) -> Unit,
    ): AttachmentMetadata? {
        val context = currentCoroutineContext()
        val trace = context[ResourceTrace]
        return phase(trace, ResourcePhase.READ) {
            context.ensureActive()
            val row = database.attachmentById(id) ?: return@phase null
            if (row.times.deletedAt != null && !includeDeleted) return@phase null
            if (copyAttachment(database, row, blobs, output, { context.ensureActive() }, onProgress) != AttachmentVerification.VALID) {
                corruptAttachment()
            }
            row.metadata()
        }
    }

    override suspend fun resolve(resourceId: ResourceId): AttachmentMetadata? = database.attachmentByResource(resourceId.value)?.metadata()

    override suspend fun rename(id: String, name: String): AttachmentMetadata = mutate(id, name, null)
    override suspend fun delete(id: String): AttachmentMetadata = mutate(id, null, true)
    override suspend fun restore(id: String): AttachmentMetadata = mutate(id, null, false)

    override suspend fun verify(id: String): AttachmentVerification {
        val row = database.attachmentById(id) ?: return AttachmentVerification.NOT_FOUND
        if (row.times.deletedAt != null) return AttachmentVerification.DELETED
        return try {
            copyAttachment(database, row, blobs, DISCARD, {}, {})
        } catch (failure: com.deepstudent.core.common.error.DstuException) {
            if (failure.error.code == "ATTACHMENT_CORRUPT") AttachmentVerification.HASH_MISMATCH else throw failure
        }
    }

    override fun page(area: WorkspaceFileArea, afterId: String?, limit: Int): List<AttachmentMetadata> =
        database.visibleFiles(area, afterId, limit)

    override fun stat(id: String): AttachmentMetadata? = database.visibleFile(id)?.metadata()

    override suspend fun read(id: String, output: OutputStream, check: () -> Unit): AttachmentMetadata? {
        val allowed = stat(id) ?: return null
        check()
        return copyTo(id, output, onProgress = { check() })?.takeIf { it == allowed }
    }

    override suspend fun write(request: WorkspaceFileWrite, input: InputStream): AttachmentMetadata {
        require(WorkspaceFileIds.isOwned(request.id)) { "not a workspace file" }
        validateWorkspaceTitle(request.source.name)
        return save(AttachmentWrite(request.source, request), input, {})
    }

    override suspend fun renameWorkspace(id: String, title: String): AttachmentMetadata {
        require(WorkspaceFileIds.isOwned(id)) { "not a workspace file" }
        validateWorkspaceTitle(title)
        return mutate(id, title, null, workspace = true)
    }

    override suspend fun deleteWorkspace(id: String) {
        require(WorkspaceFileIds.isOwned(id)) { "not a workspace file" }
        mutate(id, null, true, workspace = true)
    }

    private fun SqlOperations.blobPath(hash: String): String? = query(
        "SELECT relative_path FROM blobs WHERE hash=?",
        listOf(text(hash)),
    ) { it.text(0) }.singleOrNull()

    private fun SqlOperations.reuse(row: AttachmentRow, name: String, now: Instant): AttachmentMetadata {
        if (copyAttachment(this, row, blobs, DISCARD, {}, {}) != AttachmentVerification.VALID) corruptAttachment()
        if (row.times.deletedAt != null) {
            execute(
                "UPDATE files SET deleted_at=NULL,status='active',name=?,updated_at=? WHERE id=?",
                listOf(text(name), text(timestamp(now)), text(row.id)),
            )
        }
        return (attachmentById(row.id) ?: corruptAttachment()).metadata()
    }

    private suspend fun mutate(id: String, name: String?, deleting: Boolean?, workspace: Boolean = false): AttachmentMetadata {
        val context = currentCoroutineContext()
        return database.transaction {
            context.ensureActive()
            val row = (if (workspace) visibleFile(id) else attachmentById(id)) ?: attachmentMissing()
            val now = timestamp(clock.now())
            when (deleting) {
                true -> softDelete(this, row, now)
                false -> restoreRow(this, row, now)
                null -> renameRow(this, row, name ?: "", now)
            }
            context.ensureActive()
            (attachmentById(id) ?: attachmentMissing()).metadata()
        }
    }
}

/** Refuse removal for any durable direct or indirect reference, including deletion journals. */
private fun validateWorkspaceHash(edit: WorkspaceFileWrite?, existing: AttachmentRow?) {
    if (edit != null && existing != null && existing.id != edit.id) resourceConflict()
}

private data class AttachmentWrite(val source: StreamAttachmentRequest, val workspace: WorkspaceFileWrite? = null)

private class CommitState {
    var committed: AttachmentMetadata? = null
    var closed: Boolean = false
}

private fun validateRequest(request: StreamAttachmentRequest, limit: Int) {
    val size = request.expectedSize
    if (request.name.isBlank() || request.name.length > 1024 || '\u0000' in request.name) invalidSize()
    if (request.mediaType.isBlank() || request.mediaType.length > 255) invalidSize()
    if (size != null && (size < 0 || size > limit)) invalidSize()
}

private fun SqlOperations.hasBlobOwner(hash: String, path: String): Boolean {
    fun present(sql: String, args: List<SqlValue>): Boolean = query(sql, args) { it.long(0) }.isNotEmpty()
    val one = listOf(text(hash))
    return present("SELECT 1 FROM blobs WHERE hash=? OR relative_path=? LIMIT 1", listOf(text(hash), text(path))) ||
        present("SELECT 1 FROM resources WHERE external_hash=? LIMIT 1", one) ||
        present(
            "SELECT 1 FROM files WHERE blob_hash=? OR compressed_blob_hash=? OR sha256=? LIMIT 1",
            listOf(text(hash), text(hash), text(hash)),
        ) ||
        present("SELECT 1 FROM vfs_index_units WHERE image_blob_hash=? LIMIT 1", one) ||
        present("SELECT 1 FROM __blob_deletion_queue WHERE hash=? LIMIT 1", one) ||
        present("SELECT 1 FROM __file_deletion_journal WHERE target_kind='blob' AND entity_key=? LIMIT 1", one)
}

private fun <T> phase(trace: ResourceTrace?, phase: ResourcePhase, action: () -> T): T =
    if (trace == null) action() else trace.phase(phase, action)

private fun softDelete(tx: SqlOperations, row: AttachmentRow, now: String) {
    if (row.times.deletedAt != null) return
    tx.execute(
        "UPDATE files SET status='deleted',deleted_at=?,updated_at=? WHERE id=? AND deleted_at IS NULL",
        listOf(text(now), text(now), text(row.id)),
    )
}

private fun restoreRow(tx: SqlOperations, row: AttachmentRow, now: String) {
    if (row.times.deletedAt == null) return
    tx.execute(
        "UPDATE files SET status='active',deleted_at=NULL,updated_at=? WHERE id=? AND deleted_at IS NOT NULL",
        listOf(text(now), text(row.id)),
    )
}

private fun renameRow(tx: SqlOperations, row: AttachmentRow, name: String, now: String) {
    if (row.times.deletedAt != null) attachmentMissing()
    tx.execute("UPDATE files SET name=?,updated_at=? WHERE id=?", listOf(text(name), text(now), text(row.id)))
}

private fun insertFile(tx: SqlOperations, input: PreparedAttachment, resourceId: String, now: Instant) {
    val instant = timestamp(now)
    tx.execute(
        """INSERT INTO files (id,resource_id,blob_hash,type,name,mime_type,size,content_hash,sha256,
            file_name,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(),
        listOf(
            text(input.identity.id), text(resourceId), nullable(if (input.blobPath == null) null else input.hash),
            text(if (input.mediaType.startsWith("image/", true)) "image" else "file"), text(input.name),
            text(input.mediaType), SqlValue.Integer(input.size), text(input.hash), text(input.hash),
            text(input.name), text(instant), text(instant),
        ),
    )
}

private fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
internal fun timestamp(now: Instant): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(now)

private val DISCARD = object : OutputStream() {
    override fun write(value: Int) { }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { }
}
