package com.deepstudent.app.backup

import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkSink
import com.deepstudent.platform.security.DsbkStagingArea
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

/** Inner DSBK is not a complete backup ZIP: use the shared staging area, not validateZip. */
internal class PortableCryptoFiles(
    private val crypto: DsbkCrypto,
    private val staging: DsbkStagingArea,
    private val maxPlaintextBytes: Long,
    private val maxContainerBytes: Long,
) {
    fun decrypt(source: File, target: File, password: CharArray, check: () -> Unit) {
        Files.newByteChannel(source.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val length = channel.size()
            val header = crypto.preflight(checking(Channels.newInputStream(channel), check), length, maxPlaintextBytes, maxContainerBytes)
            val required = header.plaintextBytes ?: maxPlaintextBytes
            if (target.parentFile.usableSpace < required) throw PortableFileException(PortableFileError.LIMIT_EXCEEDED)
            channel.position(0)
            val sink = staging.createSink(target, maxPlaintextBytes)
            try {
                crypto.decrypt(checking(Channels.newInputStream(channel), check), password, sink, maxPlaintextBytes, maxContainerBytes)
                check()
                if (channel.size() != length) throw PortableFileException(PortableFileError.SOURCE_CHANGED)
            } finally { sink.abort() }
        }
    }

    fun encrypt(source: File, target: File, password: CharArray, check: () -> Unit) {
        val sink = staging.createSink(target, maxContainerBytes)
        try {
            Files.newInputStream(source.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                crypto.encryptV2(checking(input, check), PortableSinkOutput(sink, check), password, maxPlaintextBytes)
            }
            check()
            sink.commit()
        } finally { sink.abort() }
    }
}

private fun checking(input: InputStream, check: () -> Unit): InputStream = object : FilterInputStream(input) {
    override fun read(): Int { check(); return `in`.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { check(); return `in`.read(bytes, offset, length) }
}

private class PortableSinkOutput(private val sink: DsbkSink, private val check: () -> Unit) : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) { check(); sink.write(bytes, offset, length) }
}
