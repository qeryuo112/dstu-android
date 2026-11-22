package com.deepstudent.services.resources.data.assets

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.resources.backend.assets.AssetManifestCatalog
import com.deepstudent.services.resources.backend.assets.AssetManifestCodec
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ActiveAssetScannerTest {
    private val root = Files.createTempDirectory("s208-scan-").toFile()
    private val active = root.resolve("active").apply { mkdirs() }
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "fixtures/templates-assets.json").isFile }
    private val fixture = JsonValue.parseObject(repositoryRoot.resolve("fixtures/templates-assets.json").readText())

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun scansFrozenFixtureBytesOnlyInTwoRootsAndIgnoresSymlinks() {
        val assets = (fixture["assets"] as JsonValue.Array).values.map { it as JsonValue.Object }
        for (asset in assets) {
            val key = JsonFields.string(asset, "key")
            root.resolve(key).apply { requireNotNull(parentFile).mkdirs(); writeBytes(hex(JsonFields.string(asset, "bytesHex"))) }
        }
        active.resolve("images").apply { mkdirs(); resolve("ignored.bin").writeText("not in scope") }
        val external = root.resolve("external.txt").apply { writeText("not in scope") }
        val media = active.resolve("anki_media")
        Files.createSymbolicLink(media.resolve("link.wav").toPath(), external.toPath())
        Files.createSymbolicLink(media.resolve("link-dir").toPath(), root.toPath())
        val result = ActiveAssetScanner(root.resolve("active")).scan()
        assertEquals(2, result.skippedLinks)
        assertEquals(0, result.skippedSpecial)
        assertEquals(3, result.entries.size)
        for (asset in assets) {
            val key = JsonFields.string(asset, "key")
            val entry = result.entries.single { it.key.value == key }
            assertEquals(JsonFields.string(asset, "sha256"), entry.digest.sha256)
            assertEquals(JsonFields.integer(asset, "size"), entry.digest.size)
        }
        val catalog = AssetManifestCatalog.fromScan(result.entries, "scan-time")
        assertEquals(catalog, AssetManifestCodec.decode(AssetManifestCodec.encode(catalog)))
    }

    @Test fun rejectsDesktopEncodedOrCaseConflictingNamesRatherThanLyingAboutKeys() {
        val media = active.resolve("anki_media").apply { mkdirs() }
        val invalid = media.resolve("a:b").apply { writeText("x") }
        assertThrows(com.deepstudent.services.resources.api.assets.AssetScanException::class.java) { ActiveAssetScanner(root.resolve("active")).scan() }
        invalid.delete()
        media.resolve("File.txt").writeText("one")
        media.resolve("file.txt").writeText("two")
        assertThrows(IllegalStateException::class.java) { ActiveAssetScanner(root.resolve("active")).scan() }
    }

    @Test fun scanLimitsAndSymlinkedActiveRootFailClosed() {
        val media = active.resolve("anki_media").apply { mkdirs() }
        media.resolve("large.bin").writeBytes(ByteArray(16) { 1 })
        assertThrows(IllegalStateException::class.java) {
            ActiveAssetScanner(root.resolve("active"), AssetScanLimits(maxFileBytes = 15)).scan()
        }
        val data = ActiveAssetScanner(root.resolve("active"), AssetScanLimits(maxFileBytes = 16)).scan()
        assertEquals(Digests.sha256Hex(ByteArray(16) { 1 }), data.entries.single().digest.sha256)
        active.deleteRecursively()
        val outside = root.resolve("outside").apply { mkdirs() }
        Files.createSymbolicLink(active.toPath(), outside.toPath())
        assertThrows(Exception::class.java) { ActiveAssetScanner(root.resolve("active")).scan() }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
