package com.deepstudent.app.terminal

import android.content.Context
import android.util.Log
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.features.terminal.backend.TerminalSessionService
import com.deepstudent.features.terminal.data.OperitTerminalPlatform
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Main-process owner for PTY-backed terminal sessions. */
internal class MainTerminalResources private constructor(
    private val service: TerminalSessionService,
) : AutoCloseable {
    val sessions: TerminalSessions get() = service

    /** Waits for every owned session to close before the service scope is cancelled. */
    suspend fun shutdown() = withContext(Dispatchers.IO + NonCancellable) {
        service.shutdown()
        Log.i(TAG, "terminal owner shutdown")
    }

    override fun close(): Unit = runBlocking { shutdown(); Unit }

    companion object {
        private const val TAG = "DstuTerminal"

        fun open(context: Context): MainTerminalResources {
            val workingDirectory = File(context.filesDir, "terminal")
            check(workingDirectory.isDirectory || workingDirectory.mkdirs()) {
                "Terminal working directory unavailable"
            }
            val service = OperitTerminalPlatform(workingDirectory).createService()
            Log.i(TAG, "terminal owner created")
            return MainTerminalResources(service)
        }
    }
}
