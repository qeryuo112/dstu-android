package com.deepstudent.features.environments.data

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.environments.api.RootfsManifest
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object RootfsMarker {
    const val FILE_NAME = ".dstu-rootfs.json"

    fun write(root: File, manifest: RootfsManifest, generationId: String) {
        val marker = File(root, FILE_NAME)
        require(!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "rootfs marker already exists"
        }
        val payload = JsonValue.Object.Builder()
            .put("manifestId", manifest.id)
            .put("sha256", manifest.sha256)
            .put("generationId", generationId)
            .put("entryCount", manifest.entryCount.toLong())
            .build()
            .canonical()
        val temporary = File(root, "$FILE_NAME.part")
        Files.write(
            temporary.toPath(),
            payload.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        Files.move(
            temporary.toPath(),
            marker.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun verify(root: File, manifest: RootfsManifest, generationId: String): Boolean {
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val marker = File(root, FILE_NAME)
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            val value = JsonValue.parseObject(marker.readText(StandardCharsets.UTF_8), "rootfs marker")
            JsonFields.string(value, "manifestId") == manifest.id &&
                JsonFields.string(value, "sha256") == manifest.sha256 &&
                JsonFields.string(value, "generationId") == generationId &&
                JsonFields.integer(value, "entryCount") == manifest.entryCount.toLong()
        }.getOrDefault(false)
    }
}
