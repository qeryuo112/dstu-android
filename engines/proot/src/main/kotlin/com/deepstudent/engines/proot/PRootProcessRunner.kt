package com.deepstudent.engines.proot

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class PRootBindMount(
    val hostPath: String,
    val guestPath: String? = null,
)

data class PRootRunRequest(
    val rootfs: File,
    val script: String,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val bindMounts: List<PRootBindMount> = emptyList(),
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    val assumeNoSeccomp: Boolean = true,
) {
    init {
        require(script.isNotBlank()) { "PRoot script must not be blank" }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "invalid PRoot timeout" }
        require(maxOutputBytes in 1024..MAX_OUTPUT_BYTES) { "invalid PRoot output budget" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15L * 60L * 1000L
        const val MAX_TIMEOUT_MILLIS = 60L * 60L * 1000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
        const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    }
}

data class PRootRunResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean,
)

class PRootProcessRunner(private val paths: PRootRuntimePaths) {
    suspend fun run(request: PRootRunRequest): PRootRunResult = withContext(Dispatchers.IO) {
        require(request.rootfs.isDirectory) { "rootfs is unavailable" }
        coroutineContext.ensureActive()
        PRootProcessOwner(
            PRootCommand.build(paths, request),
            PRootCommand.environment(paths, request),
            request.maxOutputBytes,
        ).use { owner ->
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
            while (!owner.waitForStep()) {
                coroutineContext.ensureActive()
                check(System.nanoTime() < deadline) { "PRoot command timed out" }
            }
            coroutineContext.ensureActive()
            owner.result()
        }
    }
}

internal object PRootCommand {
    fun build(paths: PRootRuntimePaths, request: PRootRunRequest): List<String> {
        val args = mutableListOf(
            paths.binary.absolutePath,
            "-0",
            "-r",
            request.rootfs.absolutePath,
            "-w",
            "/root",
            "--link2symlink",
            "--kill-on-exit",
        )
        request.bindMounts.forEach { mount ->
            args += "-b"
            args += if (mount.guestPath == null) {
                mount.hostPath
            } else {
                "${mount.hostPath}:${mount.guestPath}"
            }
        }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "TERM=dumb",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash",
            "--noprofile",
            "--norc",
            "-c",
            request.script,
        )
        return args
    }

    fun environment(paths: PRootRuntimePaths, request: PRootRunRequest): Map<String, String> =
        buildMap {
            put("PROOT_LOADER", paths.loader.absolutePath)
            put("PROOT_TMP_DIR", paths.tempDirectory.absolutePath)
            put("LD_LIBRARY_PATH", "")
            if (request.assumeNoSeccomp) put("PROOT_NO_SECCOMP", "1")
        }
}

