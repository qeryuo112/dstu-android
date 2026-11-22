package com.deepstudent.app.backup

import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.backup.backend.ContinuityGrantExpectation
import com.deepstudent.services.backup.backend.ContinuityGrantSignedBytes
import com.deepstudent.services.backup.backend.ContinuityGrantVerifier
import com.deepstudent.services.backup.backend.ContinuityValidation
import com.deepstudent.services.backup.backend.ContinuityValidator
import com.deepstudent.services.backup.backend.VerifiedContinuityGrant
import com.deepstudent.services.backup.data.BackupIdentityAttempt
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.time.Instant

internal class VerifiedContinuityRestore(
    val grant: VerifiedContinuityGrant,
    val matched: ContinuityValidation.Matched,
    val aliases: Map<String, String>,
) {
    val attempt get() = BackupIdentityAttempt(grant.operationId, grant.targetInstallationId,
        grant.repository.instanceId, grant.writerId, grant.archiveSha256, grant.sidecarSha256)
}

internal fun verifyContinuityRestore(
    archiveFile: File, archive: ContinuityArchive, stored: StoredContinuityEvidence, now: Instant, cancel: () -> Unit,
): VerifiedContinuityRestore {
    val files = stored.files
    if (Digests.sha256Hex(files.keyDer) != files.fingerprint) failContinuity(ContinuityRestoreError.AUTHORITY_PIN_MISMATCH)
    val expectation = ContinuityGrantExpectation(files.keyDer, stored.repository.baseUrl,
        stored.target.installationId, stored.target.operationId)
    val grant = Files.newInputStream(archiveFile.toPath(), READ, NOFOLLOW_LINKS).use { stream ->
        ContinuityGrantVerifier.verify(ContinuityGrantSignedBytes(files.grant, files.signature), expectation, archive.evidence,
            stream, files.sidecar, now, cancel)
    }
    requireRepository(grant, stored.repository)
    val matched = ContinuityValidator.validate(files.sidecar, grant.trust(archive.evidence, now), cancel)
    if (matched !is ContinuityValidation.Matched) failContinuity(ContinuityRestoreError.SIDECAR_MISMATCH)
    return VerifiedContinuityRestore(grant, matched, grant.bind(matched, now))
}

private fun requireRepository(grant: VerifiedContinuityGrant, paired: PairedContinuityRepository) {
    if (grant.repository.instanceId != paired.instanceId || !grant.repository.provider.equals("webdav", ignoreCase = true)) {
        failContinuity(ContinuityRestoreError.REPOSITORY_CHANGED)
    }
    if (grant.uploadSeq >= MAX_WIRE_SEQUENCE) failContinuity(ContinuityRestoreError.SEQUENCE_LIMIT)
    // Frozen WebDAV hints can include username and provider root separately; a rotated credential's
    // username need not match. The exact hint is independently signed and checked against the sidecar.
    // The actual network location is always from the independently paired canonical base URL.
}

private const val MAX_WIRE_SEQUENCE = 999_999_999_999L

internal enum class ContinuityRestoreError(val userMessage: String) {
    NOT_PAIRED("请先用新凭据配对到原同步仓库。"),
    REPOSITORY_CHANGED("接管授权与当前配对的仓库不一致。"),
    AUTHORITY_PIN_MISMATCH("管理员公钥与独立提供的指纹不一致。"),
    SIDECAR_MISMATCH("身份文件与备份或授权不一致。"),
    SEQUENCE_LIMIT("旧写者的序列已经达到同步格式上限，无法继续发布。"),
    ALREADY_USED("这份接管授权已经使用。"),
    INPUT_INVALID("无法验证所选备份和接管授权。"),
}

internal class ContinuityRestoreException(val error: ContinuityRestoreError) : Exception(error.name)
internal fun failContinuity(error: ContinuityRestoreError): Nothing = throw ContinuityRestoreException(error)
