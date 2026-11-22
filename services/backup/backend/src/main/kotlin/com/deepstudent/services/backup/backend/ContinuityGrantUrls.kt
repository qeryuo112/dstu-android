package com.deepstudent.services.backup.backend

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

private val SECRET_KEY = Regex("(?i)(password|passwd|secret|token|authorization|credential|access[_-]?key|api[_-]?key|private[_-]?key)")

/** repositoryUrl is the independent paired WebDAV base, not the sidecar's endpoint hint. */
internal fun grantHttpsUrl(value: String) {
    grantText(value, 2048)
    grantRequire(value.all { it.code in 33..126 })
    val uri = grantUri(value)
    grantUrlSchemeAndPath(uri)
    grantUrlAuthority(uri)
    grantPath(uri.rawPath, allowLeading = true)
}

private fun grantUrlSchemeAndPath(uri: URI) {
    grantRequire(uri.scheme == "https" && uri.host != null && uri.host == uri.host.lowercase(Locale.ROOT))
    grantRequire(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    grantRequire(uri.port != 443 && (uri.port == -1 || uri.port in 1..65535))
    grantRequire(uri.rawPath.startsWith('/') && uri.rawPath.endsWith('/') && uri.normalize() == uri)
}

private fun grantUrlAuthority(uri: URI) {
    val authority = uri.rawAuthority
    grantRequire(authority != null && '@' !in authority)
    val suffix = if (uri.port == -1) "" else ":${uri.port}"
    grantRequire(authority == uri.host + suffix)
}

/** Retain the frozen hint verbatim, while rejecting credentials and ambiguous path components. */
internal fun grantEndpointHint(value: String) {
    grantText(value, 4096)
    grantRequire('?' !in value && '#' !in value)
    for (part in value.split('|')) grantHintPart(part)
}

private fun grantHintPart(part: String) {
    val key = part.substringBefore('=', "")
    val field = part.substringAfter('=', part)
    grantRequire(!SECRET_KEY.containsMatchIn(key))
    if (field.contains("://")) grantHintUrl(field)
    if (key.lowercase(Locale.ROOT).substringAfterLast('.') == "root" && field.isNotEmpty()) grantRawRoot(field)
}

private fun grantHintUrl(field: String) {
    val uri = grantUri(field)
    grantRequire(uri.scheme in setOf("https", "http", "ftp", "file", "s3"))
    grantRequire(uri.host != null || (uri.scheme == "file" && uri.rawAuthority == null))
    grantRequire(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    if (uri.rawPath.isNotEmpty()) grantPath(uri.rawPath, allowLeading = true)
}

private fun grantUri(value: String): URI = try { URI(value) }
catch (_: URISyntaxException) { throw ContinuityGrantException(ContinuityGrantFailure.INVALID_FORMAT) }

private fun grantPath(path: String, allowLeading: Boolean) {
    grantRequire(path.isNotEmpty() && (allowLeading || !path.startsWith('/')))
    val clean = if (allowLeading) path.removePrefix("/") else path
    val parts = if (clean.isEmpty()) emptyList() else clean.removeSuffix("/").split('/')
    grantRequire(parts.none { it.isEmpty() })
    for (segment in parts) grantPathSegment(segment)
}

private fun grantPathSegment(segment: String) {
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < segment.length) {
        val char = segment[index]
        if (char == '%') {
            grantRequire(index + 2 < segment.length)
            val encoded = segment.substring(index + 1, index + 3)
            grantRequire(encoded.all { it in '0'..'9' || it in 'A'..'F' })
            bytes.write(encoded.toInt(16))
            index += 3
        } else {
            grantRequire(char.code in 33..126 && char != '\\')
            bytes.write(char.code)
            index++
        }
    }
    val decoded = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (_: CharacterCodingException) { throw ContinuityGrantException(ContinuityGrantFailure.INVALID_FORMAT) }
    grantRequire(decoded != "." && decoded != ".." && decoded.none(::grantPathInvalidChar))
}

private fun grantPathInvalidChar(char: Char): Boolean = Character.isISOControl(char) ||
    Character.getType(char) == Character.FORMAT.toInt() || char in "/\\%?#"


/** Frozen endpointHint root is raw WebDavPaths input, not URL or URL-decoded path. */
private fun grantRawRoot(root: String) {
    val parts = root.removeSuffix("/").split('/')
    grantRequire(parts.none { part -> part.isEmpty() || part == "." || part == ".." ||
        part.any { it == '\\' || Character.isISOControl(it) } })
}
