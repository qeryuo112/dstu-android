package com.deepstudent.services.backup.api

import java.io.Closeable
import java.io.InputStream

/** Read-only source. Implementations must expose every archive entry, including duplicates and links. */
interface RestoreArchive : Closeable {
    fun inspect(): ArchiveCatalog
    /** Opens exactly the named regular archive file; the caller closes the returned stream. */
    fun open(path: String): InputStream
}

enum class ArchiveEntryKind { FILE, DIRECTORY, SYMBOLIC_LINK, OTHER }

data class ArchiveEntry(val path: String, val kind: ArchiveEntryKind, val size: Long? = null)

data class ArchiveCatalog(val entries: List<ArchiveEntry>)

/**
 * Observations from actually reading a file, never a checksum supplied by an archive.
 * A caller of the pure planner must obtain these from a trusted preflight reader; production
 * callers should use ArchivePreflight. They are not a capability to commit a restore.
 */
data class ShaEvidence(val path: String, val observedSize: Long, val observedSha256: String)
