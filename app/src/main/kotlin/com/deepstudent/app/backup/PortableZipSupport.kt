package com.deepstudent.app.backup

import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.services.backup.api.ArchiveEntry
import com.deepstudent.services.backup.api.ArchiveEntryKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class PortableEntry(val archive: CheckedZipArchive, val item: ArchiveEntry) {
    val name: String get() = item.path.removeSuffix("/")
}

/** CheckedZipArchive establishes path/type/size semantics; desktop adds a compression-ratio budget. */
internal fun portableCatalog(file: File, archive: CheckedZipArchive, limit: Long, desktopRatios: Boolean = true): List<PortableEntry> {
    val list = archive.inspect().entries
    if (list.size > 100_000) throw PortableFileException(PortableFileError.ZIP_INVALID)
    ZipFile(file, Charsets.UTF_8).use { verifyPortableRatios(it, list, limit, desktopRatios) }
    return list.map { PortableEntry(archive, it) }.also(::verifyNoCollisions)
}

private fun verifyPortableRatios(zip: ZipFile, list: List<ArchiveEntry>, limit: Long, enforce: Boolean) {
    val entries = zip.entries()
    val budget = PortableRatioBudget(limit, enforce)
    for (item in list) {
        if (!entries.hasMoreElements()) throw PortableFileException(PortableFileError.ZIP_INVALID)
        budget.add(item, entries.nextElement())
    }
    if (entries.hasMoreElements()) throw PortableFileException(PortableFileError.ZIP_INVALID)
    budget.finish()
}

private class PortableRatioBudget(private val limit: Long, private val enforce: Boolean) {
    private var size = 0L
    private var compressed = 0L
    fun add(item: ArchiveEntry, disk: ZipEntry) {
        if (disk.name != item.path || disk.size != item.size || disk.compressedSize < 0 ||
            item.kind !in setOf(ArchiveEntryKind.FILE, ArchiveEntryKind.DIRECTORY)
        ) throw PortableFileException(PortableFileError.ZIP_INVALID)
        size = Math.addExact(size, disk.size)
        compressed = Math.addExact(compressed, disk.compressedSize)
        if (size > limit || enforce && exceedsRatio(disk.size, disk.compressedSize)) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
    }
    fun finish() {
        if (enforce && exceedsRatio(size, compressed)) throw PortableFileException(PortableFileError.ZIP_INVALID)
    }
}

private fun exceedsRatio(plain: Long, compressed: Long): Boolean = plain > 0 &&
    (compressed <= 0 || plain / 200 > compressed || plain / 200 == compressed && plain % 200 != 0L)

/** Check NFC/case aliases and file-as-parent conflicts, including across outer and inner archives. */
internal fun verifyNoCollisions(entries: List<PortableEntry>) {
    val names = mutableMapOf<String, ArchiveEntryKind>()
    for (entry in entries) {
        val name = Normalizer.normalize(entry.name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (names.putIfAbsent(name, entry.item.kind) != null) throw PortableFileException(PortableFileError.ENTRY_CONFLICT)
    }
    for (name in names.keys) {
        var slash = name.indexOf('/')
        while (slash >= 0) {
            if (names[name.substring(0, slash)] == ArchiveEntryKind.FILE) throw PortableFileException(PortableFileError.ENTRY_CONFLICT)
            slash = name.indexOf('/', slash + 1)
        }
    }
}

internal fun readPortableEntry(entry: PortableEntry, max: Int, check: () -> Unit): ByteArray {
    if (entry.item.kind != ArchiveEntryKind.FILE || (entry.item.size ?: -1) !in 0..max.toLong()) {
        throw PortableFileException(PortableFileError.ZIP_INVALID)
    }
    return ByteArrayOutputStream().use { output ->
        entry.archive.open(entry.name).use { copyPortable(it, output, entry.item.size!!, check) }
        output.toByteArray()
    }
}

internal fun copyPortable(input: InputStream, output: OutputStream, expected: Long, check: () -> Unit): PortablePayload {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        check()
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) throw PortableFileException(PortableFileError.IO_FAILED)
        count = Math.addExact(count, n.toLong())
        if (count > expected) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
        digest.update(buffer, 0, n)
        output.write(buffer, 0, n)
    }
    if (count != expected) throw PortableFileException(PortableFileError.ZIP_INVALID)
    return PortablePayload(count, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
}

internal fun writePortableZip(file: File, entries: List<PortableEntry>, manifest: ByteArray, limit: Long, check: () -> Unit) {
    requirePortableContentBudget(file, entries, manifest.size.toLong(), limit, 1)
    ZipOutputStream(PortableLimitedOutput(file.outputStream().buffered(), limit)).use { zip ->
        // Desktop rejects compression ratios above 200:1. Level zero avoids generating them.
        zip.setLevel(Deflater.NO_COMPRESSION)
        writePortableBytes(zip, PORTABLE_MANIFEST, manifest)
        appendPortableEntries(zip, entries, check)
    }
}

internal fun writePortableBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
}

internal fun digestFile(file: File, limit: Long, check: () -> Unit): PortablePayload {
    val size = Files.size(file.toPath())
    if (size !in 1..limit) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
    return Files.newInputStream(file.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
        copyPortable(it, object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, size, check)
    }
}

internal fun writePortableOuter(
    file: File, entries: List<PortableEntry>, manifest: ByteArray, encrypted: File, payload: PortablePayload,
    limit: Long, check: () -> Unit,
) {
    requirePortableContentBudget(file, entries, Math.addExact(manifest.size.toLong(), payload.size), limit, 2)
    ZipOutputStream(PortableLimitedOutput(file.outputStream().buffered(), limit)).use { zip ->
        zip.setLevel(Deflater.NO_COMPRESSION)
        writePortableBytes(zip, PORTABLE_MANIFEST, manifest)
        appendPortableEntries(zip, entries, check)
        zip.putNextEntry(ZipEntry(PORTABLE_PAYLOAD))
        val actual = Files.newInputStream(encrypted.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
            copyPortable(it, zip, payload.size, check)
        }
        if (actual != payload) throw PortableFileException(PortableFileError.SOURCE_CHANGED)
        zip.closeEntry()
    }
}

private fun appendPortableEntries(zip: ZipOutputStream, entries: List<PortableEntry>, check: () -> Unit) {
    for (entry in entries) appendPortableEntry(zip, entry, check)
}

private fun appendPortableEntry(zip: ZipOutputStream, entry: PortableEntry, check: () -> Unit) {
    check()
    zip.putNextEntry(ZipEntry(entry.item.path))
    if (entry.item.kind == ArchiveEntryKind.FILE) {
        entry.archive.open(entry.name).use { copyPortable(it, zip, entry.item.size!!, check) }
    }
    zip.closeEntry()
}

/** Bound both inflated content and written ZIP bytes, including central directory overhead. */
private fun requirePortableContentBudget(file: File, entries: List<PortableEntry>, overhead: Long, limit: Long, extraEntries: Int) {
    if (entries.size > 100_000 - extraEntries) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
    var size = overhead
    for (entry in entries) size = Math.addExact(size, entry.item.size ?: throw PortableFileException(PortableFileError.ZIP_INVALID))
    if (size > limit || file.parentFile.usableSpace < size) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
}

private class PortableLimitedOutput(private val destination: OutputStream, private val limit: Long) : OutputStream() {
    private var written = 0L
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        written = Math.addExact(written, length.toLong())
        if (written > limit) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
        destination.write(bytes, offset, length)
    }
    override fun flush() = destination.flush()
    override fun close() = destination.close()
}
