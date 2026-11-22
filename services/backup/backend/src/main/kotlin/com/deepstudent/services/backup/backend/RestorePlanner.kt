package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*
import java.text.Normalizer
import java.util.Locale

internal const val MAX_FILES = 100_000
internal const val MAX_BYTES = 20L * 1024 * 1024 * 1024
internal val SHA256 = Regex("[0-9a-fA-F]{64}")
internal val VERSION = Regex("3\\.[0-9]+\\.[0-9]+")
internal val BACKUP_ID = Regex("[A-Za-z0-9._-]{1,128}")

internal data class Declared(val path: String, val size: Long, val hash: String, val databaseId: String?, val original: String?)
internal data class Inspection(val entries: Map<String, Declared>, val problems: List<RestoreProblem>)

/** Pure decision logic. Input evidence must originate from a trusted byte-reading preflight. */
object RestorePlanner {
    fun plan(
        manifest: BackupManifest,
        catalog: ArchiveCatalog,
        shaEvidence: List<ShaEvidence>,
        availableBytes: Long,
        manifestSha256: String?,
    ): RestorePlan = planFor(manifest, catalog, shaEvidence, availableBytes, manifestSha256, mobile = false,
        admission = RestoreAdmission.STANDARD)

    /** Only the real archive preflight may select this profile; the public desktop planner stays unchanged. */
    internal fun planMobile(
        manifest: BackupManifest, catalog: ArchiveCatalog, shaEvidence: List<ShaEvidence>,
        availableBytes: Long, manifestSha256: String?,
    ): RestorePlan = planFor(manifest, catalog, shaEvidence, availableBytes, manifestSha256, mobile = true,
        admission = RestoreAdmission.STANDARD)

    internal fun planAuthenticated(
        manifest: BackupManifest, catalog: ArchiveCatalog, shaEvidence: List<ShaEvidence>,
        availableBytes: Long, manifestSha256: String?,
    ): RestorePlan = planFor(manifest, catalog, shaEvidence, availableBytes, manifestSha256,
        mobile = manifest.platform == "mobile", admission = RestoreAdmission.AUTHENTICATED_PACKAGE)

    private fun planFor(
        manifest: BackupManifest, catalog: ArchiveCatalog, shaEvidence: List<ShaEvidence>,
        availableBytes: Long, manifestSha256: String?, mobile: Boolean, admission: RestoreAdmission,
    ): RestorePlan {
        val checked = inspectDeclarations(manifest, catalog, mobile)
        val problems = checked.problems.toMutableList()
        val legacyProblems = if (mobile) LegacyMobile.validate(manifest) else emptyList()
        problems += legacyProblems
        val legacyEligible = mobile && legacyProblems.isEmpty()
        val retainDesktopOnly = admission == RestoreAdmission.AUTHENTICATED_PACKAGE && manifest.platform in desktopPlatforms
        val previews = previews(manifest, problems, legacyEligible, retainDesktopOnly)
        if (manifest.snapshotKind != SnapshotKind.FULL) {
            problems += RestoreProblem(RestoreProblemCode.PARTIAL_NOT_SLOTABLE, "Only a full snapshot can replace a slot")
        }
        val requiredBytes = budget(checked.entries.values, availableBytes, problems)
        validateEvidence(checked.entries, shaEvidence, problems)
        if (manifestSha256 == null || !SHA256.matches(manifestSha256)) {
            problems += RestoreProblem(RestoreProblemCode.UNVERIFIED_FILE, "Manifest bytes have not been verified")
        }
        val files = planFiles(manifest, checked.entries, problems, legacyEligible)
        val source = RestorePlanSource(manifest.backupId, manifestSha256?.lowercase(),
            if (legacyEligible) LegacyMobile.report(manifest) else null, admission)
        return RestorePlan(
            manifest.snapshotKind, source, previews.toList(),
            files.toList(), requiredBytes, problems.toList(), canStage = false,
        )
    }

    private fun previews(
        manifest: BackupManifest, problems: MutableList<RestoreProblem>, legacy: Boolean, retainDesktopOnly: Boolean,
    ): List<DomainPreview> =
        FrozenDomains.all.mapNotNull { spec ->
            val coverage = manifest.coverage?.domains?.get(spec.id) ?: return@mapNotNull null
            val action = previewAction(manifest, spec, coverage, legacy, retainDesktopOnly)
            domainProblem(spec.id, action)?.let(problems::add)
            DomainPreview(spec.id, coverage.status, action, coverage.fileCount, coverage.totalSize)
        }

    private fun previewAction(
        manifest: BackupManifest, spec: DomainSpec, coverage: DomainCoverage, legacy: Boolean, retainDesktopOnly: Boolean,
    ): DomainAction {
        if (legacy && spec.id == "audit" && coverage.status == CoverageStatus.COMPLETE &&
            LegacyMobile.isInactiveAudit(manifest, coverage)) return DomainAction.PRESERVE_INACTIVE
        val action = domainAction(spec, coverage)
        return if (retainDesktopOnly && action in inactiveActions) DomainAction.RETAIN_IN_SOURCE else action
    }

    private val desktopPlatforms = setOf("linux", "macos", "windows")
    private val inactiveActions = setOf(DomainAction.EXPLICIT_TRUST_REQUIRED, DomainAction.NO_ANDROID_TARGET)

    private fun domainAction(spec: DomainSpec, coverage: DomainCoverage): DomainAction = when (coverage.status) {
        CoverageStatus.EMPTY, CoverageStatus.ABSENT -> DomainAction.NO_CONTENT
        CoverageStatus.EXCLUDED -> DomainAction.EXCLUDED
        CoverageStatus.FAILED -> DomainAction.FAILED
        CoverageStatus.COMPLETE -> completeDomainAction(spec, coverage)
    }
    private fun completeDomainAction(spec: DomainSpec, coverage: DomainCoverage): DomainAction {
        if (spec.trust != RestoreTrust.DATA || spec.scope != RestoreScope.ACTIVE_DATA_SPACE) return DomainAction.EXPLICIT_TRUST_REQUIRED
        if (coverage.paths.any(FrozenDomains::requiresTrust)) return DomainAction.EXPLICIT_TRUST_REQUIRED
        if (spec.id.startsWith("database:") || spec.id == "asset-root:vfs_blobs") return DomainAction.STAGE_IN_SLOT
        return DomainAction.NO_ANDROID_TARGET
    }
    private fun domainProblem(id: String, action: DomainAction): RestoreProblem? = when (action) {
        DomainAction.EXPLICIT_TRUST_REQUIRED -> RestoreProblem(RestoreProblemCode.EXPLICIT_TRUST_REQUIRED, "Complete domain requires a separately trusted handler", id)
        DomainAction.NO_ANDROID_TARGET -> RestoreProblem(RestoreProblemCode.NO_ANDROID_TARGET, "Complete domain has no registered Android consumer", id)
        else -> null
    }

    private fun budget(entries: Collection<Declared>, available: Long, problems: MutableList<RestoreProblem>): Long? {
        var total = 0L
        try {
            entries.forEach { total = Math.addExact(total, it.size) }
            val required = Math.multiplyExact(total, 2L)
            if (available < 0 || available < required) problems += RestoreProblem(RestoreProblemCode.INSUFFICIENT_SPACE, "Insufficient or unknown free space")
            return required
        } catch (_: ArithmeticException) {
            problems += RestoreProblem(RestoreProblemCode.SPACE_OVERFLOW, "Restore disk budget overflow")
            return null
        }
    }

    private fun validateEvidence(entries: Map<String, Declared>, evidence: List<ShaEvidence>, problems: MutableList<RestoreProblem>) {
        val grouped = evidence.groupBy(ShaEvidence::path)
        for ((path, expected) in entries) {
            val actual = grouped[path]
            if (actual == null || actual.size != 1) {
                problems += RestoreProblem(RestoreProblemCode.UNVERIFIED_FILE, "Missing or ambiguous preflight evidence", archivePath = path)
                continue
            }
            val proof = actual.single()
            if (proof.observedSize != expected.size) problems += RestoreProblem(RestoreProblemCode.SIZE_MISMATCH, "Read size differs from declaration", archivePath = path)
            if (!SHA256.matches(proof.observedSha256) || !proof.observedSha256.equals(expected.hash, ignoreCase = true)) {
                problems += RestoreProblem(RestoreProblemCode.HASH_MISMATCH, "Read SHA-256 differs from declaration", archivePath = path)
            }
        }
        if (grouped.keys.any { it !in entries }) problems += RestoreProblem(RestoreProblemCode.UNVERIFIED_FILE, "Evidence contains undeclared entries")
    }

    private fun planFiles(manifest: BackupManifest, entries: Map<String, Declared>, problems: MutableList<RestoreProblem>, legacy: Boolean): List<PlannedFile> {
        val owners = ownerIndex(manifest)
        val audit = manifest.coverage?.domains?.get("audit")
        val preserveAudit = legacy && audit != null && audit.status == CoverageStatus.COMPLETE && LegacyMobile.isInactiveAudit(manifest, audit)
        val result = entries.mapNotNull { (path, entry) -> plannedFile(path, entry, owners[path].orEmpty(), problems, preserveAudit) }
        val ordered = result.sortedBy { collisionKey(it.slotRelativePath) }
        checkTargetCollisions(ordered, problems)
        return ordered
    }
    private fun plannedFile(path: String, entry: Declared, owners: List<DomainSpec>, problems: MutableList<RestoreProblem>, preserveAudit: Boolean): PlannedFile? {
        if (preserveAudit && path == LegacyMobile.AUDIT_PATH && owners.map { it.id } == listOf("audit")) {
            return PlannedFile(path, LegacyMobile.AUDIT_TARGET, entry.size, entry.hash.lowercase())
        }
        if (FrozenDomains.requiresTrust(path)) return null
        if (owners.any { it.scope != RestoreScope.ACTIVE_DATA_SPACE || it.trust != RestoreTrust.DATA }) return null
        val target = targetFor(path, entry, owners) ?: return null
        if (!safePath(target)) {
            problems += RestoreProblem(RestoreProblemCode.INVALID_ARCHIVE_PATH, "Unsafe slot-relative path", archivePath = path)
            return null
        }
        return PlannedFile(path, target, entry.size, entry.hash.lowercase())
    }
    private fun ownerIndex(manifest: BackupManifest): Map<String, List<DomainSpec>> {
        val result = mutableMapOf<String, MutableList<DomainSpec>>()
        for (spec in FrozenDomains.all) {
            val coverage = manifest.coverage?.domains?.get(spec.id) ?: continue
            if (coverage.status != CoverageStatus.COMPLETE) continue
            for (path in coverage.paths) {
                if (spec.owns(path)) result.getOrPut(path) { mutableListOf() }.add(spec)
            }
        }
        return result
    }
    private fun targetFor(path: String, entry: Declared, owners: List<DomainSpec>): String? {
        val id = entry.databaseId
        if (id != null && owners.any { it.id == "database:$id" }) return "databases/$id.db"
        if (owners.none { it.id == "asset-root:vfs_blobs" }) return null
        if (path.startsWith("assets/vfs_blobs/")) return path.removePrefix("assets/")
        if (path.startsWith("vfs_blobs/")) return path
        return null
    }
    private fun collisionKey(path: String) = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun checkTargetCollisions(ordered: List<PlannedFile>, problems: MutableList<RestoreProblem>) {
        val seen = mutableSetOf<String>()
        for (file in ordered) {
            val key = collisionKey(file.slotRelativePath)
            val duplicate = !seen.add(key)
            if (duplicate || hasFileParent(key, seen)) problems += RestoreProblem(RestoreProblemCode.TARGET_CONFLICT, "Multiple files conflict at the slot destination", archivePath = file.archivePath)
        }
    }
    private fun hasFileParent(key: String, seen: Set<String>): Boolean {
        var slash = key.indexOf('/')
        while (slash >= 0) {
            if (key.substring(0, slash) in seen) return true
            slash = key.indexOf('/', slash + 1)
        }
        return false
    }
}

internal fun safePath(path: String): Boolean {
    if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > 4096) return false
    if (path.startsWith('/') || '\\' in path || ':' in path) return false
    if (path.any { it.code < 32 || it.code == 127 }) return false
    return path.split('/').none(::invalidComponent)
}
private fun invalidComponent(part: String) = part.isEmpty() || part == "." || part == ".."
