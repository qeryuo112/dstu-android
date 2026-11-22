package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Locally published result of a trusted, already activated continuity operation. Constructing this
 * value does not authenticate a sidecar, retire a writer, verify a claim, or activate a DataSpace.
 * The caller must independently establish those facts before publishing it to LocalSyncStore.
 * [repositoryUrl] is the canonical HTTPS WebDAV endpoint plus root, with a trailing slash.
 */
data class ContinuitySyncBinding(
    val operationId: String,
    val writerId: String,
    val instanceId: String,
    val repositoryUrl: String,
) {
    override fun toString(): String = "ContinuitySyncBinding(redacted)"

    companion object {
        /** Compute exactly the same canonical base as the WebDAV provider, without I/O or secrets. */
        @JvmStatic
        fun repositoryUrl(configuration: SyncConfiguration): String = repositoryUrl(configuration.endpoint, configuration.root)

        internal fun repositoryUrl(address: String, root: String): String {
            val endpoint = validatedHttpsUrl(address)
            return WebDavPaths.append(endpoint, root, true).toString()
        }
    }
}

internal fun ContinuitySyncBinding.validate() {
    val uuid = try { UUID.fromString(operationId) } catch (_: IllegalArgumentException) { invalidBinding() }
    if (uuid.toString() != operationId || uuid.variant() != 2 || uuid.version() !in 1..8) invalidBinding()
    validateContinuityIdentity(writerId)
    validateContinuityIdentity(instanceId)
    val url = validatedHttpsUrl(repositoryUrl)
    if (WebDavPaths.append(url, "", true).toString() != repositoryUrl) {
        invalidBinding()
    }
}

internal fun ContinuitySyncBinding.matches(configuration: SyncConfiguration): Boolean =
    configuration.deviceId == writerId && configuration.instanceId == instanceId &&
        ContinuitySyncBinding.repositoryUrl(configuration) == repositoryUrl

private fun validatedHttpsUrl(input: String): HttpUrl {
    val afterScheme = input.trim().substringAfter(':', "").trimStart('/', '\\')
    val authority = afterScheme.takeWhile { it != '/' && it != '\\' && it != '?' && it != '#' }
    if ('@' in authority) invalidBinding()
    val url = input.toHttpUrlOrNull() ?: invalidBinding()
    if (unsafeHttpUrl(url)) invalidBinding()
    return url
}

private fun unsafeHttpUrl(url: HttpUrl): Boolean = url.scheme != "https" || url.username.isNotEmpty() ||
    url.password.isNotEmpty() || url.query != null || url.fragment != null

private fun validateContinuityIdentity(value: String) {
    if (invalidIdentityText(value)) invalidBinding()
    val encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val bytes = try { encoder.encode(CharBuffer.wrap(value)).remaining() } catch (_: Exception) { invalidBinding() }
    if (bytes > 256) invalidBinding()
}

private fun invalidIdentityText(value: String): Boolean = value.isBlank() || value.length > 160 ||
    value == "." || value == ".." || value.any { it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }

private fun invalidBinding(): Nothing = throw SyncFailure(SyncError.INVALID_CONFIGURATION)
