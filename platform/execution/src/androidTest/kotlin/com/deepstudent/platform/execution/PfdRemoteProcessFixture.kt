package com.deepstudent.platform.execution

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import moe.shizuku.server.IRemoteProcess

/** Real Android pipes and correct stdin direction; no server permission is needed. */
internal class PfdRemoteProcessFixture(
    payload: ByteArray = "package:android\n".toByteArray(),
    error: ByteArray = byteArrayOf(),
    private val omitStderr: Boolean = false,
    private val holdOpen: Boolean = false,
) : IRemoteProcess.Stub(), Closeable {
    private val stdoutPipe = ParcelFileDescriptor.createPipe()
    private val stderrPipe = ParcelFileDescriptor.createPipe()
    private val stdinPipe = ParcelFileDescriptor.createPipe()
    private val writersExited = CountDownLatch(2)
    private val releaseWriters = CountDownLatch(1)
    val stdinEof = CountDownLatch(1)
    val destroyed = AtomicBoolean(false)
    val stdoutGets = AtomicInteger()
    val stderrGets = AtomicInteger()
    val stdinGets = AtomicInteger()

    init {
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(stdinPipe[0]).use {
                    while (it.read() >= 0) { /* The client should only send EOF. */ }
                }
            }
            stdinEof.countDown()
        }.apply { isDaemon = true; start() }
        writePipe(stdoutPipe[1], payload)
        if (omitStderr) {
            stderrPipe.forEach { it.close() }
            writersExited.countDown()
        } else {
            writePipe(stderrPipe[1], error)
        }
    }

    override fun getOutputStream(): ParcelFileDescriptor {
        stdinGets.incrementAndGet()
        return stdinPipe[1]
    }

    override fun getInputStream(): ParcelFileDescriptor {
        stdoutGets.incrementAndGet()
        return stdoutPipe[0]
    }

    override fun getErrorStream(): ParcelFileDescriptor? {
        stderrGets.incrementAndGet()
        return if (omitStderr) null else stderrPipe[0]
    }

    override fun waitFor(): Int {
        check(writersExited.await(10, TimeUnit.SECONDS))
        return exitValue()
    }

    override fun exitValue(): Int {
        check(writersExited.count == 0L)
        return if (destroyed.get()) 137 else 0
    }

    override fun destroy() {
        destroyed.set(true)
        releaseWriters.countDown()
        runCatching { stdoutPipe[1].close() }
        runCatching { stderrPipe[1].close() }
    }

    override fun alive(): Boolean = writersExited.count != 0L

    override fun waitForTimeout(timeout: Long, unit: String?): Boolean =
        writersExited.await(timeout, TimeUnit.valueOf(checkNotNull(unit)))

    fun stdoutFd(): Int = descriptorNumber(stdoutPipe[0])
    fun stderrFd(): Int = descriptorNumber(stderrPipe[0])
    fun stdinFd(): Int = descriptorNumber(stdinPipe[1])

    private fun descriptorNumber(descriptor: ParcelFileDescriptor): Int = try {
        descriptor.fd
    } catch (closed: IllegalStateException) {
        -1 // ParcelFileDescriptor.getFd explicitly throws once its owner has closed it.
    }

    override fun close() {
        destroy()
        (stdoutPipe + stderrPipe + stdinPipe).forEach { runCatching { it.close() } }
        check(writersExited.await(2, TimeUnit.SECONDS))
        check(stdinEof.await(2, TimeUnit.SECONDS))
    }

    private fun writePipe(descriptor: ParcelFileDescriptor, bytes: ByteArray) {
        Thread {
            try {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {
                        it.write(bytes)
                        it.flush()
                        if (holdOpen) check(releaseWriters.await(10, TimeUnit.SECONDS))
                    }
                }
            } finally {
                writersExited.countDown()
            }
        }.apply { isDaemon = true; start() }
    }
}
