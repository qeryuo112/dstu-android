package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalSize
import java.io.InputStream
import java.io.OutputStream

/**
 * Process I/O is deliberately separate from ANSI state. The platform adapter owns descriptors
 * and the native child, while the session owner only sequences calls.
 */
interface TerminalProcessPort {
    val input: InputStream
    val output: OutputStream

    fun resize(rows: Int, columns: Int): Boolean

    /** Writes the terminal interrupt character without exposing signals to callers. */
    fun interrupt()

    fun terminate()

    /** Returns the shell exit status, or a negative value when it cannot be obtained. */
    fun awaitExit(): Int

    fun close()
}

interface TerminalScreenPort {
    fun append(text: String)
    fun resize(size: TerminalSize)
    fun snapshot(): TerminalScreen
}

fun interface TerminalProcessFactory {
    fun start(size: TerminalSize): TerminalProcessPort
}

fun interface TerminalScreenFactory {
    fun create(size: TerminalSize): TerminalScreenPort
}

fun interface TerminalIdFactory {
    fun next(): String
}
