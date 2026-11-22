package com.deepstudent.services.backup.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_SNAPSHOT_FILES = 300_000
private const val MAX_SNAPSHOT_BYTES = 200L * 1024 * 1024 * 1024
private const val RESERVE_BYTES = 16L * 1024 * 1024

internal data class SnapshotEntry(val source: Path, val relative: Path, val directory: Boolean, val bytes: Long)
internal data class Snapshot(val entries: List<SnapshotEntry>, val bytes: Long)

/** Only used while DataSpace maintenance excludes every normal lease and database connection. */
internal object SnapshotCopy {
    fun enumerate(active: File, trace: RestoreTrace): Snapshot {
        val root = active.toPath()
        val entries = mutableListOf<SnapshotEntry>()
        var bytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                trace.checkCancelled()
                if (!attrs.isDirectory || attrs.isSymbolicLink) throw RestoreFailure(RestoreError.SNAPSHOT_UNSAFE)
                if (dir != root) entries += SnapshotEntry(dir, root.relativize(dir), true, 0)
                if (entries.size > MAX_SNAPSHOT_FILES) throw RestoreFailure(RestoreError.LIMIT_EXCEEDED)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                trace.checkCancelled()
                if (!attrs.isRegularFile || attrs.isSymbolicLink) throw RestoreFailure(RestoreError.SNAPSHOT_UNSAFE)
                val rel = root.relativize(file)
                if (sqliteEphemeral(rel)) return FileVisitResult.CONTINUE
                bytes = checkedSum(bytes, attrs.size(), MAX_SNAPSHOT_BYTES)
                entries += SnapshotEntry(file, rel, false, attrs.size())
                if (entries.size > MAX_SNAPSHOT_FILES) throw RestoreFailure(RestoreError.LIMIT_EXCEEDED)
                return FileVisitResult.CONTINUE
            }
        })
        return Snapshot(entries.toList(), bytes)
    }

    fun checkSpace(snapshot: Snapshot, stage: File, incomingBytes: Long) {
        val required = checkedSum(checkedSum(snapshot.bytes, incomingBytes, Long.MAX_VALUE), RESERVE_BYTES, Long.MAX_VALUE)
        // Android app UIDs cannot enumerate mount stores here; query the owned directory itself.
        if (stage.usableSpace < required) throw RestoreFailure(RestoreError.INSUFFICIENT_SPACE)
    }

    fun copy(snapshot: Snapshot, stage: File, trace: RestoreTrace) {
        for (entry in snapshot.entries) {
            trace.checkCancelled()
            val target = stage.toPath().resolve(entry.relative)
            if (entry.directory) Files.createDirectory(target) else copyFile(entry, target, trace)
            trace.checkpoint(RestoreCheckpoint.SNAPSHOT_ENTRY)
        }
    }

    private fun copyFile(entry: SnapshotEntry, target: Path, trace: RestoreTrace) {
        FileChannel.open(entry.source, READ, NOFOLLOW_LINKS).use { source ->
            FileChannel.open(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { destination ->
                transfer(source, destination, entry.bytes, trace)
            }
        }
    }

    private fun transfer(source: FileChannel, destination: FileChannel, expected: Long, trace: RestoreTrace) {
        val buffer = ByteBuffer.allocateDirect(64 * 1024)
        var count = 0L
        while (true) {
            trace.checkCancelled()
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) throw RestoreFailure(RestoreError.IO_FAILURE)
            count = checkedSum(count, read.toLong(), expected)
            buffer.flip()
            while (buffer.hasRemaining()) destination.write(buffer)
            buffer.clear()
        }
        if (count != expected) throw RestoreFailure(RestoreError.SNAPSHOT_UNSAFE)
    }

    private fun sqliteEphemeral(relative: Path): Boolean {
        if (relative.nameCount != 2 || relative.getName(0).toString() != "databases") return false
        val name = relative.fileName.toString()
        return name.endsWith(".db-shm") || name.endsWith(".db.lock")
    }
}
