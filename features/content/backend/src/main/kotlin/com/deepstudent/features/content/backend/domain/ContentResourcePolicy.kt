package com.deepstudent.features.content.backend.domain

import com.deepstudent.features.content.api.ContentResource
import com.deepstudent.features.content.api.ContentResources
import java.net.URI

class ContentResourcePolicy(resources: List<ContentResource>, private val maxBytes: Int = 8 * 1024 * 1024) : ContentResources {
    private val entries: Map<String, ContentResource>

    init {
        require(resources.size <= 128)
        var size = 0L
        val result = mutableMapOf<String, ContentResource>()
        resources.forEach { resource ->
            require(validPath(resource.path) && allowedMime(resource.mimeType))
            size += resource.bytes.size
            require(size <= maxBytes && resource.path !in result)
            result[resource.path] = resource.copy(bytes = resource.bytes.copyOf())
        }
        entries = result.toMap()
    }

    override fun find(path: String): ContentResource? =
        if (validPath(path)) entries[path]?.let { it.copy(bytes = it.bytes.copyOf()) } else null

    companion object {
        fun validPath(path: String): Boolean {
            if (path.isEmpty() || path.length > 512 || path.startsWith('/') || path.endsWith('/')) return false
            return path.none(::forbiddenCharacter) && path.split('/').all(::validSegment)
        }

        private fun forbiddenCharacter(char: Char): Boolean = char in "%\\?#" || char.code < 0x20
        private fun validSegment(segment: String): Boolean = segment.isNotEmpty() && segment != "." && segment != ".."

        private fun allowedMime(mime: String): Boolean = mime in setOf(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "audio/mpeg", "audio/ogg", "audio/wav",
        )

        fun isSafeLink(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && value.length <= 2048
        }.getOrDefault(false)
    }
}
