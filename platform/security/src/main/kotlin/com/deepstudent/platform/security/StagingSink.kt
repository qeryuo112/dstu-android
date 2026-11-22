package com.deepstudent.platform.security

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Every operation shares the area's lock, making commit/abort/close linearizable. */
internal class StagingSink(
    private val target: File,
    private val limit: Long,
    directory: File,
    private val state: StagingDirectoryState,
    private val directoryBudget: Long,
    private val onFinished: (StagingSink) -> Unit,
) : DsbkSink {
    private val temp = Files.createTempFile(directory.toPath(), ".dstu-", ".part").toFile()
    private val output: FileChannel
    private var committed = false
    private var aborted = false
    private var size = 0L

    val tempName: String get() = temp.name
    val targetPath: String = target.canonicalPath
    override val stagedBytes: Long get() = synchronized(state.lock) { size }

    init {
        try {
            SecureFiles.ownerOnly(temp)
            output = FileChannel.open(temp.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                output.lock() // Released on close; orphan cleanup can distinguish a live process.
            } catch (error: Exception) {
                output.close()
                throw error
            }
        } catch (error: Exception) {
            SecureFiles.wipeAndDelete(temp)
            throw error
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(state.lock) {
        ensureWritable()
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
        if (length.toLong() > limit - size || length.toLong() > directoryBudget - state.stagedBytes) {
            throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        }
        try {
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) output.write(buffer)
            size += length
            state.stagedBytes += length
        } catch (_: Exception) {
            abort()
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    override fun commit() = synchronized(state.lock) {
        ensureWritable()
        try {
            output.force(true)
            SecureFiles.replaceAtomically(temp, target)
            committed = true
            state.stagedBytes -= size
            // Activation has already happened: close diagnostics cannot turn a
            // successful atomic rename into a reported failed transaction.
            runCatching { output.close() }
            onFinished(this)
        } catch (_: Exception) {
            abort()
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    override fun abort() = synchronized(state.lock) {
        if (!aborted && !committed) {
            aborted = true
            runCatching { output.close() }
            SecureFiles.wipeAndDelete(temp)
            state.stagedBytes -= size
            size = 0
            onFinished(this)
        }
    }

    private fun ensureWritable() {
        if (committed || aborted) throw DsbkException(DsbkError.STAGING_FAILED)
    }
}
