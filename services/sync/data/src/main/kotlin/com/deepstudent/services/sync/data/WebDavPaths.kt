package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import okhttp3.HttpUrl

internal object WebDavPaths {
    fun segments(path: String, directory: Boolean): List<String> {
        val clean = if (directory && path.endsWith('/')) path.dropLast(1) else path
        val parts = if (clean.isEmpty()) emptyList() else clean.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || it.any(Char::isISOControl) }) {
            throw SyncFailure(SyncError.INVALID_CONFIGURATION)
        }
        return parts
    }

    fun append(base: HttpUrl, path: String, directory: Boolean = false): HttpUrl {
        val parts = segments(path, directory)
        val builder = base.newBuilder()
        if (!base.encodedPath.endsWith('/')) builder.addPathSegment("")
        for (part in parts) builder.addPathSegment(part)
        if (directory && parts.isNotEmpty()) builder.addPathSegment("")
        return builder.build()
    }
}
