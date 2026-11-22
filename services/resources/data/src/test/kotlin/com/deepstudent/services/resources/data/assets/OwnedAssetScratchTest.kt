package com.deepstudent.services.resources.data.assets

import com.deepstudent.services.resources.api.assets.AssetCleanupPolicy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OwnedAssetScratchTest {
    private val root = Files.createTempDirectory("s208-scratch-")
    @After fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun removesOnlyOwnStaleRegularFilesNotForeignOrSymlinkTargets() {
        val scratch = OwnedAssetScratch(root.toFile())
        scratch.withFile { it.writeText("transient"); assertTrue(it.exists()) }
        val dir = root.resolve("s208-owned-scratch")
        val stale = dir.resolve("s208-${UUID.randomUUID()}.tmp")
        val foreign = dir.resolve("not-owned.tmp")
        val outside = root.resolve("outside.txt")
        val link = dir.resolve("s208-${UUID.randomUUID()}.tmp")
        stale.toFile().writeText("stale")
        foreign.toFile().writeText("foreign")
        outside.toFile().writeText("keep")
        Files.createSymbolicLink(link, outside)
        val result = scratch.cleanup()
        assertEquals(AssetCleanupPolicy.REMOVE_S208_OWN_SCRATCH, result.policy)
        assertEquals(1, result.removed)
        assertEquals(2, result.skipped)
        assertFalse(Files.exists(stale))
        assertEquals("foreign", foreign.toFile().readText())
        assertEquals("keep", outside.toFile().readText())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun cleanupWaitsForLiveCooperatingWriterAndNeverUnlinksIt() {
        val scratch = OwnedAssetScratch(root.toFile())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit { scratch.withFile { file ->
                file.writeText("live"); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); assertTrue(file.exists())
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val cleanup = executor.submit<AssetCleanupResultHolder> {
                cleanupStarted.countDown()
                AssetCleanupResultHolder(scratch.cleanup().removed)
            }
            assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertFalse(cleanup.isDone)
            release.countDown()
            writer.get(3, TimeUnit.SECONDS)
            assertEquals(0, cleanup.get(3, TimeUnit.SECONDS).removed)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun rejectsScratchDirectorySymlinkInsteadOfDeletingOutside() {
        val outside = Files.createTempDirectory("s208-outside-")
        try {
            val victim = outside.resolve("s208-${UUID.randomUUID()}.tmp")
            victim.toFile().writeText("keep")
            Files.createSymbolicLink(root.resolve("s208-owned-scratch"), outside)
            assertThrows(IllegalStateException::class.java) { OwnedAssetScratch(root.toFile()).cleanup() }
            assertEquals("keep", victim.toFile().readText())
        } finally { outside.toFile().deleteRecursively() }
    }
}
private data class AssetCleanupResultHolder(val removed: Int)
