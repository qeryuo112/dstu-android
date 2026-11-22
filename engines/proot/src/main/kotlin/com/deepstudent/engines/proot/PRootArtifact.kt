package com.deepstudent.engines.proot

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

enum class PRootProvenance {
    SOURCE_LOCKED,
    UNRESOLVED_PRECOMPILED,
}

data class PRootArtifactManifest(
    val id: String,
    val binarySha256: String,
    val binaryBytes: Long,
    val loaderSha256: String,
    val loaderBytes: Long,
    val abi: String = "arm64-v8a",
    val minSdk: Int = 24,
) {
    init {
        require(id.isNotBlank()) { "PRoot artifact id must not be blank" }
        require(binarySha256.matches(Regex("[0-9a-f]{64}"))) { "invalid PRoot binary hash" }
        require(loaderSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid PRoot loader hash" }
        require(binaryBytes > 0 && loaderBytes > 0) { "invalid PRoot artifact size" }
        require(abi == "arm64-v8a") { "only arm64-v8a is supported" }
        require(minSdk >= 24) { "PRoot requires API 24 or newer" }
    }
}

data class PRootArtifact(
    val manifest: PRootArtifactManifest,
    val binary: File,
    val loader: File,
    val provenance: PRootProvenance,
)

data class PRootRuntimePaths(
    val binary: File,
    val loader: File,
    val tempDirectory: File,
    val link2SymlinkDirectory: File,
)

class PRootUnavailableException(message: String) : IllegalStateException(message)

class PRootRuntimeInstaller(
    private val elfVerifier: PRootElfVerifier = PRootElfVerifier(),
) {
    /**
     * The runtime accepts only artifacts built from the explicitly locked sources.
     * There is no device-test switch that can waive this production policy.
     */
    fun prepare(
        artifact: PRootArtifact,
        targetDirectory: File,
        executableDirectory: File? = null,
    ): PRootRuntimePaths {
        if (artifact.provenance != PRootProvenance.SOURCE_LOCKED) {
            throw PRootUnavailableException(
                "PRoot artifact source/license is unresolved; production execution is fail-closed",
            )
        }
        Files.createDirectories(targetDirectory.toPath())
        val binary = if (executableDirectory == null) {
            installFile(
                artifact.binary,
                File(targetDirectory, "proot"),
                artifact.manifest.binaryBytes,
                artifact.manifest.binarySha256,
            )
        } else {
            verifyExecutable(artifact.binary, artifact.manifest.binaryBytes, artifact.manifest.binarySha256)
        }
        val loader = if (executableDirectory == null) {
            installFile(
                artifact.loader,
                File(targetDirectory, "loader"),
                artifact.manifest.loaderBytes,
                artifact.manifest.loaderSha256,
            )
        } else {
            verifyExecutable(artifact.loader, artifact.manifest.loaderBytes, artifact.manifest.loaderSha256)
        }
        elfVerifier.requireAndroidAarch64Pie(binary)
        val temp = File(targetDirectory, "tmp").also { Files.createDirectories(it.toPath()) }
        val links = File(targetDirectory, "l2s").also { Files.createDirectories(it.toPath()) }
        return PRootRuntimePaths(binary, loader, temp, links)
    }

    private fun installFile(
        source: File,
        target: File,
        expectedBytes: Long,
        expectedSha256: String,
    ): File {
        if (target.isFile && !Files.isSymbolicLink(target.toPath())) {
            if (target.length() == expectedBytes && sha256(target) == expectedSha256) {
                target.setExecutable(true, true)
                return target
            }
        }
        verifySource(source, expectedBytes, expectedSha256)
        val temporary = File(target.parentFile, "${target.name}.part")
        Files.deleteIfExists(temporary.toPath())
        try {
            Files.newInputStream(source.toPath(), StandardOpenOption.READ).use { input ->
                Files.newOutputStream(
                    temporary.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            check(temporary.length() == expectedBytes) { "PRoot artifact changed while copying" }
            check(sha256(temporary) == expectedSha256) { "PRoot artifact changed while copying" }
            temporary.setExecutable(true, true)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            target.setExecutable(true, true)
            return target
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun verifyExecutable(source: File, expectedBytes: Long, expectedSha256: String): File {
        verifySource(source, expectedBytes, expectedSha256)
        return source
    }

    private fun verifySource(source: File, expectedBytes: Long, expectedSha256: String) {
        check(!Files.isSymbolicLink(source.toPath())) { "PRoot artifact must not be a symlink" }
        check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "PRoot artifact is unavailable"
        }
        check(source.length() == expectedBytes) { "PRoot artifact size mismatch" }
        check(sha256(source) == expectedSha256) { "PRoot artifact hash mismatch" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
