package com.deepstudent.services.backup.backend

import com.deepstudent.services.backup.api.*

internal class CatalogValidator(
    private val catalog: ArchiveCatalog, private val entries: Map<String, Declared>, private val mobile: Boolean,
) {
    private val problems = mutableListOf<RestoreProblem>()
    private val indexed = mutableMapOf<String, ArchiveEntry>()
    private fun issue(code: RestoreProblemCode, message: String, path: String) { problems += RestoreProblem(code, message, archivePath = path) }

    fun validate(): List<RestoreProblem> {
        if (catalog.entries.size > MAX_FILES + 10_000) problems += RestoreProblem(RestoreProblemCode.INVALID_ARCHIVE_ENTRY, "Archive entry count exceeds limit")
        catalog.entries.forEach(::validateEntry)
        if (indexed["manifest.json"]?.kind != ArchiveEntryKind.FILE) issue(RestoreProblemCode.MISSING_ARCHIVE_ENTRY, "Missing root manifest", "manifest.json")
        entries.forEach(::validateDeclaredEntry)
        indexed.keys.forEach(::validateParents)
        return problems
    }
    private fun validateEntry(entry: ArchiveEntry) {
        val name = if (entry.kind == ArchiveEntryKind.DIRECTORY) entry.path.removeSuffix("/") else entry.path
        if (!safePath(name)) issue(RestoreProblemCode.INVALID_ARCHIVE_PATH, "Unsafe archive entry name", entry.path)
        if (entry.kind == ArchiveEntryKind.SYMBOLIC_LINK || entry.kind == ArchiveEntryKind.OTHER) issue(RestoreProblemCode.INVALID_ARCHIVE_ENTRY, "Archive has a non-regular entry", entry.path)
        if ((entry.size ?: 0) < 0) issue(RestoreProblemCode.INVALID_ARCHIVE_ENTRY, "Invalid archive entry size", entry.path)
        if (indexed.putIfAbsent(name, entry) != null) issue(RestoreProblemCode.DUPLICATE_ARCHIVE_ENTRY, "Duplicate archive entry", entry.path)
        if (isUndeclaredFile(entry, name)) issue(RestoreProblemCode.UNDECLARED_ARCHIVE_ENTRY, "Undeclared archive file", entry.path)
    }
    private fun isUndeclaredFile(entry: ArchiveEntry, name: String) = entry.kind == ArchiveEntryKind.FILE &&
        name != "manifest.json" && (mobile || name != "checksums.sha256") && name !in entries
    private fun validateDeclaredEntry(path: String, declared: Declared) {
        val found = indexed[path]
        if (found?.kind != ArchiveEntryKind.FILE) issue(RestoreProblemCode.MISSING_ARCHIVE_ENTRY, "Declared file is absent or not regular", path)
        else if (found.size != null && found.size != declared.size) issue(RestoreProblemCode.SIZE_MISMATCH, "Archive directory size differs from manifest", path)
    }
    private fun validateParents(path: String) {
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (indexed[path.substring(0, slash)]?.kind == ArchiveEntryKind.FILE) issue(RestoreProblemCode.INVALID_ARCHIVE_ENTRY, "File is also a parent directory", path)
            slash = path.indexOf('/', slash + 1)
        }
    }
}
