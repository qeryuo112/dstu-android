package com.deepstudent.engines.proot

import java.io.InputStream
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/** Owns the tracer and its one pipe worker. Never closes a pipe before killing its writer. */
internal class PRootProcessOwner(command: List<String>, environment: Map<String, String>, limit: Int) : AutoCloseable {
    private val process = ProcessBuilder(
        listOf("/system/bin/sh", "-c", BOOTSTRAP, "dstu-proot") + command,
    ).redirectErrorStream(true).apply {
        environment().clear()
        environment().putAll(environment)
    }.start()
    private val pid = readOwnerPid(process.inputStream)
    private val output = PRootBoundedOutput(limit)
    private val readFailure = AtomicReference<Exception?>()
    private val reader = Thread({ drain(process.inputStream) }, "dstu-proot-output").apply {
        isDaemon = true
        start()
    }

    init {
        // Package and health commands are noninteractive. Their stdin must reach EOF.
        process.outputStream.close()
    }

    fun waitForStep(): Boolean = process.waitFor(100, TimeUnit.MILLISECONDS)

    fun result(): PRootRunResult {
        check(!process.isAlive) { "PRoot is still running" }
        reader.join(2_000)
        check(!reader.isAlive) { "PRoot output pipe did not reach EOF" }
        readFailure.get()?.let { throw IllegalStateException("PRoot output read failed", it) }
        return PRootRunResult(process.exitValue(), output.text(), output.truncated)
    }

    override fun close() {
        // This pinned PRoot deliberately IGNORES SIGTERM, including Android's
        // Process.destroyForcibly implementation. SIGQUIT is its explicit owner
        // teardown handler: it kills all tracees and exits through the event loop.
        // The PID is emitted by our fixed host shell before exec, never parsed
        // from a guest command or a hidden/reflected Java Process implementation.
        if (process.isAlive) signal(OsConstants.SIGQUIT)
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            logger.warning("event=proot-stop-deadline")
            signal(OsConstants.SIGKILL)
            check(process.waitFor(2, TimeUnit.SECONDS)) { "PRoot process did not terminate" }
        }
        reader.join(2_000)
        check(!reader.isAlive) { "PRoot output worker did not terminate" }
        process.inputStream.close()
        process.errorStream.close()
    }

    private fun drain(input: InputStream) {
        try {
            input.use {
                drainBytes(it)
            }
        } catch (failure: Exception) {
            readFailure.set(failure)
            logger.warning("event=proot-pipe-failed type=${failure.javaClass.simpleName}")
        }
    }

    private fun drainBytes(input: InputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.append(buffer, count)
        }
    }

    private fun signal(number: Int) {
        try {
            Os.kill(pid, number)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ESRCH) throw failure
        }
    }

    private fun readOwnerPid(input: InputStream): Int {
        val line = StringBuilder()
        while (line.length < 64) {
            val byte = input.read()
            check(byte >= 0) { "PRoot launcher closed before its owner handshake" }
            if (byte == 10) {
                check(line.startsWith("DSTU_PROOT_PID\t")) { "invalid PRoot owner handshake" }
                return line.toString().substringAfter('\t').toInt().also { check(it > 1) }
            }
            line.append(byte.toChar())
        }
        error("PRoot launcher handshake exceeds its budget")
    }

    private companion object {
        const val BOOTSTRAP = "printf 'DSTU_PROOT_PID\\t%s\\n' \"\$\$\"; exec \"\$@\""
        val logger: Logger = Logger.getLogger("DstuPRoot")
    }
}

/** Keep the tail, including version receipts, while always draining the kernel pipe. */
internal class PRootBoundedOutput(private val limit: Int) {
    private val bytes = ByteArray(limit)
    private var cursor = 0
    private var count = 0
    var truncated: Boolean = false
        private set

    @Synchronized
    fun append(source: ByteArray, size: Int) {
        require(size in 0..source.size)
        for (index in 0 until size) {
            bytes[cursor] = source[index]
            cursor = (cursor + 1) % limit
        }
        truncated = truncated || count + size > limit
        count = minOf(limit, count + size)
    }

    @Synchronized
    fun text(): String {
        val first = (cursor - count + limit) % limit
        val tail = ByteArray(count) { bytes[(first + it) % limit] }
        return tail.toString(Charsets.UTF_8)
    }
}
