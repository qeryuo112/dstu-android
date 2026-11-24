package com.deepstudent.app.storage.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.deepstudent.app.storage.AndroidOperationLogger
import java.io.FileNotFoundException

/** Android owns grant checks. We additionally fence each operation to a logical resource root. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private val privacy by lazy { WorkspaceSharePolicy.open(requireNotNull(context)) }
    private val owner by lazy { WorkspaceDocumentsBackend.open(requireNotNull(context)) }
    private val descriptors by lazy { WorkspaceDocumentDescriptors(requireNotNull(context), owner, ::notifyDocument) }
    private val authority: String get() = "${requireNotNull(context).packageName}.documents.workspace"

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = rootCursor(projection).also { cursor ->
        DocumentRoot.entries.forEach { cursor.addRoot(it) }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documentCursor(projection).also { cursor ->
        checkVisible(documentId)
        val root = DocumentRoot.find(documentId)
        if (root != null) cursor.addDirectory(root) else cursor.addDocument(owner.query(key(documentId)) ?: missingDocument())
        val uri = DocumentsContract.buildDocumentUri(authority, documentId)
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val root = DocumentRoot.find(parentDocumentId) ?: missingDocument()
        return documentCursor(projection).also { cursor ->
            val outside = Binder.getCallingUid() != Process.myUid()
            owner.visit(root) { info -> if (!outside || !privacy.hidden(info.key)) cursor.addDocument(info) }
            cursor.setNotificationUri(requireNotNull(context).contentResolver, children(root))
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val root = DocumentRoot.find(parentDocumentId) ?: return false
        val target = WorkspaceDocumentKey.parse(documentId) ?: return false
        if (Binder.getCallingUid() != Process.myUid() && privacy.hidden(target)) return false
        return target.root == root && owner.query(target) != null
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        checkVisible(childDocumentId)
        val root = DocumentRoot.find(childDocumentId) ?: key(childDocumentId).let { target ->
            owner.query(target)?.key?.root ?: missingDocument()
        }
        if (parentDocumentId != null && parentDocumentId != root.id) missingDocument()
        val path = if (childDocumentId == root.id) listOf(root.id) else listOf(root.id, childDocumentId)
        return DocumentsContract.Path(if (parentDocumentId == null) root.id else null, path)
    }

    override fun getDocumentType(documentId: String): String {
        checkVisible(documentId)
        return if (DocumentRoot.find(documentId) !=
            null
        ) {
            Document.MIME_TYPE_DIR
        } else {
            owner.query(key(documentId))?.mime ?: missingDocument()
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (DocumentRoot.find(parentDocumentId) != DocumentRoot.WORKSPACE) missingDocument()
        val reservation = owner.reserve(displayName, mimeType)
        notifyDocument(reservation.key)
        return reservation.key.documentId
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val key = key(documentId)
        privacy.commit(key, Binder.getCallingUid()) { owner.rename(key, displayName) }
        notifyDocument(key)
        return null // returning null preserves the ID and its grants
    }

    override fun deleteDocument(documentId: String) {
        val key = key(documentId)
        privacy.commit(key, Binder.getCallingUid()) { owner.delete(key) }
        notifyDocument(key)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val key = key(documentId)
        val access = DocumentAccess.parse(mode)
        if (access.write && !key.root.writable) throw FileNotFoundException("attachment is read only")
        val grant = WorkspaceUriGrant.capture(requireNotNull(context), authority, key, privacy)
        grant.check(access)
        signal?.throwIfCanceled()
        val identity = Binder.clearCallingIdentity()
        return try {
            val info = owner.query(key) ?: missingDocument()
            descriptors.open(info, access, signal, grant)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Revoke our own boundary first; framework cleanup can be incomplete on some devices. */
    internal fun revokeExternalAccess(documentId: String) {
        check(Binder.getCallingUid() == Process.myUid()) { "only the workspace owner can withdraw sharing" }
        val key = key(documentId)
        owner.query(key) ?: missingDocument()
        privacy.deny(key)
        runCatching { revokeDocumentPermission(documentId) }
            .onFailure { AndroidOperationLogger.warn("workspace.documents.framework_revoke_failed") }
        AndroidOperationLogger.info("workspace.documents.external_revoked")
        notifyDocument(key)
    }

    private fun checkVisible(documentId: String) {
        WorkspaceDocumentKey.parse(documentId)?.let { privacy.check(it, Binder.getCallingUid()) }
    }

    private fun key(id: String): WorkspaceDocumentKey = WorkspaceDocumentKey.parse(id) ?: missingDocument()

    private fun children(root: DocumentRoot) = DocumentsContract.buildChildDocumentsUri(authority, root.id)

    private fun notifyDocument(key: WorkspaceDocumentKey) {
        val resolver = requireNotNull(context).contentResolver
        resolver.notifyChange(children(key.root), null)
        resolver.notifyChange(DocumentsContract.buildDocumentUri(authority, key.documentId), null)
    }
}

private val rootColumns =
    arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES)
private val documentColumns = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_FLAGS,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED,
)

private fun rootCursor(projection: Array<out String>?) = MatrixCursor(projection ?: rootColumns)
private fun documentCursor(projection: Array<out String>?) = MatrixCursor(projection ?: documentColumns)

private fun MatrixCursor.addRoot(root: DocumentRoot) = addFields(
    mapOf(
        Root.COLUMN_ROOT_ID to root.id,
        Root.COLUMN_DOCUMENT_ID to root.id,
        Root.COLUMN_TITLE to root.label,
        Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or if (root.writable) Root.FLAG_SUPPORTS_CREATE else 0),
        Root.COLUMN_MIME_TYPES to "*/*",
    ),
)

private fun MatrixCursor.addDirectory(root: DocumentRoot) = addFields(
    mapOf(
        Document.COLUMN_DOCUMENT_ID to root.id,
        Document.COLUMN_DISPLAY_NAME to root.label,
        Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
        Document.COLUMN_FLAGS to if (root.writable) Document.FLAG_DIR_SUPPORTS_CREATE else 0,
    ),
)

private fun MatrixCursor.addDocument(info: WorkspaceDocumentInfo) = addFields(
    mapOf(
        Document.COLUMN_DOCUMENT_ID to info.key.documentId,
        Document.COLUMN_DISPLAY_NAME to info.title,
        Document.COLUMN_MIME_TYPE to info.mime,
        Document.COLUMN_SIZE to info.size,
        Document.COLUMN_LAST_MODIFIED to info.modified,
        Document.COLUMN_FLAGS to if (info.key.root.writable) EDITABLE_FLAGS else 0,
    ),
)

private fun MatrixCursor.addFields(values: Map<String, Any?>) = addRow(columnNames.map(values::get))

private const val EDITABLE_FLAGS = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
