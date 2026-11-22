package com.deepstudent.services.backup.data

import com.deepstudent.services.backup.api.ArchiveEntry
import com.deepstudent.services.backup.api.ArchiveEntryKind
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.CoverageStatus
import com.deepstudent.services.backup.api.PlannedFile
import com.deepstudent.services.backup.api.RestoreArchive
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.SnapshotKind

internal const val MAX_RESTORE_FILES = 100_000
internal const val MAX_RESTORE_BYTES = 20L * 1024 * 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
internal val HASH_PATTERN = Regex("[a-fA-F0-9]{64}")
internal val DATABASE_IDS = setOf("vfs", "chat_v2", "mistakes", "llm_usage")
private const val LEGACY_AUDIT_SOURCE = "databases/audit.db"
private const val LEGACY_AUDIT_TARGET = "legacy-import/audit.db"

internal fun isSafePath(path: String): Boolean {
    if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > 4096 || path.startsWith('/')) return false
    if (path.any(::forbiddenPathCharacter)) return false
    return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

private fun forbiddenPathCharacter(char: Char): Boolean = char == '\\' || char == ':' || char.code < 32 || char.code == 127

internal fun checkedSum(total: Long, amount: Long, max: Long): Long {
    if (amount < 0 || amount > max || total > max - amount) throw RestoreFailure(RestoreError.LIMIT_EXCEEDED)
    return total + amount
}

/** The planner is trusted application code, but raw manifest paths are never target authority. */
internal fun checkPlan(plan: RestorePlan) {
    if (plan.snapshotKind != SnapshotKind.FULL || !plan.canStage || plan.problems.isNotEmpty() ||
        plan.manifestSha256?.matches(HASH_PATTERN) != true ||
        plan.files.size > MAX_RESTORE_FILES || !plan.requiresPreservingUnmappedContent) {
        throw RestoreFailure(RestoreError.INVALID_PLAN)
    }
    PlanRoutes(plan).validate()
}

private class PlanRoutes(private val plan: RestorePlan) {
    private val domains = plan.domains.groupBy { it.id }
    private val sources = mutableSetOf<String>()
    private val targets = mutableSetOf<String>()
    private var bytes = 0L

    fun validate() {
        if (domains.values.any { it.size != 1 }) throw RestoreFailure(RestoreError.INVALID_PLAN)
        plan.files.forEach(::validateFile)
        if (targets.any { path -> generateSequence(path.parentPath(), String::parentPath).any { it in targets } }) {
            throw RestoreFailure(RestoreError.INVALID_PLAN)
        }
    }

    private fun validateFile(file: PlannedFile) {
        if (!isSafePath(file.archivePath) || !isSafePath(file.slotRelativePath) || !file.sha256.matches(HASH_PATTERN)) {
            throw RestoreFailure(RestoreError.INVALID_PLAN)
        }
        if (!sources.add(file.archivePath) || !targets.add(file.slotRelativePath)) throw RestoreFailure(RestoreError.INVALID_PLAN)
        bytes = checkedSum(bytes, file.size, MAX_RESTORE_BYTES)
        validateDomain(file)
    }

    private fun validateDomain(file: PlannedFile) {
        if (plan.legacyImport != null && file.archivePath == LEGACY_AUDIT_SOURCE && file.slotRelativePath == LEGACY_AUDIT_TARGET) {
            requireCompleteDomain("audit", DomainAction.PRESERVE_INACTIVE)
            return
        }
        val domainId = domainFor(file) ?: throw RestoreFailure(RestoreError.UNTRUSTED_TARGET)
        requireCompleteDomain(domainId, DomainAction.STAGE_IN_SLOT)
    }

    private fun requireCompleteDomain(domainId: String, action: DomainAction) {
        val domain = domains[domainId]?.singleOrNull() ?: throw RestoreFailure(RestoreError.INVALID_PLAN)
        if (domain.status != CoverageStatus.COMPLETE || domain.action != action) {
            throw RestoreFailure(RestoreError.INVALID_PLAN)
        }
    }
}

private fun String.parentPath(): String? = substringBeforeLast('/', "").takeUnless(String::isEmpty)

private fun domainFor(file: PlannedFile): String? {
    for (id in DATABASE_IDS) {
        if (file.archivePath == "$id.db" && file.slotRelativePath == "databases/$id.db") return "database:$id"
    }
    if (!file.slotRelativePath.startsWith("vfs_blobs/")) return null
    if (file.archivePath == file.slotRelativePath || file.archivePath == "assets/${file.slotRelativePath}") {
        return "asset-root:vfs_blobs"
    }
    return null
}

internal fun checkCatalog(archive: RestoreArchive, plan: RestorePlan) {
    val entries = archive.inspect().entries
    if (entries.size > MAX_RESTORE_FILES + 10_000) throw RestoreFailure(RestoreError.LIMIT_EXCEEDED)
    val names = indexCatalog(entries)
    checkAncestors(names)
    if (names["manifest.json"] != ArchiveEntryKind.FILE || plan.files.any { names[it.archivePath] != ArchiveEntryKind.FILE }) {
        throw RestoreFailure(RestoreError.MISSING_ENTRY)
    }
}

private fun indexCatalog(entries: List<ArchiveEntry>): Map<String, ArchiveEntryKind> {
    val names = mutableMapOf<String, ArchiveEntryKind>()
    var declaredSize = 0L
    for (entry in entries) {
        val path = if (entry.kind == ArchiveEntryKind.DIRECTORY) entry.path.removeSuffix("/") else entry.path
        if (!isSafePath(path) || entry.kind !in setOf(ArchiveEntryKind.FILE, ArchiveEntryKind.DIRECTORY)) {
            throw RestoreFailure(RestoreError.UNSAFE_ARCHIVE)
        }
        if (names.put(path, entry.kind) != null) throw RestoreFailure(RestoreError.UNSAFE_ARCHIVE)
        entry.size?.let { declaredSize = checkedSum(declaredSize, it, MAX_RESTORE_BYTES + MAX_MANIFEST_BYTES) }
    }
    return names
}

private fun checkAncestors(names: Map<String, ArchiveEntryKind>) {
    for (path in names.keys) {
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (names[path.substring(0, slash)] == ArchiveEntryKind.FILE) throw RestoreFailure(RestoreError.UNSAFE_ARCHIVE)
            slash = path.indexOf('/', slash + 1)
        }
    }
}
