package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.services.providers.api.ProviderHeader

/**
 * Configured custom header merge for the S3.08 relay/local boundary, mirroring the
 * frozen `prepared_request.ts` merge semantics: valid names only, no CR/LF/NUL in
 * values, appended case-insensitively only when the request does not carry the name.
 */
object RelayHeaders {
    // RFC 7230 token characters, identical to the frozen desktop HEADER_NAME regex.
    private val HEADER_NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    private val FORBIDDEN_VALUE = Regex("[\\r\\n\\u0000]")

    /** Name is valid and the value carries no line-break injection; desktop `appendProviderHeaderIfAbsent`. */
    fun isApplicable(name: String, value: String): Boolean = HEADER_NAME.matches(name) && !FORBIDDEN_VALUE.containsMatchIn(value)

    /** Appends `name=value` unless an existing header already uses the name (case-insensitive). */
    fun appendIfAbsent(headers: MutableList<NetworkHeader>, name: String, value: String) {
        if (!isApplicable(name, value)) return
        val lower = name.lowercase()
        if (headers.any { it.name.lowercase() == lower }) return
        headers += NetworkHeader(lower, value)
    }

    /** Merges configured vendor headers; the desktop only filters these for the model list. */
    fun mergeConfigured(headers: MutableList<NetworkHeader>, configured: List<ProviderHeader>) {
        for (header in configured) appendIfAbsent(headers, header.name, header.value)
    }
}
