package com.deepstudent.features.environments.data

import com.deepstudent.engines.proot.PRootProcessRunner
import com.deepstudent.engines.proot.PRootRunRequest
import com.deepstudent.engines.proot.PRootRunResult
import com.deepstudent.engines.proot.PRootRuntimePaths
import com.deepstudent.features.environments.api.EnvironmentManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Borrowed S1.21 owner; a selected generation never silently moves to a newer rootfs. */
class EnvironmentConsole internal constructor(
    private val paths: EnvironmentPaths,
    private val runtime: PRootRuntimePaths?,
    private val manager: EnvironmentManager,
) {
    fun activeGeneration(): String? = manager.state.value.takeIf { runtime != null && it.isReady() }?.activeGeneration

    suspend fun run(generation: String, script: String, timeoutMillis: Long, outputBytes: Int): PRootRunResult? {
        val runtime = runtime ?: return null
        if (activeGeneration() != generation) return null
        val expected = File(paths.root.canonicalFile, "generations/$generation/rootfs")
        val root = paths.generationRoot(generation).canonicalFile
        if (root != expected || !root.isDirectory) return null
        return coroutineScope {
            val command = async {
                PRootProcessRunner(runtime).run(PRootRunRequest(root, script, timeoutMillis, maxOutputBytes = outputBytes))
            }
            val watch = launch {
                manager.state.first { !it.isReady() || it.activeGeneration != generation }
                command.cancel()
            }
            try {
                command.await()
            } finally {
                watch.cancel()
            }
        }
    }
}
