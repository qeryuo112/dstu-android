package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.RootfsManifest
import java.io.File

internal class EnvironmentPaths(
    private val base: File,
    runtimeDirectory: File = File(base, "runtime"),
    val dnsServers: List<String> = emptyList(),
) {
    val root: File = base
    val journal: File = File(base, "state/environment.json")
    val activePointer: File = File(base, "state/active.json")
    val runtime: File = runtimeDirectory
    val resolvConf: File = File(runtime, "resolv.conf")
    val fipsEnabled: File = File(runtime, "fips_enabled")

    fun archive(manifest: RootfsManifest): File = File(base, "archive/${manifest.sha256}.tar.xz")

    fun archivePart(manifest: RootfsManifest): File =
        File(base, "archive/${manifest.sha256}.tar.xz.part")

    fun stagingGeneration(generationId: String): File = File(base, "staging/${safeGeneration(generationId)}")

    fun stagingRoot(generationId: String): File = File(stagingGeneration(generationId), "rootfs")

    fun generation(generationId: String): File = File(base, "generations/${safeGeneration(generationId)}")

    fun generationRoot(generationId: String): File = File(generation(generationId), "rootfs")

    private fun safeGeneration(value: String): String {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) { "invalid rootfs generation id" }
        return value
    }

    fun ensureDirectories() {
        listOf("archive", "state", "staging", "generations").forEach {
            File(base, it).mkdirs()
        }
        runtime.mkdirs()
    }
}
