package com.deepstudent.features.terminal.data

import com.deepstudent.engines.terminal.EngineTerminalSize
import com.deepstudent.engines.terminal.OperitPtyProcess
import com.deepstudent.engines.terminal.OperitTerminalScreen
import com.deepstudent.features.terminal.api.TerminalCell
import com.deepstudent.features.terminal.api.TerminalScreen
import com.deepstudent.features.terminal.api.TerminalSize
import com.deepstudent.features.terminal.api.TerminalStyle
import com.deepstudent.features.terminal.backend.TerminalProcessFactory
import com.deepstudent.features.terminal.backend.TerminalProcessPort
import com.deepstudent.features.terminal.backend.TerminalScreenFactory
import com.deepstudent.features.terminal.backend.TerminalScreenPort
import com.deepstudent.features.terminal.backend.TerminalSessionService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Platform composition for terminal. The app supplies an existing sandbox directory and owns
 * the returned service for the process lifetime.
 */
class OperitTerminalPlatform(
    private val workingDirectory: File,
) {
    fun createService(): TerminalSessionService {
        require(workingDirectory.isDirectory) { "Terminal working directory is unavailable" }
        return TerminalSessionService(processFactory(), screenFactory())
    }

    fun processFactory(): TerminalProcessFactory = TerminalProcessFactory { size ->
        OperitProcessAdapter(OperitPtyProcess.start(workingDirectory, size.toEngine()))
    }

    fun screenFactory(): TerminalScreenFactory = TerminalScreenFactory { size ->
        OperitScreenAdapter(OperitTerminalScreen(size.toEngine()))
    }
}

private class OperitProcessAdapter(
    private val process: OperitPtyProcess,
) : TerminalProcessPort {
    override val input: InputStream get() = process.input
    override val output: OutputStream get() = process.output

    override fun resize(rows: Int, columns: Int): Boolean = process.resize(rows, columns)

    override fun interrupt() = process.interrupt()

    override fun terminate() = process.terminate()

    override fun awaitExit(): Int = process.awaitExit()

    override fun close() = process.close()
}

private class OperitScreenAdapter(
    private val screen: OperitTerminalScreen,
) : TerminalScreenPort {
    override fun append(text: String) = screen.append(text)

    override fun resize(size: TerminalSize) = screen.resize(size.toEngine())

    override fun snapshot(): TerminalScreen = screen.snapshot().toApi()
}

private fun TerminalSize.toEngine(): EngineTerminalSize =
    EngineTerminalSize(columns = columns, rows = rows)

private fun com.deepstudent.engines.terminal.EngineTerminalScreen.toApi(): TerminalScreen =
    TerminalScreen(
        size = TerminalSize(size.columns, size.rows),
        lines = lines.map { line -> line.map { it.toApi() } },
        historyRows = historyRows,
        cursorColumn = cursorColumn,
        cursorRow = cursorRow,
        cursorVisible = cursorVisible,
    )

private fun com.deepstudent.engines.terminal.EngineTerminalCell.toApi(): TerminalCell =
    TerminalCell(
        text = text,
        style = TerminalStyle(style.foreground, style.background, style.flags),
        continuation = continuation,
    )
