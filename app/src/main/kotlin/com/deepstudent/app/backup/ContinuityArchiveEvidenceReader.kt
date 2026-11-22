package com.deepstudent.app.backup

import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.backend.ArchivePreflight
import com.deepstudent.services.backup.backend.ContinuityArchiveEvidence
import com.deepstudent.services.backup.backend.ManifestCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

internal data class ContinuityArchive(val evidence: ContinuityArchiveEvidence, val plan: RestorePlan) {
    override fun toString() = "ContinuityArchive(redacted)"
}

/** Read only owned private bytes; the parser never receives a caller-supplied ZIP hash or manifest. */
internal fun inspectContinuityArchive(file: File, cancel: () -> Unit): ContinuityArchive {
    check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
    val before = archiveDigest(file, cancel)
    val (plan, manifest) = CheckedZipArchive(file).use { archive ->
        val checked = ArchivePreflight.inspect(archive, file.usableSpace, cancel).plan
        check(checked.canStage && checked.legacyImport != null)
        val bytes = archive.open("manifest.json").use { readContinuityBytes(it, 16 * 1024 * 1024, cancel) }
        check(Digests.sha256Hex(bytes) == checked.manifestSha256)
        checked to ManifestCodec.decode(bytes.decodeToString(throwOnInvalidSequence = true), cancel)
    }
    check(before == archiveDigest(file, cancel))
    return ContinuityArchive(ContinuityArchiveEvidence(before.second, before.first, manifest), plan)
}

private fun archiveDigest(file: File, cancel: () -> Unit): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    val size = file.length()
    val input = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS)
    input.use { stream ->
        val bytes = ByteArray(128 * 1024)
        var remaining = size
        while (true) {
            cancel()
            val n = stream.read(bytes)
            if (n == -1) break
            check(n > 0 && n <= remaining)
            digest.update(bytes, 0, n)
            remaining -= n
        }
        check(remaining == 0L)
    }
    return size to Digests.toHex(digest.digest())
}
