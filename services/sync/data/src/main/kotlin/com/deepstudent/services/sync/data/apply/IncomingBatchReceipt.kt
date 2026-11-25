package com.deepstudent.services.sync.data.apply

import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import java.nio.ByteBuffer
import java.security.MessageDigest

internal fun SqlOperations.incomingReceiptSetup() = execute(
    "CREATE TABLE IF NOT EXISTS __android_sync_apply_receipts(" +
        "instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,batch_sha256 TEXT NOT NULL," +
        "PRIMARY KEY(instance,device,seq))",
)

internal fun SqlOperations.incomingVerifyReceipt(instance: String, device: String, seq: Long, digest: String): Boolean {
    val previous = query(
        "SELECT batch_sha256 FROM __android_sync_apply_receipts WHERE instance=? AND device=? AND seq=?",
        listOf(SqlValue.Text(instance), SqlValue.Text(device), SqlValue.Integer(seq)),
    ) { it.text(0) }.singleOrNull() ?: return false
    check(previous == digest) { "sync batch changed after partial commit" }
    return true
}

internal fun SqlOperations.incomingInsertReceipt(instance: String, device: String, seq: Long, digest: String) = execute(
    "INSERT INTO __android_sync_apply_receipts(instance,device,seq,batch_sha256) VALUES(?,?,?,?)",
    listOf(SqlValue.Text(instance), SqlValue.Text(device), SqlValue.Integer(seq), SqlValue.Text(digest)),
)

internal fun incomingBatchDigest(changes: List<ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(changes.size).array())
    changes.forEach { change ->
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(change.size).array())
        digest.update(change)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
