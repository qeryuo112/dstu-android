package com.deepstudent.services.providers.data

import com.deepstudent.services.providers.api.ProviderHeader

/**
 * Model-list request headers per dialect, mirroring the frozen `vendor_model_headers`:
 * configured custom headers go first (hop-by-hop names skipped, name/value validated),
 * then dialect auth headers are set and therefore win on the same lowercase name.
 */
object VendorModelHeaders {
    private val RESERVED_HEADERS = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "proxy-authorization",
        "authorization",
        "x-api-key",
        "x-goog-api-key",
    )
    private val HEADER_NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    private val HEADER_VALUE = Regex("[\\t\\x20-\\x7E\\x80-\\uFFFF]*")
    const val ANTHROPIC_VERSION: String = "2023-06-01"

    /**
     * Builds the ordered header list for one dialect. [apiKey] is the already-resolved
     * credential text; [headers] are the configured vendor headers.
     */
    fun build(headers: List<ProviderHeader>, apiKey: String, kind: ModelListEndpoint.Kind): List<ProviderHeader> {
        val merged = LinkedHashMap<String, ProviderHeader>()
        for (header in headers) {
            val name = header.name
            if (name.trim().lowercase() in RESERVED_HEADERS) continue
            requireHeaderName(name)
            requireHeaderValue(header.value, ModelListFailureCode.INVALID_HEADER_VALUE)
            merged[name.lowercase()] = header
        }
        applyAuthHeaders(merged, apiKey.trim(), kind)
        return merged.values.toList()
    }

    private fun requireHeaderName(name: String) {
        if (!HEADER_NAME.matches(name)) throw ModelListException(ModelListFailureCode.INVALID_HEADER_NAME)
    }

    private fun requireHeaderValue(value: String, failure: ModelListFailureCode) {
        if (!HEADER_VALUE.matches(value)) throw ModelListException(failure)
    }

    private fun requireKeyValue(key: String) {
        if (!HEADER_VALUE.matches(key)) throw ModelListException(ModelListFailureCode.INVALID_API_KEY_CHARACTERS)
    }

    /** Dialect auth headers override configured ones on the same name (desktop Map.set order). */
    private fun applyAuthHeaders(merged: LinkedHashMap<String, ProviderHeader>, key: String, kind: ModelListEndpoint.Kind) {
        when (kind) {
            ModelListEndpoint.Kind.GEMINI -> setIfKeyed(merged, key, "x-goog-api-key", key)

            ModelListEndpoint.Kind.ANTHROPIC -> {
                requireHeaderValue(ANTHROPIC_VERSION, ModelListFailureCode.INVALID_HEADER_VALUE)
                merged["anthropic-version"] = ProviderHeader("anthropic-version", ANTHROPIC_VERSION)
                setIfKeyed(merged, key, "x-api-key", key)
            }

            ModelListEndpoint.Kind.OPENAI -> setIfKeyed(merged, key, "Authorization", "Bearer $key")
        }
    }

    private fun setIfKeyed(merged: LinkedHashMap<String, ProviderHeader>, key: String, name: String, value: String) {
        if (key.isEmpty()) return
        requireKeyValue(key)
        merged[name] = ProviderHeader(name, value)
    }
}
