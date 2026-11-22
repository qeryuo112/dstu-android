package com.deepstudent.app.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ContinuityDocument(val label: String, val budget: Int) {
    ARCHIVE("旧移动备份 ZIP", 0),
    SIDECAR("迁移身份文件", ContinuityRestoreJournal.SIDECAR_LIMIT),
    GRANT("管理员授权正文", ContinuityRestoreJournal.GRANT_LIMIT),
    SIGNATURE("授权签名", ContinuityRestoreJournal.SIGNATURE_LIMIT),
    KEY("管理员公钥（DER）", ContinuityRestoreJournal.KEY_LIMIT),
}

internal interface ContinuityEntryActions {
    suspend fun issueTarget(): ContinuityTarget
    suspend fun renewTarget(): ContinuityTarget
    suspend fun review(documents: Map<ContinuityDocument, Uri>, fingerprint: String): ContinuityReview
    suspend fun schedule(documents: Map<ContinuityDocument, Uri>, fingerprint: String, reviewed: ContinuityReview): Boolean
}

internal class SafContinuityEntryActions(private val context: Context, private val restore: BackupRestoreCoordinator) : ContinuityEntryActions {
    override suspend fun issueTarget(): ContinuityTarget = withContext(Dispatchers.IO) { restore.issueContinuityTarget() }
    override suspend fun renewTarget(): ContinuityTarget = withContext(Dispatchers.IO) { restore.issueContinuityTarget(renew = true) }

    override suspend fun review(documents: Map<ContinuityDocument, Uri>, fingerprint: String): ContinuityReview =
        restore.reviewContinuity(documents.getValue(ContinuityDocument.ARCHIVE), load(documents, fingerprint))

    override suspend fun schedule(documents: Map<ContinuityDocument, Uri>, fingerprint: String, reviewed: ContinuityReview): Boolean =
        restore.scheduleContinuity(documents.getValue(ContinuityDocument.ARCHIVE), load(documents, fingerprint), reviewed)

    private suspend fun load(documents: Map<ContinuityDocument, Uri>, fingerprint: String): ContinuityFiles = withContext(Dispatchers.IO) {
        val cancel = { coroutineContext.ensureActive() }
        ContinuityFiles(read(documents, ContinuityDocument.SIDECAR, cancel), read(documents, ContinuityDocument.GRANT, cancel),
            read(documents, ContinuityDocument.SIGNATURE, cancel), read(documents, ContinuityDocument.KEY, cancel), fingerprint)
    }

    private fun read(files: Map<ContinuityDocument, Uri>, kind: ContinuityDocument, cancel: () -> Unit): ByteArray {
        val uri = files.getValue(kind)
        check(uri.scheme == "content")
        return checkNotNull(context.contentResolver.openInputStream(uri)).use { readContinuityBytes(it, kind.budget, cancel) }
    }
}
