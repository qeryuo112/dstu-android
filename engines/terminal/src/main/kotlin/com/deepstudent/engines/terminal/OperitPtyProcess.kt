package com.deepstudent.engines.terminal

import com.ai.assistance.operit.terminal.Pty
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Narrow host handle over Operit's PTY bridge. It exposes bytes and lifecycle only; ANSI state
 * is owned by [OperitTerminalScreen].
 */
class OperitPtyProcess private constructor(private val process: Pty) : AutoCloseable {
    val input: InputStream get() = process.input
    val output: OutputStream get() = process.output

    fun interrupt() = process.interrupt()

    fun resize(rows: Int, columns: Int): Boolean = process.resize(rows, columns)

    fun terminate() = process.terminate()

    fun awaitExit(): Int = process.awaitExit()

    override fun close() = process.close()

    companion object {
        fun startCommand(workingDirectory: File, argv: List<String>): OperitPtyProcess =
            OperitPtyProcess(Pty.startCommand(workingDirectory, argv.toTypedArray()))

        fun start(workingDirectory: File, size: EngineTerminalSize): OperitPtyProcess =
            OperitPtyProcess(Pty.start(workingDirectory, size.rows, size.columns))
    }
}
