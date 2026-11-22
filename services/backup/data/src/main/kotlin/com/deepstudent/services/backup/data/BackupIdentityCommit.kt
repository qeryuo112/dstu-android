package com.deepstudent.services.backup.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private fun text(value: String) = SqlValue.Text(value)
private fun witnessHash(witness: String): String = Digests.sha256HexUtf8("dstu-continuity-commit-v1\u0000$witness")

/** Canonical length-delimited map: mapping content is as important as the archive hashes. */
internal fun backupIdentityAliasDigest(aliases: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("dstu-continuity-aliases-v1\u0000".toByteArray(StandardCharsets.UTF_8))
    for ((source, canonical) in aliases.toSortedMap()) {
        for (value in listOf(source, canonical)) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Reject pre-existing rows even if they claim to be the expected operation. Never skip staging. */
internal fun SqlReadOperations.backupIdentityCheckNoCommit(expected: BackupIdentityContinuityCommit) {
    val rows = query("SELECT 1 FROM __dstu_identity_commit WHERE operation_id=? OR takeover_key=? LIMIT 1",
        listOf(text(expected.attempt.operationId), text(expected.takeoverKey))) { it.long(0) }
    if (rows.isNotEmpty()) throw BackupIdentityException(BackupIdentityFailure.DUPLICATE_TAKEOVER)
}

internal fun SqlOperations.backupIdentityWriteCommit(expected: BackupIdentityContinuityCommit) {
    val attempt = expected.attempt
    execute("""INSERT INTO __dstu_identity_commit(operation_id,target_installation_id,instance_id,device_id,
        archive_sha256,sidecar_sha256,takeover_key,witness_sha256,aliases_sha256) VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent(),
        listOf(text(attempt.operationId), text(attempt.targetInstallationId), text(attempt.instanceId),
            text(attempt.writerId), text(attempt.archiveSha256), text(attempt.sidecarSha256),
            text(expected.takeoverKey), text(witnessHash(expected.witness)), text(expected.aliasesSha256)))
}

/** Compare the whole independently persisted attempt, witness and ordinary takeover receipt. */
internal fun SqlReadOperations.backupIdentityRequireCommit(expected: BackupIdentityContinuityCommit) {
    val attempt = expected.attempt
    val commit = query("""SELECT 1 FROM __dstu_identity_commit WHERE operation_id=? AND target_installation_id=?
        AND instance_id=? AND device_id=? AND archive_sha256=? AND sidecar_sha256=? AND takeover_key=? AND witness_sha256=? AND aliases_sha256=?""".trimIndent(),
        listOf(text(attempt.operationId), text(attempt.targetInstallationId), text(attempt.instanceId),
            text(attempt.writerId), text(attempt.archiveSha256), text(attempt.sidecarSha256),
            text(expected.takeoverKey), text(witnessHash(expected.witness)), text(expected.aliasesSha256))) { it.long(0) }
    val receipt = query("""SELECT 1 FROM __dstu_identity_receipt WHERE takeover_key=? AND instance_id=?
        AND device_id=? AND sidecar_sha256=?""".trimIndent(),
        listOf(text(expected.takeoverKey), text(attempt.instanceId), text(attempt.writerId), text(attempt.sidecarSha256))) { it.long(0) }
    if (commit != listOf(1L) || receipt != listOf(1L)) throw BackupIdentityException(BackupIdentityFailure.COMMIT_MISMATCH)
}
