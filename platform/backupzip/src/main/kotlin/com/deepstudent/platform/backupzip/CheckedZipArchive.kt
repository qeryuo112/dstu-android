package com.deepstudent.platform.backupzip

import com.deepstudent.services.backup.api.ArchiveCatalog
import com.deepstudent.services.backup.api.ArchiveEntry
import com.deepstudent.services.backup.api.ArchiveEntryKind
import com.deepstudent.services.backup.api.RestoreArchive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

private const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024 * 1024

/**
 * Read-only ZIP view of a caller-owned private file. No extraction or mutation is performed.
 * Caller owns SAF authorization and the lifetime of the private copy. Closing this closes ZIP streams.
 */
class CheckedZipArchive(file: File) : RestoreArchive {
    @Suppress("DEPRECATION")
    private val zip: ZipFile
    private var closed = false
    private var catalog: ArchiveCatalog? = null
    private var byName: Map<String, ZipArchiveEntry> = emptyMap()

    init {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath())) { "Archive must be a regular private file" }
        require(file.length() in 1..MAX_ARCHIVE_BYTES) { "Archive size is outside limits" }
        ZipDirectoryBudget.verify(file)
        // UTF-8 only; do not substitute a separate Unicode extra-field name.
        @Suppress("DEPRECATION")
        zip = ZipFile(file, "UTF-8", false, false)
    }

    override fun inspect(): ArchiveCatalog {
        check(!closed)
        catalog?.let { return it }
        val state = ZipScanState()
        val enumeration = zip.entries
        while (enumeration.hasMoreElements()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("ZIP inspection cancelled")
            if (state.entries.size >= ZIP_MAX_ENTRIES) throw IOException("ZIP entry limit exceeded")
            addEntry(enumeration.nextElement(), state)
        }
        byName = state.names
        return ArchiveCatalog(state.entries.toList()).also { catalog = it }
    }
    private fun addEntry(entry: ZipArchiveEntry, state: ZipScanState) {
        val name = strictName(entry)
        val path = if (entry.isDirectory) name.removeSuffix("/") else name
        if (!safeName(path)) throw IOException("Unsafe ZIP entry name")
        if (state.names.putIfAbsent(path, entry) != null) throw IOException("Duplicate ZIP entry")
        validateSize(entry, state)
        validateEncoding(entry)
        state.entries += ArchiveEntry(name, entryKind(entry), entry.size)
    }
    private fun validateSize(entry: ZipArchiveEntry, state: ZipScanState) {
        if (entry.size < 0 || entry.compressedSize < 0) throw IOException("Unknown ZIP entry size")
        state.total = try { Math.addExact(state.total, entry.size) } catch (_: ArithmeticException) { throw IOException("ZIP size overflow") }
        if (state.total > MAX_ARCHIVE_BYTES) throw IOException("ZIP inflated size exceeds limit")
    }
    private fun validateEncoding(entry: ZipArchiveEntry) {
        if (entry.generalPurposeBit.usesEncryption() || entry.method !in setOf(0, 8) || !zip.canReadEntryData(entry)) throw IOException("Unsupported ZIP entry encoding")
    }

    override fun open(path: String): InputStream {
        check(!closed)
        inspect()
        val entry = byName[path] ?: throw IOException("ZIP entry not found")
        if (entryKind(entry) != ArchiveEntryKind.FILE) throw IOException("ZIP entry is not a regular file")
        return zip.getInputStream(entry) ?: throw IOException("ZIP entry is not readable")
    }

    override fun close() {
        if (!closed) { closed = true; zip.close() }
    }

    private fun strictName(entry: ZipArchiveEntry): String {
        val raw = entry.rawName ?: throw IOException("ZIP entry lacks a raw name")
        val decoded = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Exception) { throw IOException("ZIP entry name is not UTF-8") }
        if (decoded != entry.name) throw IOException("Ambiguous ZIP entry name")
        return decoded
    }

    private fun entryKind(entry: ZipArchiveEntry): ArchiveEntryKind {
        if (entry.isUnixSymlink) return ArchiveEntryKind.SYMBOLIC_LINK
        val modeType = entry.unixMode and 0xF000
        if (modeType != 0 && modeType != 0x8000 && modeType != 0x4000) return ArchiveEntryKind.OTHER
        if ((modeType == 0x4000) != entry.isDirectory && modeType != 0) return ArchiveEntryKind.OTHER
        return if (entry.isDirectory) ArchiveEntryKind.DIRECTORY else ArchiveEntryKind.FILE
    }

    private fun safeName(path: String): Boolean {
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > 4096) return false
        if (path.startsWith('/') || '\\' in path || ':' in path) return false
        if (path.any { it.code < 32 || it.code == 127 }) return false
        return path.split('/').none(::invalidComponent)
    }
    private fun invalidComponent(part: String) = part.isEmpty() || part == "." || part == ".."
}

private class ZipScanState(
    val entries: MutableList<ArchiveEntry> = mutableListOf(),
    val names: MutableMap<String, ZipArchiveEntry> = mutableMapOf(),
    var total: Long = 0,
)
