package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*

/** The manifest is untrusted: these are format gates, not proof of the source or identity. */
internal object LegacyMobile {
    const val AUDIT_PATH = "databases/audit.db"
    const val AUDIT_TARGET = "legacy-import/audit.db"
    private val DISPLAY_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}")
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun validate(manifest: BackupManifest): List<RestoreProblem> = buildList {
        validateFormat(manifest, this)
        validateSchemaVersions(manifest, this)
        validateDisplayVersion(manifest, this)
    }

    private fun validateFormat(manifest: BackupManifest, problems: MutableList<RestoreProblem>) {
        if (manifest.platform != "mobile") problems += RestoreProblem(RestoreProblemCode.INVALID_MANIFEST, "Legacy import requires a mobile platform declaration")
        if (manifest.version != "3.0.0") problems += RestoreProblem(RestoreProblemCode.UNSUPPORTED_VERSION, "Legacy mobile requires manifest version 3.0.0")
        if (manifest.snapshotKind != SnapshotKind.FULL) problems += RestoreProblem(RestoreProblemCode.PARTIAL_NOT_SLOTABLE, "Legacy mobile requires a full snapshot")
        if (manifest.isIncremental || manifest.snapshot.hasIncrementalBase) problems += RestoreProblem(RestoreProblemCode.INCREMENTAL_UNSUPPORTED, "Legacy mobile incremental backup is unsupported")
        if (manifest.keyPolicy != KeyPolicy.NOT_PRESENT) problems += RestoreProblem(RestoreProblemCode.INVALID_KEY_POLICY, "Legacy mobile requires not_present key policy")
        if (manifest.coverage == null) problems += RestoreProblem(RestoreProblemCode.INVALID_COVERAGE, "Legacy mobile requires a coverage ledger")
    }

    private fun validateSchemaVersions(manifest: BackupManifest, problems: MutableList<RestoreProblem>) {
        val complete = manifest.schemaVersions.keys == FrozenDomains.databaseIds.toSet()
        val supported = manifest.schemaVersions.all { (id, version) -> version > 0 && version <= (FrozenDomains.latestSchema[id] ?: 0) }
        if (!complete || !supported) problems += RestoreProblem(RestoreProblemCode.UNSUPPORTED_SCHEMA, "Legacy mobile requires four supported database versions")
    }

    private fun validateDisplayVersion(manifest: BackupManifest, problems: MutableList<RestoreProblem>) {
        if (!DISPLAY_VERSION.matches(manifest.appVersion)) problems += RestoreProblem(RestoreProblemCode.INVALID_MANIFEST, "Unsafe legacy mobile app version")
    }

    fun report(manifest: BackupManifest): LegacyImportReport = LegacyImportReport(
        manifest.version, manifest.appVersion, manifest.schemaVersions.toMap(),
    )

    fun isInactiveAudit(manifest: BackupManifest, coverage: DomainCoverage): Boolean =
        coverage.paths == listOf(AUDIT_PATH) &&
            manifest.files.count { it.path == AUDIT_PATH && it.databaseId == null } == 1

    fun isDatabase(path: String): Boolean = path == AUDIT_PATH ||
        FrozenDomains.databaseIds.any { path == "$it.db" }

    fun validDatabase(size: Long, header: ByteArray): Boolean =
        size >= 512 && size % 512 == 0L && header.contentEquals(sqliteHeader)
}
