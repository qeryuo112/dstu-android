package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*

/** Validate all untrusted declarations before opening any named payload. */
internal fun inspectDeclarations(manifest: BackupManifest, catalog: ArchiveCatalog, mobile: Boolean = false): Inspection =
    DeclarationValidator(manifest, catalog, mobile).inspect()

private class DeclarationValidator(
    private val manifest: BackupManifest, private val catalog: ArchiveCatalog, private val mobile: Boolean,
) {
    private val problems = mutableListOf<RestoreProblem>()
    private val declared = linkedMapOf<String, Declared>()
    private val dbIds = mutableSetOf<String>()
    private var sum = 0L

    fun inspect(): Inspection {
        validateHeader()
        manifest.files.forEach(::validateFile)
        manifest.assets.forEach(::validateAsset)
        problems += CatalogValidator(catalog, declared, mobile).validate()
        problems += CoverageValidator(manifest, declared).validate()
        if (manifest.snapshotKind == SnapshotKind.FULL && dbIds != FrozenDomains.databaseIds.toSet()) issue(RestoreProblemCode.INVALID_DATABASE, "Full snapshot requires all four databases")
        return Inspection(declared, problems)
    }
    private fun issue(code: RestoreProblemCode, message: String, path: String? = null) {
        problems += RestoreProblem(code, message, archivePath = path)
    }
    private fun validateHeader() {
        if (!VERSION.matches(manifest.version) || manifest.snapshotKind == SnapshotKind.LEGACY_CANDIDATE) issue(RestoreProblemCode.UNSUPPORTED_VERSION, "Only v3 full and partial snapshots are supported")
        validateBackupId()
        if (manifest.isIncremental) issue(RestoreProblemCode.INCREMENTAL_UNSUPPORTED, "Incremental restore is unsupported")
        if (manifest.files.size.toLong() + manifest.assets.size > MAX_FILES) issue(RestoreProblemCode.INVALID_MANIFEST, "File count exceeds limit")
        if (manifest.snapshotKind == SnapshotKind.FULL && manifest.snapshotEpoch.isBlank()) issue(RestoreProblemCode.INVALID_MANIFEST, "Full snapshot lacks epoch")
        manifest.schemaVersions.forEach(::validateSchema)
    }
    private fun validateBackupId() {
        if (!BACKUP_ID.matches(manifest.backupId) || manifest.backupId.startsWith('.') || ".." in manifest.backupId) issue(RestoreProblemCode.INVALID_MANIFEST, "Invalid backup id")
    }
    private fun validateSchema(id: String, version: Long) {
        val latest = FrozenDomains.latestSchema[id]
        if (latest == null || version < 0 || version > latest) issue(RestoreProblemCode.UNSUPPORTED_SCHEMA, "Unknown or newer database schema")
    }
    private fun add(item: Declared) {
        if (!safePath(item.path)) issue(RestoreProblemCode.INVALID_ARCHIVE_PATH, "Unsafe declared path", item.path)
        if (item.size < 0) issue(RestoreProblemCode.INVALID_MANIFEST, "Negative file size", item.path)
        try { sum = Math.addExact(sum, item.size) } catch (_: ArithmeticException) { issue(RestoreProblemCode.SPACE_OVERFLOW, "Declared sizes overflow", item.path) }
        if (sum > MAX_BYTES) issue(RestoreProblemCode.INVALID_MANIFEST, "Declared size limit exceeded", item.path)
        if (!SHA256.matches(item.hash)) issue(RestoreProblemCode.INVALID_MANIFEST, "Missing or malformed SHA-256", item.path)
        if (declared.putIfAbsent(item.path, item) != null) issue(RestoreProblemCode.DUPLICATE_DECLARATION, "Duplicate declared path", item.path)
    }
    private fun validateFile(file: BackupFile) {
        add(Declared(file.path, file.size, file.sha256, file.databaseId, null))
        val id = file.databaseId
        if (id != null) validateDatabaseId(id, file.path)
        else if (file.path in FrozenDomains.databaseIds.map { "$it.db" }) issue(RestoreProblemCode.INVALID_DATABASE, "Database is missing its id", file.path)
    }
    private fun validateDatabaseId(id: String, path: String) {
        if (id !in FrozenDomains.databaseIds || path != "$id.db" || !dbIds.add(id)) issue(RestoreProblemCode.INVALID_DATABASE, "Invalid or duplicate database binding", path)
    }
    private fun validateAsset(asset: BackupAsset) {
        if (!safePath(asset.relativePath) || !safePath(asset.originalPath)) issue(RestoreProblemCode.INVALID_ARCHIVE_PATH, "Unsafe asset path", asset.relativePath)
        if (!assetTypeMatchesPath(asset)) issue(RestoreProblemCode.INVALID_MANIFEST, "Asset type and paths disagree", asset.relativePath)
        if (isWorkspaceDatabase(asset)) issue(RestoreProblemCode.INVALID_DATABASE, "Workspace SQLite requires a database snapshot", asset.relativePath)
        if (asset.originalPath != asset.relativePath.removePrefix("assets/")) issue(RestoreProblemCode.INVALID_MANIFEST, "Asset archive and destination suffixes disagree", asset.relativePath)
        if (!asset.isDirectory) add(Declared(asset.relativePath, asset.size, asset.checksum ?: "", null, asset.originalPath))
    }
    private fun assetTypeMatchesPath(asset: BackupAsset): Boolean {
        if (asset.assetType !in FrozenDomains.assetIds || !asset.originalPath.startsWith("${asset.assetType}/")) return false
        return asset.relativePath.startsWith("assets/${asset.assetType}/") ||
            (mobile && asset.assetType == "vfs_blobs" && asset.relativePath.startsWith("vfs_blobs/"))
    }
    private fun isWorkspaceDatabase(asset: BackupAsset) = asset.assetType == "workspaces" &&
        listOf(".db", "-wal", "-shm").any(asset.originalPath::endsWith)
}
