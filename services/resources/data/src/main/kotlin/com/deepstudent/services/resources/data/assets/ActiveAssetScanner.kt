package com.deepstudent.services.resources.data.assets

import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.AssetScanEntry
import com.deepstudent.services.resources.api.assets.AssetScanResult
import com.deepstudent.services.resources.api.assets.AssetScanFailure
import com.deepstudent.services.resources.api.assets.AssetScanException
import com.deepstudent.services.resources.backend.assets.AssetScanner
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Scans only active/anki_media and active/chat_attachments in the selected private slot. Logical asset keys remain active/... after a switch.
 * Every child is opened relative to an anchored directory handle with NOFOLLOW_LINKS. Providers
 * without SecureDirectoryStream are refused, not downgraded to a path-based traversal.
 */
class ActiveAssetScanner(private val activeRoot: File, private val limits: AssetScanLimits = AssetScanLimits()) : AssetScanner {
    override fun scan(): AssetScanResult {
        val selected = activeRoot.toPath().toAbsolutePath()
        val parent = requireNotNull(selected.parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "invalid private slot parent" }
        val result = ScanAccumulator(limits)
        Files.newDirectoryStream(parent).use { directory ->
            val secure = directory as? SecureDirectoryStream<Path> ?: throw AssetScanException(AssetScanFailure.SECURE_HANDLES_UNAVAILABLE)
            secure.newDirectoryStream(selected.fileName, NOFOLLOW_LINKS).use { active ->
                scanRoot(active, "anki_media", result)
                scanRoot(active, "chat_attachments", result)
            }
        }
        return AssetScanResult(result.entries.sortedBy { it.key.value }, result.skippedLinks, result.skippedSpecial)
    }

    private fun scanRoot(active: SecureDirectoryStream<Path>, top: String, result: ScanAccumulator) {
        val name = Paths.get(top)
        val attrs = try { active.attrs(name) } catch (_: NoSuchFileException) { return }
        if (attrs.isSymbolicLink) { result.skippedLinks++; return }
        require(attrs.isDirectory) { "invalid asset root" }
        active.newDirectoryStream(name, NOFOLLOW_LINKS).use { walk(it, "active/$top", 0, result) }
    }

    private fun walk(dir: SecureDirectoryStream<Path>, prefix: String, depth: Int, result: ScanAccumulator) {
        if (depth > limits.maxDepth) throw AssetScanException(AssetScanFailure.LIMIT)
        val names = mutableSetOf<String>()
        for (path in dir) {
            result.visit()
            walkEntry(dir, path.fileName, prefix, depth, result, names)
        }
    }

    private fun walkEntry(dir: SecureDirectoryStream<Path>, name: Path, prefix: String, depth: Int,
        result: ScanAccumulator, names: MutableSet<String>,
    ) {
        val attrs = dir.attrs(name)
        if (attrs.isSymbolicLink) { result.skippedLinks++; return }
        if (!attrs.isRegularFile && !attrs.isDirectory) { result.skippedSpecial++; return }
        val segment = portableSegment(name.toString())
        if (!names.add(segment.lowercase(java.util.Locale.ROOT))) throw AssetScanException(AssetScanFailure.COLLISION)
        val key = "$prefix/$segment"
        validatePortableKeyLength(key)
        if (attrs.isDirectory) {
            dir.newDirectoryStream(name, NOFOLLOW_LINKS).use { walk(it, key, depth + 1, result) }
        } else {
            result.add(dir, name, key, attrs)
        }
    }

}

data class AssetScanLimits(
    val maxEntries: Int = 20_000,
    val maxFiles: Int = 10_000,
    val maxDepth: Int = 32,
    val maxFileBytes: Long = 512L * 1024 * 1024,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    init { require(maxEntries > 0 && maxFiles > 0 && maxDepth > 0 && maxFileBytes >= 0 && maxTotalBytes >= 0) }
}

private class ScanAccumulator(private val limits: AssetScanLimits) {
    val entries = mutableListOf<AssetScanEntry>()
    var skippedLinks = 0
    var skippedSpecial = 0
    private var visited = 0
    private var total = 0L

    fun visit() { if (++visited > limits.maxEntries) throw AssetScanException(AssetScanFailure.LIMIT) }

    fun add(dir: SecureDirectoryStream<Path>, name: Path, key: String, before: BasicFileAttributes) {
        validateBudget(before)
        val options: Set<OpenOption> = setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)
        val digest = dir.newByteChannel(name, options).use { hash(it, before.size()) }
        verifyUnchanged(before, dir.attrs(name))
        total += before.size()
        entries += AssetScanEntry(AssetKey.parse(key), AssetDigest(digest, before.size()), before.lastModifiedTime().toInstant().toString())
    }

    private fun validateBudget(before: BasicFileAttributes) {
        if (entries.size >= limits.maxFiles || before.size() > limits.maxFileBytes ||
            before.size() > limits.maxTotalBytes - total) throw AssetScanException(AssetScanFailure.LIMIT)
        if (before.fileKey() == null) throw AssetScanException(AssetScanFailure.STABLE_ID_UNAVAILABLE)
    }

    private fun verifyUnchanged(before: BasicFileAttributes, after: BasicFileAttributes) {
        if (!after.isRegularFile || before.fileKey() != after.fileKey() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()) throw AssetScanException(AssetScanFailure.CHANGED)
    }

    private fun hash(channel: SeekableByteChannel, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var total = 0L
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            total += count
            if (total > size || total > limits.maxFileBytes) throw AssetScanException(AssetScanFailure.CHANGED)
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
        if (total != size) throw AssetScanException(AssetScanFailure.CHANGED)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private fun SecureDirectoryStream<Path>.attrs(name: Path): BasicFileAttributes =
    getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
