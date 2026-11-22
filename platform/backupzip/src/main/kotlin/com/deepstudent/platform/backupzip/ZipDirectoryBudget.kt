package com.deepstudent.platform.backupzip

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

internal const val ZIP_MAX_ENTRIES = 110_000

/** Cap the actual central directory and its headers before Commons Compress allocates entry objects. */
internal object ZipDirectoryBudget {
    private const val MAX_DIRECTORY_BYTES = 16L * 1024 * 1024
    private const val END_SIGNATURE = 0x06054b50
    private const val ZIP64_END_SIGNATURE = 0x06064b50
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
    private const val HEADER_SIGNATURE = 0x02014b50

    fun verify(file: File) = RandomAccessFile(file, "r").use { archive ->
        val end = endRecord(archive)
        val directory = directory(archive, end)
        if (directory.entries !in 0L..ZIP_MAX_ENTRIES.toLong() || directory.bytes !in 0L..MAX_DIRECTORY_BYTES) reject()
        if (directory.start < 0 || directory.start + directory.bytes != directory.end) reject()
        verifyHeaderCount(archive, directory)
    }

    private fun endRecord(archive: RandomAccessFile): End {
        val n = minOf(archive.length(), 65_557L).toInt()
        if (n < 22) reject()
        val start = archive.length() - n
        val tail = archive.readAt(start, n)
        for (p in n - 22 downTo 0) {
            if (tail.getInt(p) == END_SIGNATURE && p + 22 + tail.u16(p + 20) == n) return End(start + p, p, tail)
        }
        reject()
    }

    private fun directory(archive: RandomAccessFile, end: End): Table {
        val (position, p, tail) = end
        if (tail.u16(p + 4) != 0 || tail.u16(p + 6) != 0 || tail.u16(p + 8) != tail.u16(p + 10)) reject()
        val count = tail.u16(p + 10).toLong()
        val size = tail.u32(p + 12)
        val start = tail.u32(p + 16)
        val locator = if (position >= 20) archive.readAt(position - 20, 20) else null
        if (locator?.getInt(0) == ZIP64_LOCATOR_SIGNATURE) return zip64Directory(archive, position - 20, locator)
        if (count == 65_535L || size == 0xffffffffL || start == 0xffffffffL) reject()
        return Table(start, size, count, position)
    }

    private fun zip64Directory(archive: RandomAccessFile, locatorAt: Long, locator: ByteBuffer): Table {
        val endAt = locator.getLong(8)
        if (locator.u32(4) != 0L || locator.u32(16) != 1L || endAt !in 0..locatorAt - 56) reject()
        val record = archive.readAt(endAt, 56)
        val recordSize = record.getLong(4)
        if (record.getInt(0) != ZIP64_END_SIGNATURE) reject()
        verifyZip64EndSize(recordSize, endAt, locatorAt)
        if (record.u32(16) != 0L || record.u32(20) != 0L || record.getLong(24) != record.getLong(32)) reject()
        return Table(record.getLong(48), record.getLong(40), record.getLong(32), endAt)
    }

    private fun verifyZip64EndSize(size: Long, endAt: Long, locatorAt: Long) {
        if (size !in 44..16_384 || endAt + 12 + size != locatorAt) reject()
    }

    private fun verifyHeaderCount(archive: RandomAccessFile, table: Table) {
        val header = ByteBuffer.allocate(46).order(LITTLE_ENDIAN)
        var next = table.start
        repeat(table.entries.toInt()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("ZIP inspection cancelled")
            if (next > table.end - 46) reject()
            archive.seek(next)
            archive.readFully(header.array())
            if (header.getInt(0) != HEADER_SIGNATURE) reject()
            next += 46L + header.u16(28) + header.u16(30) + header.u16(32)
        }
        if (next != table.end) reject()
    }

    private fun RandomAccessFile.readAt(position: Long, length: Int): ByteBuffer {
        val bytes = ByteArray(length)
        seek(position)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
    }

    private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xffff
    private fun ByteBuffer.u32(offset: Int): Long = getInt(offset).toLong() and 0xffffffffL
    private fun reject(): Nothing = throw IOException("ZIP central directory is outside its supported structure or budget")
    private data class End(val position: Long, val offset: Int, val bytes: ByteBuffer)
    private data class Table(val start: Long, val bytes: Long, val entries: Long, val end: Long)
}
