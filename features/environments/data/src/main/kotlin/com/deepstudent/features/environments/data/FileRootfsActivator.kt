package com.deepstudent.features.environments.data

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.RootfsManifest
import com.deepstudent.features.environments.backend.RootfsActivator
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class FileRootfsActivator(
    private val paths: EnvironmentPaths,
) : RootfsActivator {
    override suspend fun activate(manifest: RootfsManifest, stagingToken: String): String {
        val staging = paths.stagingGeneration(stagingToken)
        val stagingRoot = paths.stagingRoot(stagingToken)
        val destination = paths.generation(stagingToken)
        val destinationRoot = paths.generationRoot(stagingToken)
        if (destination.exists()) {
            if (!RootfsMarker.verify(destinationRoot, manifest, stagingToken)) {
                fail("existing rootfs generation is not valid")
            }
            SafeFileTree.deleteRecursivelyNoFollow(staging)
        } else {
            if (!RootfsMarker.verify(stagingRoot, manifest, stagingToken)) {
                fail("staged rootfs is not valid")
            }
            destination.parentFile?.mkdirs()
            try {
                Files.move(
                    staging.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (failure: Exception) {
                fail("rootfs generation could not be activated atomically", failure)
            }
        }
        if (!RootfsMarker.verify(destinationRoot, manifest, stagingToken)) {
            fail("activated rootfs marker is invalid")
        }
        writeActivePointer(manifest, stagingToken)
        EnvironmentDataLog.event("rootfs-activated", "generation=$stagingToken manifest=${manifest.id}")
        return stagingToken
    }

    override suspend fun currentActive(manifest: RootfsManifest): String? {
        val pointer = paths.activePointer
        if (!Files.isRegularFile(pointer.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            val value = JsonValue.parseObject(pointer.readText(StandardCharsets.UTF_8), "active rootfs")
            val generation = JsonFields.string(value, "generationId")
            val sha = JsonFields.string(value, "sha256")
            if (sha != manifest.sha256) return null
            if (!RootfsMarker.verify(paths.generationRoot(generation), manifest, generation)) return null
            generation
        }.getOrNull()
    }

    private fun writeActivePointer(manifest: RootfsManifest, generation: String) {
        val payload = JsonValue.Object.Builder()
            .put("generationId", generation)
            .put("sha256", manifest.sha256)
            .build()
            .canonical()
        val pointer = paths.activePointer
        pointer.parentFile?.mkdirs()
        val temporary = File(pointer.parentFile, "${pointer.name}.part")
        Files.write(
            temporary.toPath(),
            payload.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        try {
            Files.move(
                temporary.toPath(),
                pointer.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary.toPath())
            fail("active rootfs pointer could not be committed", failure)
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw EnvironmentCommandException(
            EnvironmentFailure(
                EnvironmentFailureCode.CORRUPT_STATE,
                message,
                recoverable = true,
            ),
            cause,
        )
}
