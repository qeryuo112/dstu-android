package com.deepstudent.engines.proot

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PRootArtifactTest {
    @Test
    fun unresolvedPrecompiledArtifactFailsClosedByDefault() {
        val directory = Files.createTempDirectory("dstu-proot-policy").toFile()
        try {
            val binary = File(directory, "libproot.so").apply { writeBytes(elf()) }
            val loader = File(directory, "loader").apply { writeText("loader") }
            val artifact = artifact(binary, loader, PRootProvenance.UNRESOLVED_PRECOMPILED)
            val thrown = runCatching {
                PRootRuntimeInstaller().prepare(artifact, File(directory, "runtime"))
            }.exceptionOrNull()
            assertTrue(thrown is PRootUnavailableException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sourceLockedReleaseCopiesAndVerifiesExecutable() {
        val directory = Files.createTempDirectory("dstu-proot-copy").toFile()
        try {
            val binary = File(directory, "libproot.so").apply { writeBytes(elf()) }
            val loader = File(directory, "loader").apply { writeText("loader") }
            val artifact = artifact(binary, loader, PRootProvenance.SOURCE_LOCKED)
            val paths = PRootRuntimeInstaller().prepare(
                artifact,
                File(directory, "runtime"),
            )
            assertEquals(artifact.manifest.binarySha256, sha256(paths.binary))
            assertTrue(paths.binary.canExecute())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun elfRejectsNon16KiBLoadSegment() {
        val directory = Files.createTempDirectory("dstu-proot-elf").toFile()
        try {
            val file = File(directory, "bad").apply { writeBytes(elf(align = 0x1000)) }
            val thrown = runCatching { PRootElfVerifier().requireAndroidAarch64Pie(file) }
                .exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun commandUsesArgumentVectorAndFixedGuestShell() {
        val root = File("/tmp/root")
        val paths = PRootRuntimePaths(
            File("/tmp/proot"),
            File("/tmp/loader"),
            File("/tmp/tmp"),
            File("/tmp/l2s"),
        )
        val request = PRootRunRequest(
            rootfs = root,
            script = "printf hello",
            bindMounts = listOf(PRootBindMount("/dev")),
        )
        val command = PRootCommand.build(paths, request)
        assertTrue(command.contains("--link2symlink"))
        assertTrue(command.contains("--kill-on-exit"))
        assertEquals("printf hello", command.last())
        assertEquals("/bin/bash", command[command.lastIndex - 4])
    }

    private fun artifact(binary: File, loader: File, provenance: PRootProvenance): PRootArtifact =
        PRootArtifact(
            PRootArtifactManifest(
                id = "test",
                binarySha256 = sha256(binary),
                binaryBytes = binary.length(),
                loaderSha256 = sha256(loader),
                loaderBytes = loader.length(),
            ),
            binary,
            loader,
            provenance,
        )

    private fun sha256(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun elf(align: Int = 0x4000): ByteArray {
        val bytes = ByteArray(64 + 56)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        buffer.putShort(16, 3)
        buffer.putShort(18, 183)
        buffer.putLong(32, 64)
        buffer.putShort(54, 56)
        buffer.putShort(56, 1)
        buffer.putInt(64, 1)
        buffer.putLong(72, 0)
        buffer.putLong(80, 0)
        buffer.putLong(112, align.toLong())
        return bytes
    }
}
