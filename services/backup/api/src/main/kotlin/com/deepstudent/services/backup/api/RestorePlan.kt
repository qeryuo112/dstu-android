package com.deepstudent.services.backup.api

enum class DomainAction {
    STAGE_IN_SLOT,
    /** Retained as evidence only; never activate as the local audit database. */
    PRESERVE_INACTIVE,
    /** Verified in the authenticated package, but not copied into Android's data slot. */
    RETAIN_IN_SOURCE,
    EXPLICIT_TRUST_REQUIRED,
    NO_ANDROID_TARGET,
    NO_CONTENT,
    EXCLUDED,
    FAILED,
}

data class DomainPreview(
    val id: String,
    val status: CoverageStatus,
    val action: DomainAction,
    val fileCount: Long,
    val totalSize: Long,
)

/** Both paths are archive/slot-relative, never absolute filesystem paths. */
data class PlannedFile(
    val archivePath: String,
    val slotRelativePath: String,
    val size: Long,
    val sha256: String,
)

enum class RestoreProblemCode {
    INVALID_MANIFEST, UNSUPPORTED_VERSION, INVALID_ARCHIVE_PATH, INVALID_ARCHIVE_ENTRY,
    DUPLICATE_ARCHIVE_ENTRY, DUPLICATE_DECLARATION, MISSING_ARCHIVE_ENTRY, UNDECLARED_ARCHIVE_ENTRY,
    INVALID_COVERAGE, MISSING_DOMAIN, INCONSISTENT_COMPONENTS, INVALID_DATABASE,
    UNSUPPORTED_SCHEMA, INVALID_KEY_POLICY, INCREMENTAL_UNSUPPORTED, PARTIAL_NOT_SLOTABLE,
    SIZE_MISMATCH, HASH_MISMATCH, UNVERIFIED_FILE, TARGET_CONFLICT,
    SPACE_OVERFLOW, INSUFFICIENT_SPACE, EXPLICIT_TRUST_REQUIRED, NO_ANDROID_TARGET,
    ARCHIVE_IO,
}

/** Detail is diagnostic context, not safe for unredacted application logs. */
data class RestoreProblem(
    val code: RestoreProblemCode,
    val detail: String,
    val domainId: String? = null,
    val archivePath: String? = null,
)

/** Untrusted declarations from a legacy mobile manifest, not authenticated source identity. */
data class LegacyImportReport(
    val manifestVersion: String,
    val appVersion: String,
    val schemaVersions: Map<String, Long>,
)

/** Set by a trusted caller after authenticating a package; never read from a manifest. */
enum class RestoreAdmission { STANDARD, AUTHENTICATED_PACKAGE }

data class RestorePlanSource(
    val backupId: String? = null,
    val manifestSha256: String? = null,
    val legacyImport: LegacyImportReport? = null,
    val admission: RestoreAdmission = RestoreAdmission.STANDARD,
)

/**
 * A preflight result permits at most staging. It is never an authorization to switch slots:
 * the executor must re-read the manifest and every planned byte, validate the staged data,
 * and satisfy its transactional switch contract. Failure at any step preserves the old slot.
 */
data class RestorePlan(
    val snapshotKind: SnapshotKind?,
    val source: RestorePlanSource,
    val domains: List<DomainPreview>,
    val files: List<PlannedFile>,
    val requiredBytes: Long?,
    val problems: List<RestoreProblem>,
    /** True only on a real preflight result, never on a pure planner preview. */
    val canStage: Boolean,
) {
    val backupId get() = source.backupId
    val manifestSha256 get() = source.manifestSha256
    val legacyImport get() = source.legacyImport
    /** Executor must preserve existing Android-only and omitted/skipped state, never infer deletion. */
    val requiresPreservingUnmappedContent: Boolean get() = true
    /** Preflight alone can never authorize publication. */
    val canSwitchSlot: Boolean get() = false
}
