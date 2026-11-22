package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*

internal class CoverageValidator(private val manifest: BackupManifest, private val entries: Map<String, Declared>) {
    private val problems = mutableListOf<RestoreProblem>()
    private val covered = mutableSetOf<String>()
    private fun issue(code: RestoreProblemCode, message: String, id: String? = null) { problems += RestoreProblem(code, message, domainId = id) }

    fun validate(): List<RestoreProblem> {
        val coverage = manifest.coverage
        if (coverage == null || coverage.version != 1L) {
            issue(RestoreProblemCode.INVALID_COVERAGE, "Missing or unsupported coverage ledger"); return problems
        }
        if (coverage.domains.keys != FrozenDomains.byId.keys) issue(RestoreProblemCode.MISSING_DOMAIN, "Coverage domain set does not match frozen registry")
        for (spec in FrozenDomains.all) coverage.domains[spec.id]?.let { validateDomain(spec, it) }
        if (entries.keys.any { it !in covered }) issue(RestoreProblemCode.INVALID_COVERAGE, "Declared file is not covered by a domain")
        validateComponents()
        validateKeyPolicy(coverage.domains["crypto"]?.status)
        return problems
    }
    private fun validateDomain(spec: DomainSpec, c: DomainCoverage) {
        if (!metadataMatches(spec, c)) issue(RestoreProblemCode.INVALID_COVERAGE, "Domain security metadata differs from frozen registry", spec.id)
        validateStatus(spec, c)
        val unique = mutableSetOf<String>()
        var total = 0L
        for (path in c.paths) {
            if (!spec.owns(path) || !unique.add(path)) issue(RestoreProblemCode.INVALID_COVERAGE, "Duplicate or out-of-domain path", spec.id)
            covered += path
            total = addCoveredSize(spec.id, path, total)
        }
        if (c.totalSize != total) issue(RestoreProblemCode.INVALID_COVERAGE, "Coverage byte total disagrees", spec.id)
    }
    private fun metadataMatches(spec: DomainSpec, c: DomainCoverage): Boolean =
        c.executable == spec.executable && c.encrypted == spec.encrypted && c.optional == spec.optional &&
            c.restoreTarget == spec.desktopTarget && c.restoreScope == spec.scope && c.restoreTrust == spec.trust
    private fun validateStatus(spec: DomainSpec, c: DomainCoverage) {
        val pathsAllowed = c.status in setOf(CoverageStatus.COMPLETE, CoverageStatus.FAILED)
        if ((!pathsAllowed && c.paths.isNotEmpty()) || (c.status == CoverageStatus.COMPLETE && c.paths.isEmpty()) || c.fileCount != c.paths.size.toLong() || c.totalSize < 0) issue(RestoreProblemCode.INVALID_COVERAGE, "Domain status and file count disagree", spec.id)
        if (isIncompleteFull(spec, c)) issue(RestoreProblemCode.MISSING_DOMAIN, "Full snapshot domain is incomplete", spec.id)
    }
    private fun isIncompleteFull(spec: DomainSpec, c: DomainCoverage): Boolean = manifest.snapshotKind == SnapshotKind.FULL &&
        (c.status == CoverageStatus.EXCLUDED || c.status == CoverageStatus.FAILED || (spec.required && c.status != CoverageStatus.COMPLETE))
    private fun addCoveredSize(id: String, path: String, current: Long): Long {
        val entry = entries[path]
        if (entry == null) { issue(RestoreProblemCode.INVALID_COVERAGE, "Coverage refers to undeclared file", id); return current }
        return try { Math.addExact(current, entry.size) } catch (_: ArithmeticException) {
            issue(RestoreProblemCode.SPACE_OVERFLOW, "Coverage size overflow", id); current
        }
    }
    private fun validateKeyPolicy(crypto: CoverageStatus?) {
        when (manifest.keyPolicy) {
            KeyPolicy.INCLUDED_LOCAL -> if (crypto != CoverageStatus.COMPLETE) issue(RestoreProblemCode.INVALID_KEY_POLICY, "Included keys lack crypto coverage")
            KeyPolicy.NOT_PRESENT -> if (crypto != CoverageStatus.ABSENT && crypto != CoverageStatus.EMPTY) issue(RestoreProblemCode.INVALID_KEY_POLICY, "Key policy disagrees with crypto coverage")
            KeyPolicy.INCLUDED_ENCRYPTED -> issue(RestoreProblemCode.INVALID_KEY_POLICY, "Encrypted backup requires a separate unseal flow")
            KeyPolicy.EXCLUDED_PORTABLE, KeyPolicy.LEGACY_UNKNOWN -> if (manifest.snapshotKind == SnapshotKind.FULL) issue(RestoreProblemCode.INVALID_KEY_POLICY, "Full snapshot has no usable key policy")
        }
    }
    private fun validateComponents() {
        val expectedRequired = if (manifest.snapshotKind == SnapshotKind.FULL) FrozenDomains.byId.keys else emptySet()
        val included = mutableSetOf<String>()
        manifest.files.forEach { fileComponent(it)?.let(included::add) }
        manifest.assets.forEach { included += "asset-root:${it.assetType}" }
        manifest.coverage?.domains?.forEach { (id, c) -> if (c.status in setOf(CoverageStatus.COMPLETE, CoverageStatus.EMPTY, CoverageStatus.ABSENT)) included += id }
        if (!sameComponents(manifest.requiredComponents, expectedRequired) || !sameComponents(manifest.includedComponents, included)) issue(RestoreProblemCode.INCONSISTENT_COMPONENTS, "Component declarations disagree with coverage and files")
    }
    private fun sameComponents(actual: List<String>, expected: Set<String>) = actual.toSet() == expected && actual.size == expected.size
    private fun fileComponent(file: BackupFile): String? = when {
        file.databaseId != null -> "database:${file.databaseId}"
        file.path.startsWith("workspaces/") -> "workspace:${file.path.removePrefix("workspaces/").substringBefore('/')}"
        file.path.startsWith("assets/") -> "asset-root:${file.path.removePrefix("assets/").substringBefore('/')}"
        else -> null
    }
}
