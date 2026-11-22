package com.deepstudent.services.resources.data.saf

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Separate test-APK UID. These constants name only synthetic private files; the provider never
 * reads external storage or a user account. No static initialization or instrumentation arguments
 * are needed: DocumentsUI may start this process independently of either test runner.
 */
private object GrantFixture {
    const val AUTHORITY = "com.deepstudent.services.resources.data.test.s207grant"
    const val ROOT = "s207-root"
    const val ROOT_LABEL = "DSTU S207 Test Only"
    const val SOURCE = "s207-fixture-source.bin"
    const val EXPORT = "s207-fixture-export.bin"
    const val MIME = "application/octet-stream"
    const val APP = "com.deepstudent.app"
    const val SIZE = 2 * 1024 * 1024 + 31
    const val OP = "s207.operation"
    const val UID_PROOF = "s207.uidProof"
    val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    fun uri(id: String) = DocumentsContract.buildDocumentUri(AUTHORITY, id)
    fun file(dir: File, id: String): File {
        require(id == SOURCE || id == EXPORT)
        return File(dir, id)
    }
    fun seed(dir: File) {
        file(dir, SOURCE).outputStream().buffered().use { out ->
            val block = ByteArray(8192) { index -> ((index * 31 + 7) and 255).toByte() }
            var left = SIZE
            while (left > 0) {
                val count = minOf(left, block.size)
                out.write(block, 0, count)
                left -= count
            }
        }
    }
}

/** Fixed, private synthetic local documents; callable by DocumentsUI and URI grant holders only. */
class GrantFixtureProvider : DocumentsProvider() {
    private val dir get() = checkNotNull(context).filesDir

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        row(projection ?: ROOT_COLUMNS, mapOf(
            DocumentsContract.Root.COLUMN_ROOT_ID to GrantFixture.ROOT,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID to GrantFixture.ROOT,
            DocumentsContract.Root.COLUMN_TITLE to GrantFixture.ROOT_LABEL,
            DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_CREATE,
        ))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DOC_COLUMNS
        return MatrixCursor(columns).apply { addRow(documentRow(documentId, columns)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != GrantFixture.ROOT) throw FileNotFoundException()
        val columns = projection ?: DOC_COLUMNS
        return MatrixCursor(columns).apply {
            listOf(GrantFixture.SOURCE, GrantFixture.EXPORT).forEach { id ->
                if (GrantFixture.file(dir, id).isFile) addRow(documentRow(id, columns))
            }
        }
    }

    override fun getDocumentType(documentId: String): String = when (documentId) {
        GrantFixture.ROOT -> DocumentsContract.Document.MIME_TYPE_DIR
        GrantFixture.SOURCE, GrantFixture.EXPORT -> GrantFixture.MIME
        else -> throw FileNotFoundException()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == GrantFixture.ROOT &&
            (documentId == GrantFixture.SOURCE || documentId == GrantFixture.EXPORT) &&
            GrantFixture.file(dir, documentId).isFile

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        if (parentDocumentId != null && parentDocumentId != GrantFixture.ROOT) throw FileNotFoundException()
        val path = when (childDocumentId) {
            GrantFixture.ROOT -> listOf(GrantFixture.ROOT)
            GrantFixture.SOURCE, GrantFixture.EXPORT -> {
                if (!GrantFixture.file(dir, childDocumentId).isFile) throw FileNotFoundException()
                listOf(GrantFixture.ROOT, childDocumentId)
            }
            else -> throw FileNotFoundException()
        }
        return DocumentsContract.Path(if (parentDocumentId == null) GrantFixture.ROOT else null, path)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != GrantFixture.ROOT || mimeType != GrantFixture.MIME || displayName != GrantFixture.EXPORT) {
            throw FileNotFoundException("not a synthetic fixture destination")
        }
        if (!GrantFixture.file(dir, GrantFixture.EXPORT).createNewFile()) throw FileNotFoundException("destination exists")
        checkNotNull(context).contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(GrantFixture.AUTHORITY, GrantFixture.ROOT), null)
        return GrantFixture.EXPORT
    }

    override fun deleteDocument(documentId: String) {
        if (documentId != GrantFixture.EXPORT || !GrantFixture.file(dir, documentId).delete()) throw FileNotFoundException()
        checkNotNull(context).contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(GrantFixture.AUTHORITY, GrantFixture.ROOT), null)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId != GrantFixture.SOURCE && documentId != GrantFixture.EXPORT) throw FileNotFoundException()
        val file = GrantFixture.file(dir, documentId)
        if (!file.isFile) throw FileNotFoundException()
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> if (documentId == GrantFixture.EXPORT) {
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            } else throw FileNotFoundException("source is read only")
            else -> throw FileNotFoundException("only narrow modes")
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    private fun documentRow(id: String, columns: Array<out String>): Array<Any?> {
        val root = id == GrantFixture.ROOT
        val file = if (root) null else GrantFixture.file(dir, id)
        if (!root && file?.isFile != true) throw FileNotFoundException()
        val flags = if (root) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else if (id == GrantFixture.EXPORT) {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        } else 0
        val values = mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (root) GrantFixture.ROOT_LABEL else id,
            DocumentsContract.Document.COLUMN_MIME_TYPE to getDocumentType(id),
            DocumentsContract.Document.COLUMN_FLAGS to flags,
            DocumentsContract.Document.COLUMN_SIZE to file?.length(),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to file?.lastModified(),
        )
        return columns.map { values[it] }.toTypedArray()
    }

    private fun row(columns: Array<out String>, values: Map<String, Any?>): Cursor =
        MatrixCursor(columns).apply { addRow(columns.map { values[it] }) }

    companion object {
        private val ROOT_COLUMNS = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)
        private val DOC_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}

/**
 * Explicit test-only grant source, NOT the system document picker. Android attaches the grants
 * when this separate-UID Activity returns its result. It only accepts the target app as caller and
 * has no parameter for arbitrary URI, path or data. PendingIntent proves the source UID via OS.
 */
class GrantFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingPackage != GrantFixture.APP) { finish(); return }
        val source = GrantFixture.uri(GrantFixture.SOURCE)
        val export = GrantFixture.uri(GrantFixture.EXPORT)
        @Suppress("DEPRECATION")
        val providerInfo = checkNotNull(packageManager.resolveContentProvider(GrantFixture.AUTHORITY, 0))
        val reply = Intent().putExtra(GrantFixture.UID_PROOF, PendingIntent.getActivity(
            this, 0, Intent(this, GrantFixtureActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )).putExtra("s207.providerUid", providerInfo.applicationInfo.uid)
            .putExtra("s207.providerPackage", providerInfo.packageName)
        when (intent.getStringExtra(GrantFixture.OP)) {
            "reset" -> {
                revokeFixtureDocuments(GrantFixture.SOURCE, GrantFixture.EXPORT)
                GrantFixture.file(filesDir, GrantFixture.EXPORT).delete()
                GrantFixture.seed(filesDir)
                contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(GrantFixture.AUTHORITY, GrantFixture.ROOT), null)
            }
            "status" -> {
                reply.putExtra("s207.sourceExists", GrantFixture.file(filesDir, GrantFixture.SOURCE).isFile)
                val destination = GrantFixture.file(filesDir, GrantFixture.EXPORT)
                reply.putExtra("s207.exportExists", destination.isFile)
                reply.putExtra("s207.exportSize", if (destination.isFile) destination.length() else -1L)
            }
            "probe" -> reply.data = source // no grant flags
            "read" -> reply.setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            "read-persist" -> reply.setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            "new-rw-persist" -> {
                check(GrantFixture.file(filesDir, GrantFixture.EXPORT).createNewFile())
                reply.setData(export).addFlags(GrantFixture.rw or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            "new-w" -> {
                check(GrantFixture.file(filesDir, GrantFixture.EXPORT).createNewFile())
                reply.setData(export).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            "revoke-export" -> reply.putExtra("s207.outgoingAfter", revokeFixtureDocuments(GrantFixture.EXPORT))
            "revoke" -> reply.putExtra("s207.outgoingAfter", revokeFixtureDocuments(GrantFixture.SOURCE, GrantFixture.EXPORT))
            else -> { finish(); return }
        }
        setResult(RESULT_OK, reply)
        finish()
    }

    private fun revokeFixtureDocuments(vararg documentIds: String): Int {
        // Use the document owner's API, including persisted and read/write grants. The client
        // must be local: no exported test-only provider command or permission override is needed.
        checkNotNull(contentResolver.acquireContentProviderClient(GrantFixture.AUTHORITY)).use { client ->
            val provider = checkNotNull(client.localContentProvider as? GrantFixtureProvider)
            documentIds.forEach(provider::revokeDocumentPermission)
        }
        val revoked = documentIds.map(GrantFixture::uri).toSet()
        return contentResolver.outgoingPersistedUriPermissions.count { it.uri in revoked }
    }
}
