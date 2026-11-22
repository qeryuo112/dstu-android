package com.deepstudent.features.content.data

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import com.deepstudent.features.content.api.ContentResources
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class ContentResponses(
    private val assets: AssetManager,
    private val origin: String,
    private val nonce: String,
    private val page: String,
    private val resources: ContentResources,
) {
    private val active = AtomicBoolean(true)

    fun close() { active.set(false) }

    fun resolve(uri: Uri, mainFrame: Boolean): WebResourceResponse {
        if (!allowedOrigin(uri)) return reject()
        val path = uri.encodedPath ?: return reject()
        if (!allowedPath(path)) return reject()
        if (mainFrame) return document(uri, path)
        if (path == "/index.html") return reject()
        if (uri.encodedQuery != null) return reject()
        return if (path.startsWith("/resource/")) resource(path) else asset(path)
    }

    private fun document(uri: Uri, path: String): WebResourceResponse =
        if (path == "/index.html" && uri.encodedQuery == "nonce=$nonce") {
            bytes("text/html", page.toByteArray(Charsets.UTF_8))
        } else reject()

    private fun allowedOrigin(uri: Uri): Boolean =
        active.get() && "${uri.scheme}://${uri.encodedAuthority}" == origin && uri.fragment == null

    private fun allowedPath(path: String): Boolean =
        '%' !in path && '\\' !in path && path.split('/').none { it == "." || it == ".." }

    private fun resource(path: String): WebResourceResponse {
        val item = runCatching { resources.find(path.removePrefix("/resource/")) }.getOrNull() ?: return reject()
        return if (active.get()) bytes(item.mimeType, item.bytes) else reject()
    }

    private fun asset(path: String): WebResourceResponse {
        val file = when {
            path == "/host.js" -> "content/host.js"
            path in VENDOR -> "content$path"
            path.startsWith("/vendor/fonts/") && FONT.matches(path) -> "content$path"
            else -> return reject()
        }
        return runCatching { bytes(mime(file), assets.open(file).use { it.readBytes() }) }.getOrElse { reject() }
    }

    private fun mime(path: String): String = when (path.substringAfterLast('.')) {
        "js" -> "text/javascript"
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        else -> "font/ttf"
    }

    private fun bytes(mime: String, data: ByteArray) = WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(data))
    private fun reject() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    private companion object {
        val VENDOR = setOf("/vendor/katex.min.js", "/vendor/katex.min.css", "/vendor/mermaid.min.js")
        val FONT = Regex("/vendor/fonts/KaTeX_[A-Za-z0-9-]+\\.(ttf|woff2?)")
    }
}
