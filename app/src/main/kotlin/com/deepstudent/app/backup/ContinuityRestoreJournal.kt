package com.deepstudent.app.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

internal data class ContinuityTarget(val installationId: String, val operationId: String) {
    override fun toString() = "ContinuityTarget(redacted)"
}

/** Repository identity established independently by the installation's existing pairing. */
internal data class PairedContinuityRepository(val baseUrl: String, val instanceId: String) {
    override fun toString() = "PairedContinuityRepository(redacted)"
}

internal class ContinuityFiles(
    val sidecar: ByteArray, val grant: ByteArray, val signature: ByteArray, val keyDer: ByteArray, val fingerprint: String,
)

internal class StoredContinuityEvidence(
    val files: ContinuityFiles, val target: ContinuityTarget, val repository: PairedContinuityRepository,
)

/** This marker is published only after the live remote claim, never from the archive itself. */
internal data class ContinuityClaimCheckpoint(val epochSecond: Long, val grantSha256: String, val witness: String) {
    override fun toString() = "ContinuityClaimCheckpoint(redacted)"
}

internal class ContinuityRestoreJournal(private val root: File) {
    private val request = root.resolve("continuity-target")
    private val sidecar = root.resolve("continuity-sidecar")
    private val signedGrant = root.resolve("continuity-grant")
    private val signature = root.resolve("continuity-signature")
    private val key = root.resolve("continuity-key")
    private val stamp = root.resolve("continuity-receipt")
    private val checkpoint = root.resolve("continuity-claim")
    private val claimIntent = root.resolve("continuity-claim-intent")
    private val completed = root.resolve("continuity-complete")

    fun target(): ContinuityTarget {
        val seen = readOptional(request, META_LIMIT)?.decodeToString(throwOnInvalidSequence = true)
        return if (seen == null) newTarget() else decodeTarget(seen)
    }

    fun issueTarget(): ContinuityTarget {
        val old = target()
        val done = readOptional(completed, META_LIMIT)?.decodeToString(throwOnInvalidSequence = true)?.let(::uuid)
        return if (done == old.operationId) saveTarget(ContinuityTarget(old.installationId, UUID.randomUUID().toString())) else old
    }

    fun renewTarget(): ContinuityTarget = saveTarget(ContinuityTarget(target().installationId, UUID.randomUUID().toString()))

    fun markCompleted(operationId: String) = write(completed, uuid(operationId).toByteArray(), META_LIMIT)

    fun isCompleted(operationId: String): Boolean =
        readOptional(completed, META_LIMIT)?.decodeToString(throwOnInvalidSequence = true) == operationId

    fun persist(evidence: StoredContinuityEvidence) {
        check(target() == evidence.target)
        val data = evidence.files
        write(sidecar, data.sidecar, SIDECAR_LIMIT)
        write(signedGrant, data.grant, GRANT_LIMIT)
        write(signature, data.signature, SIGNATURE_LIMIT)
        write(key, data.keyDer, KEY_LIMIT)
        val lines = listOf("DSTU-CONTINUITY-1", evidence.target.installationId, evidence.target.operationId,
            evidence.repository.instanceId, evidence.repository.baseUrl, data.fingerprint)
        check(lines.all(::safeLine))
        write(stamp, lines.joinToString("\n", postfix = "\n").toByteArray(), META_LIMIT)
    }

    fun load(operationId: String): StoredContinuityEvidence {
        val meta = read(stamp, META_LIMIT).decodeToString(throwOnInvalidSequence = true).split('\n')
        check(meta.size == 7 && meta.first() == "DSTU-CONTINUITY-1" && meta.last().isEmpty())
        check(meta[2] == operationId && meta[5].matches(hexDigest) && meta.dropLast(1).all(::safeLine))
        val recorded = ContinuityTarget(uuid(meta[1]), uuid(meta[2]))
        // The committed request may outlive a restart, but cannot be retargeted at another installation.
        check(target().installationId == recorded.installationId)
        return StoredContinuityEvidence(ContinuityFiles(read(sidecar, SIDECAR_LIMIT), read(signedGrant, GRANT_LIMIT),
            read(signature, SIGNATURE_LIMIT), read(key, KEY_LIMIT), meta[5]), recorded, PairedContinuityRepository(meta[4], meta[3]))
    }

    /** Durable uncertainty marker, written before a remote conditional PUT can be sent. */
    fun markClaimIntent(operationId: String, digest: String) {
        val id = uuid(operationId)
        check(target().operationId == id && digest.matches(hexDigest))
        val expected = "$id\n$digest\n"
        val previous = readOptional(claimIntent, META_LIMIT)?.decodeToString(throwOnInvalidSequence = true)
        check(previous == null || previous == expected)
        if (previous == null) write(claimIntent, expected.toByteArray(), META_LIMIT)
    }

    /** A confirmed checkpoint or an attempted PUT both require evidence-preserving recovery. */
    fun mightHaveClaim(operationId: String, digest: String): Boolean {
        val id = uuid(operationId)
        check(digest.matches(hexDigest))
        val intent = readOptional(claimIntent, META_LIMIT)?.decodeToString(throwOnInvalidSequence = true)
        check(intent == null || intent == "$id\n$digest\n")
        val confirmed = requireSameClaimedEvidence(digest)
        return intent != null || confirmed != null
    }

    fun markClaimed(claim: ContinuityClaimCheckpoint) {
        check(claim.epochSecond > 0 && claim.grantSha256.matches(hexDigest) && claim.witness.matches(hexDigest))
        write(checkpoint, "${claim.epochSecond}\n${claim.grantSha256}\n${claim.witness}\n".toByteArray(), META_LIMIT)
    }

    fun claimed(): ContinuityClaimCheckpoint? {
        val text = readOptional(checkpoint, META_LIMIT)?.decodeToString(throwOnInvalidSequence = true) ?: return null
        val lines = text.split('\n')
        check(lines.size == 4 && lines.last().isEmpty() && lines[1].matches(hexDigest) && lines[2].matches(hexDigest))
        val seconds = checkNotNull(lines[0].toLongOrNull())
        check(seconds > 0 && seconds.toString() == lines[0])
        return ContinuityClaimCheckpoint(seconds, lines[1], lines[2])
    }

    fun requireSameClaimedEvidence(digest: String): ContinuityClaimCheckpoint? = claimed()?.also {
        check(it.grantSha256 == digest)
    }

    fun cleanEvidence() {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        val removed = listOf(sidecar, signedGrant, signature, key, stamp, checkpoint, claimIntent).map { Files.deleteIfExists(it.toPath()) }
        Files.newDirectoryStream(root.toPath(), ".continuity-*.tmp").use { paths -> paths.forEach(Files::deleteIfExists) }
        if (removed.any { it }) syncRoot()
    }

    private fun newTarget() = saveTarget(ContinuityTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString()))

    private fun saveTarget(next: ContinuityTarget): ContinuityTarget {
        write(request, "${next.installationId}\n${next.operationId}\n".toByteArray(), META_LIMIT)
        return next
    }

    private fun decodeTarget(raw: String): ContinuityTarget {
        val fields = raw.split('\n')
        check(fields.size == 3 && fields.last().isEmpty())
        return ContinuityTarget(uuid(fields[0]), uuid(fields[1]))
    }

    private fun uuid(text: String): String {
        val id = UUID.fromString(text)
        check(id.version() == 4 && id.toString() == text)
        return text
    }

    private fun readOptional(file: File, limit: Int): ByteArray? =
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) null else read(file, limit)

    private fun read(file: File, limit: Int): ByteArray {
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        return Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { readContinuityBytes(it, limit) }
    }

    private fun write(file: File, bytes: ByteArray, limit: Int) {
        check(bytes.size in 1..limit)
        val existed = Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)
        Files.createDirectories(root.toPath())
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        if (!existed) FileChannel.open(checkNotNull(root.parentFile).toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
        val temp = Files.createTempFile(root.toPath(), ".continuity-", ".tmp").toFile()
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncRoot()
        } finally { Files.deleteIfExists(temp.toPath()) }
    }

    private fun syncRoot() = FileChannel.open(root.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }

    private fun safeLine(text: String): Boolean = text.isNotBlank() && text.none(Char::isISOControl)

    companion object {
        const val SIDECAR_LIMIT = 8 * 1024 * 1024
        const val GRANT_LIMIT = 1024 * 1024
        const val SIGNATURE_LIMIT = 512
        const val KEY_LIMIT = 4096
        private const val META_LIMIT = 8192
        private val hexDigest = Regex("[a-f0-9]{64}")
    }
}

internal fun readContinuityBytes(input: InputStream, limit: Int, cancel: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream()
    copyBounded(input, output, limit.toLong(), cancel)
    return output.toByteArray().also { check(it.isNotEmpty()) }
}
