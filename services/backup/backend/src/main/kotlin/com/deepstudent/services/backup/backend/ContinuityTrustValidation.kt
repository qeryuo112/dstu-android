package com.deepstudent.services.backup.backend

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonObject

private val CONTINUITY_SHA256 = Regex("[0-9a-f]{64}")
private val SCHEMA_IDS = setOf("vfs", "llm_usage", "chat_v2", "mistakes")
private const val SAFE_ID = 160

internal fun validateContinuityTrust(root: JsonObject, trust: ContinuityTrust) {
    continuityRequire(trust.writer.stopped && trust.writer.unchangedSinceSnapshot, ContinuityFailure.WRITER_NOT_CONFIRMED)
    val manifest = trust.archive.manifest
    continuityRequire(LegacyMobile.validate(manifest).isEmpty(), ContinuityFailure.TRUST_MISMATCH)
    continuityRequire(CONTINUITY_SHA256.matches(trust.archive.sha256), ContinuityFailure.TRUST_MISMATCH)
    continuityRequire(trust.archive.bytes in 1..JSON_SAFE_INTEGER, ContinuityFailure.TRUST_MISMATCH)
    // The transaction receipt embeds epoch as a path component; avoid ambiguous keys.
    safeIdentity(manifest.snapshotEpoch)
    safeIdentity(manifest.backupId)
    val snapshot = root.objectValue("snapshot")
    snapshot.shape(setOf("backup_id", "snapshot_epoch", "manifest_created_at", "zip_sha256", "zip_bytes", "manifest_version", "app_version", "schema_versions", "key_policy"))
    val equal = snapshot.text("zip_sha256") == trust.archive.sha256 &&
        snapshot.integer("zip_bytes", allowString = false, positive = true) == trust.archive.bytes &&
        snapshot.text("backup_id") == manifest.backupId && snapshot.text("snapshot_epoch") == manifest.snapshotEpoch &&
        snapshot.text("manifest_created_at") == manifest.createdAt && snapshot.text("manifest_version") == manifest.version &&
        snapshot.text("app_version") == manifest.appVersion && snapshot.text("key_policy") == "not_present"
    continuityRequire(equal, ContinuityFailure.TRUST_MISMATCH)
    val schema = snapshot.objectValue("schema_versions")
    schema.shape(SCHEMA_IDS)
    continuityRequire(manifest.schemaVersions.keys == SCHEMA_IDS, ContinuityFailure.TRUST_MISMATCH)
    continuityRequire(SCHEMA_IDS.all { schema.integer(it, allowString = false, positive = true) == manifest.schemaVersions[it] }, ContinuityFailure.TRUST_MISMATCH)
    validateInstallation(root.objectValue("source_installation"), trust)
    validateIdentity(root.objectValue("identity"), trust)
}

private fun validateInstallation(source: JsonObject, trust: ContinuityTrust) {
    val expected = trust.installation
    safeIdentity(expected.installationId)
    safeIdentity(expected.applicationId)
    val valid = source.text("applicationId") == expected.applicationId &&
        source.text("versionName") == expected.versionName && expected.versionName == trust.archive.manifest.appVersion &&
        source.integer("versionCode", allowString = false, positive = true) == expected.versionCode &&
        source.text("installationId") == expected.installationId
    continuityRequire(valid, ContinuityFailure.TRUST_MISMATCH)
}

private fun validateIdentity(identity: JsonObject, trust: ContinuityTrust) {
    identity.shape(setOf("mode", "instance_id", "device_id", "reason"))
    safeIdentity(trust.repository.instanceId)
    safeIdentity(trust.writer.deviceId)
    checkedText(trust.repository.provider)
    safeEndpointHint(trust.repository.endpointHint)
    val equal = identity.text("mode") == "continuity" && identity.text("reason") == "same-device-continuity" &&
        identity.text("instance_id") == trust.repository.instanceId && identity.text("device_id") == trust.writer.deviceId
    continuityRequire(equal, ContinuityFailure.TRUST_MISMATCH)
}

internal fun safeIdentity(value: String): String {
    checkedText(value)
    continuityRequire(value.length <= SAFE_ID && value != "." && value != "..")
    continuityRequire(value.none { it == '/' || it == '\\' || it.isWhitespace() })
    return value
}

/** Reject URL credentials and known secret-bearing hint forms; hints are never logged. */
private fun safeEndpointHint(value: String) {
    checkedText(value)
    continuityRequire(!value.contains('?') && !value.contains('#'))
    val forbidden = Regex("(?i)(^|[|;&])\\s*(password|passwd|secret|token|authorization|access[_-]?key|private[_-]?key)\\s*=")
    continuityRequire(!forbidden.containsMatchIn(value))
    for (part in value.split('|')) {
        val candidate = part.substringAfter('=', part)
        if (!candidate.contains("://")) continue
        val uri = try { URI(candidate) } catch (_: URISyntaxException) { throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE) }
        continuityRequire(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    }
}
