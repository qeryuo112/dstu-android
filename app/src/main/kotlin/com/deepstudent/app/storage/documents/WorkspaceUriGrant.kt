package com.deepstudent.app.storage.documents

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/** Recheck on use as well as on open. Holding a staged descriptor must not bypass URI revocation. */
internal class WorkspaceUriGrant private constructor(
    private val context: Context,
    private val pid: Int,
    private val uid: Int,
    private val document: Uri,
    private val treeDocument: Uri,
    private val key: WorkspaceDocumentKey,
    private val privacy: WorkspaceSharePolicy,
) {
    fun check(access: DocumentAccess) = check(access.permission)

    fun <T> access(permission: Int, action: () -> T): T = privacy.commit(key, uid) {
        check(permission)
        action()
    }

    fun check(permission: Int) {
        privacy.check(key, uid)
        val granted = PackageManager.PERMISSION_GRANTED
        if (uid == Process.myUid() || context.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, pid, uid) == granted) return
        if (context.checkUriPermission(document, pid, uid, permission) == granted) return
        if (context.checkUriPermission(treeDocument, pid, uid, permission) == granted) return
        throw SecurityException("workspace document grant is absent or revoked")
    }

    companion object {
        fun capture(context: Context, authority: String, key: WorkspaceDocumentKey, privacy: WorkspaceSharePolicy): WorkspaceUriGrant {
            val tree = DocumentsContract.buildTreeDocumentUri(authority, key.root.id)
            return WorkspaceUriGrant(
                context,
                Binder.getCallingPid(),
                Binder.getCallingUid(),
                DocumentsContract.buildDocumentUri(authority, key.documentId),
                DocumentsContract.buildDocumentUriUsingTree(tree, key.documentId),
                key,
                privacy,
            )
        }
    }
}

internal enum class DocumentAccess(val read: Boolean, val write: Boolean, val truncate: Boolean = false, val append: Boolean = false) {
    READ(true, false),
    WRITE(false, true, truncate = true),
    READ_WRITE(true, true),
    READ_WRITE_TRUNCATE(true, true, truncate = true),
    APPEND(false, true, append = true),
    ;

    val permission: Int get() =
        (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

    companion object {
        fun parse(mode: String) = when (mode) {
            "r" -> READ
            "w", "wt" -> WRITE
            "rw" -> READ_WRITE
            "rwt" -> READ_WRITE_TRUNCATE
            "wa" -> APPEND
            else -> throw FileNotFoundException("unsupported document access mode")
        }
    }
}
