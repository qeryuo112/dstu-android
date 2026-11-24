package com.ai.assistance.operit.terminal

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/** Host bridge for the unmodified Operit JNI and nonblocking I/O implementation. */
internal class Pty private constructor(
    val pid: Int,
    private val master: ParcelFileDescriptor,
    private val ioDescriptor: ParcelFileDescriptor,
    private val io: PtyIo,
) {
    val input get() = io.input
    val output get() = io.output
    private val guard = Any()
    private var exitStatus: Int? = null
    private var closed = false

    fun interrupt() = io.interrupt()

    fun resize(rows: Int, columns: Int): Boolean = synchronized(guard) {
        !closed && exitStatus == null && setPtyWindowSize(master.fd, rows, columns) == 0
    }

    fun awaitExit(): Int {
        while (true) {
            synchronized(guard) {
                exitStatus?.let { return it }
                val result = waitForNoHang(pid)
                if (result != Int.MIN_VALUE) {
                    exitStatus = result
                    return result
                }
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    fun signal(signal: Int) {
        synchronized(guard) {
            if (!closed && exitStatus == null) signalSession(master.fd, pid, signal)
        }
    }

    fun terminate() {
        synchronized(guard) {
            if (!closed && exitStatus == null) terminateSession(master.fd, pid)
        }
    }

    fun close() {
        synchronized(guard) {
            if (closed) return
            closed = true
            try {
                io.input.close()
            } finally {
                detachQuietly(ioDescriptor)
                master.close()
            }
        }
    }

    private fun detachQuietly(descriptor: ParcelFileDescriptor) {
        try {
            descriptor.detachFd()
        } catch (_: Exception) {
        }
    }

    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int

    companion object {
        init {
            System.loadLibrary("pty")
        }

        private val startGuard = Any()
        private const val POLL_MILLIS = 20L

        fun start(directory: File, rows: Int, columns: Int): Pty =
            launch(directory, rows, columns, arrayOf("/system/bin/sh", "-i"), interactive = true)

        fun startCommand(directory: File, argv: Array<String>): Pty = launch(directory, 24, 80, argv, interactive = false)

        private fun launch(directory: File, rows: Int, columns: Int, argv: Array<String>, interactive: Boolean): Pty =
            synchronized(startGuard) {
                val env = arrayOf(
                    "PATH=/system/bin:/system/xbin",
                    "HOME=${directory.absolutePath}",
                    "TMPDIR=${directory.absolutePath}",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                )
                val info = createSubprocess(argv, env, directory.absolutePath)
                    ?: throw IOException("forkpty failed")
                require(info.size == 2 && info[0] > 0 && info[1] >= 0) {
                    "Invalid PTY process handle"
                }
                val master = adoptMaster(info)
                try {
                    configureCloseOnExec(master.fd)
                    val descriptor = ParcelFileDescriptor.dup(master.fileDescriptor)
                    try {
                        configureCloseOnExec(descriptor.fd)
                        val pty = Pty(info[0], master, descriptor, PtyIo(descriptor.fileDescriptor, master.fd))
                        check(pty.resize(rows, columns)) { "Initial PTY resize failed" }
                        // Android mksh's line editor drops supplementary code points. Compose owns
                        // line composition; turning both editors off preserves literal Chinese/emoji.
                        if (interactive) pty.output.write("set +o emacs; set +o vi\n".toByteArray(Charsets.US_ASCII))
                        pty
                    } catch (error: Throwable) {
                        closeQuietly(descriptor)
                        throw error
                    }
                } catch (error: Throwable) {
                    signalSession(master.fd, info[0], 9)
                    closeQuietly(master)
                    waitFor(info[0])
                    throw error
                }
            }

        private fun adoptMaster(info: IntArray): ParcelFileDescriptor = try {
            ParcelFileDescriptor.adoptFd(info[1])
        } catch (error: Throwable) {
            signalSession(info[1], info[0], 9)
            closeRaw(info[1])
            waitFor(info[0])
            throw error
        }

        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            try {
                descriptor.close()
            } catch (_: Exception) {
            }
        }

        fun configureNonBlocking(fd: Int) {
            val errno = setNonBlocking(fd)
            if (errno != 0) throw IOException("PTY nonblocking setup failed: errno=$errno")
        }

        private fun configureCloseOnExec(fd: Int) {
            val errno = setCloseOnExec(fd)
            if (errno != 0) throw IOException("PTY close-on-exec setup failed: errno=$errno")
        }

        private external fun setNonBlocking(fd: Int): Int
        private external fun setCloseOnExec(fd: Int): Int
        private external fun createSubprocess(command: Array<String>, environment: Array<String>, cwd: String): IntArray?
        private external fun waitFor(pid: Int): Int
        private external fun waitForNoHang(pid: Int): Int
        private external fun signalSession(fd: Int, pid: Int, signal: Int)
        private external fun terminateSession(fd: Int, pid: Int)
        private external fun closeRaw(fd: Int)
    }
}
