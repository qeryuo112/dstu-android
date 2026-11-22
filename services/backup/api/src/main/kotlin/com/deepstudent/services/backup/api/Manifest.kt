package com.deepstudent.services.backup.api

enum class SnapshotKind { FULL, PARTIAL_OVERLAY, LEGACY_CANDIDATE }
enum class CoverageStatus { COMPLETE, EMPTY, ABSENT, EXCLUDED, FAILED }
enum class RestoreScope { ACTIVE_DATA_SPACE, APPLICATION_DATA, USER_HOME }
enum class RestoreTrust { DATA, EXPLICIT, UNTRUSTED_EXECUTABLE }
enum class KeyPolicy { INCLUDED_LOCAL, INCLUDED_ENCRYPTED, NOT_PRESENT, EXCLUDED_PORTABLE, LEGACY_UNKNOWN }

data class BackupFile(val path: String, val size: Long, val sha256: String, val databaseId: String? = null)

data class BackupAsset(
    val assetType: String, val relativePath: String, val originalPath: String,
    val size: Long, val checksum: String?, val isDirectory: Boolean,
)

/** Frozen desktop security declarations, not a safe Android destination. */
data class DomainSecurity(
    val executable: Boolean, val optional: Boolean, val encrypted: Boolean,
    val restoreTarget: String, val restoreScope: RestoreScope, val restoreTrust: RestoreTrust,
)

data class DomainCoverage(
    val status: CoverageStatus, val paths: List<String>, val fileCount: Long,
    val totalSize: Long, val security: DomainSecurity,
) {
    val executable get() = security.executable
    val optional get() = security.optional
    val encrypted get() = security.encrypted
    val restoreTarget get() = security.restoreTarget
    val restoreScope get() = security.restoreScope
    val restoreTrust get() = security.restoreTrust
}

data class CoverageLedger(val version: Long, val domains: Map<String, DomainCoverage>)

data class ManifestIdentity(
    val version: String, val appVersion: String, val createdAt: String, val platform: String,
    val backupId: String, val snapshotEpoch: String,
)
data class ManifestPayload(val schemaVersions: Map<String, Long>, val files: List<BackupFile>, val assets: List<BackupAsset>)
data class SnapshotDeclaration(
    val isIncremental: Boolean, val kind: SnapshotKind, val requiredComponents: List<String>,
    val includedComponents: List<String>, val keyPolicy: KeyPolicy,
    /** A non-null base cannot represent a self-contained legacy mobile full snapshot. */
    val hasIncrementalBase: Boolean = false,
)

data class BackupManifest(
    val identity: ManifestIdentity, val payload: ManifestPayload,
    val snapshot: SnapshotDeclaration, val coverage: CoverageLedger?,
) {
    val version get() = identity.version
    val appVersion get() = identity.appVersion
    val createdAt get() = identity.createdAt
    val platform get() = identity.platform
    val backupId get() = identity.backupId
    val snapshotEpoch get() = identity.snapshotEpoch
    val schemaVersions get() = payload.schemaVersions
    val files get() = payload.files
    val assets get() = payload.assets
    val isIncremental get() = snapshot.isIncremental
    val snapshotKind get() = snapshot.kind
    val requiredComponents get() = snapshot.requiredComponents
    val includedComponents get() = snapshot.includedComponents
    val keyPolicy get() = snapshot.keyPolicy
}
