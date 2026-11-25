package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.transfer.DeviceManifests
import com.deepstudent.services.sync.backend.transfer.TransferChangeKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

private const val BACKUP_PREFIX = "backups/"
private const val BACKUP_MANIFEST_PREFIX = "manifests/"
private const val BACKUP_MANIFEST_BYTES = 4 * 1024 * 1024
private val backupId = Regex("[A-Za-z0-9_-]{1,128}")
private val checksum = Regex("[0-9a-fA-F]{64}")

internal class LegacyBackupPassword(private val provider: ObjectProvider, private val crypto: SyncCryptography) {
    suspend fun prove(password: CharArray): Boolean {
        val objects = provider.list("")
        val versions = backupVersions(objects)
        if (versions.isNotEmpty()) {
            proveVersion(password, versions)
            return false
        }
        if (objects.any { it.startsWith(BACKUP_PREFIX) }) proofRequired()
        val payloads = syncExamples(objects)
        if (payloads.isEmpty()) {
            requireEmpty(objects)
            return true
        }
        verifySyncExample(payloads.first(), password)
        return false
    }

    suspend fun recheckEmpty() = requireEmpty(provider.list(""))

    private fun requireEmpty(objects: List<String>) {
        if (objects.any {
                it !in emptyControlKeys && !it.startsWith("$MARKER_KEY.pending/") &&
                    !it.startsWith("data_governance/locks/")
            }
        ) {
            proofRequired()
        }
    }

    private suspend fun backupVersions(objects: List<String>): List<LegacyBackupVersion> {
        val sources = objects.filter { it.startsWith(BACKUP_MANIFEST_PREFIX) && it.endsWith(".json") }.sorted()
        val versions = linkedMapOf<String, LegacyBackupVersion>()
        fun merge(raw: ByteArray) {
            decodeVersions(raw).forEach { versions[it.id] = it }
            if (versions.size > 10_000) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        }
        for (source in sources) merge(provider.getBounded(source, BACKUP_MANIFEST_BYTES) ?: proofRequired())
        rootManifest()?.let(::merge)
        return versions.values.sortedWith(compareByDescending<LegacyBackupVersion> { it.timestamp }.thenByDescending { it.id }).take(2)
    }

    private suspend fun rootManifest(): ByteArray? {
        val primary = provider.getBounded("manifest.json", BACKUP_MANIFEST_BYTES) ?: return null
        try {
            decodeVersions(primary)
            return primary
        } catch (failure: SyncFailure) {
            if (failure.error != SyncError.ENCRYPTION_PROOF_REQUIRED) throw failure
            return provider.getBounded("manifest.json.bak", BACKUP_MANIFEST_BYTES) ?: throw failure
        }
    }

    private suspend fun proveVersion(password: CharArray, versions: List<LegacyBackupVersion>) {
        var firstFailure: SyncFailure? = null
        for (version in versions) {
            currentCoroutineContext().ensureActive()
            try {
                crypto.proveBackupPassword(provider, "$BACKUP_PREFIX${version.id}.zip", version.size, version.hash, password)
                return
            } catch (failure: SyncFailure) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        throw checkNotNull(firstFailure)
    }

    private suspend fun verifySyncExample(key: String, password: CharArray) {
        val raw = provider.get(key) ?: proofRequired()
        val plain = try {
            crypto.payload(password).use { it.decode(raw) }
        } catch (failure: SyncFailure) {
            if (failure.error == SyncError.ENCRYPTED_DATA_INVALID) throw SyncFailure(SyncError.ENCRYPTION_PASSWORD_INCORRECT)
            throw failure
        }
        try {
            if (key.startsWith("data_governance/manifests/")) {
                DeviceManifests.decodeForKey(key, plain)
            } else if (plain.isEmpty()) {
                proofRequired()
            }
        } finally {
            if (plain !== raw) plain.fill(0)
        }
    }

    private fun syncExamples(keys: List<String>): List<String> {
        val manifests = keys.filter { it.startsWith("data_governance/manifests/") && it.endsWith(".json") }
        return manifests.sorted() + keys.mapNotNull(TransferChangeKey::parse).sortedByDescending { it.sequence }.map { it.key }
    }

    private companion object {
        val emptyControlKeys = setOf(
            MARKER_KEY,
            LEASE_KEY_FOR_PROOF,
            PairingService.INSTANCE_KEY,
            PairingService.FORMAT_KEY,
            "manifest.json",
            "manifest.json.bak",
        )
        const val LEASE_KEY_FOR_PROOF = "$MARKER_KEY.lease"
    }
}

private data class LegacyBackupVersion(val id: String, val timestamp: Instant, val size: Long, val hash: String)

private fun decodeVersions(raw: ByteArray): List<LegacyBackupVersion> = try {
    val manifest = WireValues.parse(raw)
    WireValues.unsigned(manifest["version"], UInt.MAX_VALUE.toULong())
    Instant.parse(WireValues.text(manifest["updatedAt"]))
    val entries = WireValues.array(manifest["versions"])
    require(entries.size <= 10_000)
    val versions = entries.map { decodeVersion(WireValues.obj(it)) }
    val ids = versions.map { it.id }.toSet()
    val latest = WireValues.optionalText(manifest["latest"])
    require(ids.size == versions.size && if (ids.isEmpty()) latest == null else latest in ids)
    versions
} catch (_: Exception) {
    proofRequired()
}

private fun decodeVersion(row: JsonValue.Object): LegacyBackupVersion {
    val id = WireValues.text(row["id"]).also { require(backupId.matches(it)) }
    val timestamp = Instant.parse(WireValues.text(row["timestamp"]))
    val size = WireValues.unsigned(row["size"], Long.MAX_VALUE.toULong()).toLong()
    val hash = WireValues.text(row["checksum"]).also { require(checksum.matches(it)) }
    WireValues.text(row["deviceId"])
    return LegacyBackupVersion(id, timestamp, size, hash)
}

private fun proofRequired(): Nothing = throw SyncFailure(SyncError.ENCRYPTION_PROOF_REQUIRED)
