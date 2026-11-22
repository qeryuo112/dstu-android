package com.deepstudent.features.settings.api

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.text.Utf8Text
import java.net.URI

data class SanitizedPortableConfig(
    val kind: String,
    val items: List<JsonValue.Object>,
    val excludedLocal: List<String>,
    val excludedSecret: List<String>,
) {
    fun portableObject(): JsonValue.Object {
        val plural = if (kind == KIND_MODEL) "models" else "tools"
        return JsonValue.Object.Builder()
            .put("version", 1L)
            .put(plural, JsonValue.Array(items))
            .build()
    }

    fun encoded(): String = portableObject().literal()

    companion object {
        const val KIND_MODEL = "model"
        const val KIND_TOOL = "tool"
    }
}

object PortableConfigSanitizer {
    const val MAX_VALUE_BYTES = 1024 * 1024
    const val MAX_MODELS = 64
    const val MAX_TOOLS = 256

    val THEME_MODES: List<String> = listOf("light", "dark", "auto")
    val THEME_PALETTES: List<String> = listOf(
        "default",
        "purple",
        "green",
        "orange",
        "pink",
        "bright-pink",
        "teal",
        "muted",
        "paper",
        "custom",
    )

    private val themeKeys = setOf("theme", "dstu-theme-mode")
    private val paletteKeys = setOf("theme_palette", "dstu-theme-palette")
    private val colorKeys = setOf("theme_custom_color", "dstu-theme-custom-color")
    private val colorPattern = Regex("#[0-9a-fA-F]{6}")

    private val modelFields = listOf(
        "apiProtocol",
        "baseUrl",
        "contextWindow",
        "id",
        "isAudioTranscription",
        "isEmbedding",
        "isFavorite",
        "isImageGeneration",
        "isMultimodal",
        "isReasoning",
        "isReranker",
        "maxOutputTokens",
        "model",
        "name",
        "providerType",
        "supportsOpenAIResponses",
        "supportsTools",
        "temperature",
        "vendorId",
        "vendorName",
    )

    private val toolFields = listOf("description", "id", "name", "transportType")
    private val numericModelFields = setOf("maxOutputTokens", "temperature", "contextWindow")

    private val credentialKeys = setOf(
        "accesstoken", "apikey", "apikeys", "auth", "authorization",
        "bearer", "clientsecret", "cookie", "cookies", "credentials", "env",
        "environment", "envvars", "headers", "httpproxy", "oauth", "password",
        "privatekey", "processenv", "refreshtoken", "secret", "token",
    )

    private val credentialSuffixes = listOf(
        "apikey", "authorization", "bearer", "cookie", "credential", "credentials",
        "env", "environment", "headers", "httpproxy", "oauth", "password", "privatekey",
        "secret", "token",
    )

    private val executableFields = setOf(
        "args", "command", "cwd", "executable", "filepath", "localpath", "path",
        "permissions", "trust", "workingdir", "absolutepath",
    )

    private val executableSuffixes = listOf(
        "command", "cwd", "filepath", "localpath", "path", "workingdir",
    )

    fun normalizePortableValue(key: String, value: String): String {
        val classification = SettingClassification.classify(key)
        if (classification.scope != SettingScope.PORTABLE) {
            settingsError("NOT_PORTABLE", "$key is ${classification.scope.wire}: ${classification.reason}")
        }
        return when {
            key in themeKeys -> normalizeTheme(key, value)
            key in paletteKeys -> normalizePalette(key, value)
            key in colorKeys -> normalizeColor(key, value)
            key == "portable.model.preferences.v1" ->
                normalizeStructured(key, SanitizedPortableConfig.KIND_MODEL, value)
            key == "portable.tool.preferences.v1" ->
                normalizeStructured(key, SanitizedPortableConfig.KIND_TOOL, value)
            else -> validateGenericJson(value, key)
        }
    }

    private fun normalizeTheme(key: String, value: String): String {
        if (value !in THEME_MODES) settingsError("INVALID_VALUE", "$key must be light, dark, or auto")
        return value
    }

    private fun normalizePalette(key: String, value: String): String {
        if (value !in THEME_PALETTES) settingsError("INVALID_VALUE", "$key is not a known palette")
        return value
    }

    private fun normalizeColor(key: String, value: String): String {
        if (!colorPattern.matches(value)) settingsError("INVALID_VALUE", "$key must be #RRGGBB")
        return value.lowercase()
    }

    private fun normalizeStructured(key: String, kind: String, value: String): String =
        sanitizePortableConfig(kind, parseStructured(value, key)).encoded()

    fun sanitizePortableConfig(kind: String, input: JsonValue): SanitizedPortableConfig {
        if (kind != SanitizedPortableConfig.KIND_MODEL && kind != SanitizedPortableConfig.KIND_TOOL) {
            settingsError("UNKNOWN_KIND", "unsupported config kind $kind")
        }
        assertNoSecretFields(input, "$")
        val items = collection(input, kind)
        return if (kind == SanitizedPortableConfig.KIND_MODEL) {
            if (items.size > MAX_MODELS) settingsError("VALUE_TOO_LARGE", "model count exceeds $MAX_MODELS")
            SanitizedPortableConfig(
                kind = kind,
                items = items.mapIndexed { index, item -> sanitizeModel(item, "$.models[$index]") },
                excludedLocal = listOf("enabled", "authMode", "extraBody", "unknown fields"),
                excludedSecret = listOf("apiKey", "apiKeys", "headers", "credentials"),
            )
        } else {
            if (items.size > MAX_TOOLS) settingsError("VALUE_TOO_LARGE", "tool count exceeds $MAX_TOOLS")
            SanitizedPortableConfig(
                kind = kind,
                items = items.mapIndexed { index, item -> sanitizeTool(item, "$.tools[$index]") },
                excludedLocal = listOf(
                    "args", "command", "cwd", "enabled", "framing", "permissions",
                    "trust", "url", "unknown fields",
                ),
                excludedSecret = listOf("credentials", "env", "headers"),
            )
        }
    }

    fun parseStructured(value: String, label: String): JsonValue {
        if (Utf8Text.byteLength(value) > MAX_VALUE_BYTES) {
            settingsError("VALUE_TOO_LARGE", "$label exceeds 1 MiB")
        }
        return try {
            JsonValue.parse(value)
        } catch (failure: Exception) {
            settingsError("INVALID_JSON", "$label is not valid JSON")
        }
    }

    fun parseStructured(value: JsonValue, label: String): JsonValue {
        if (value is JsonValue.Text) return parseStructured(value.value, label)
        assertNoSecretFields(value, "$")
        return value
    }

    fun assertNoSecretFields(value: JsonValue, path: String) {
        when (value) {
            is JsonValue.Array -> value.values.forEachIndexed { index, item ->
                assertNoSecretFields(item, "$path[$index]")
            }
            is JsonValue.Object -> {
                for (key in value.keys()) {
                    val item = value[key] ?: JsonValue.Null
                    if (forbiddenCredentialField(key)) {
                        settingsError("NESTED_SECRET", "credential field rejected at $path.$key")
                    }
                    assertNoSecretFields(item, "$path.$key")
                }
            }
            else -> Unit
        }
    }

    fun assertNoExecutableFields(value: JsonValue, path: String) {
        when (value) {
            is JsonValue.Array -> value.values.forEachIndexed { index, item ->
                assertNoExecutableFields(item, "$path[$index]")
            }
            is JsonValue.Object -> {
                for (key in value.keys()) {
                    val normalized = normalizeCredentialKey(key)
                    if (normalized in executableFields || executableSuffixes.any { normalized.endsWith(it) }) {
                        settingsError("EXECUTABLE_FIELD", "executable or trust field rejected at $path.$key")
                    }
                    assertNoExecutableFields(value[key] ?: JsonValue.Null, "$path.$key")
                }
            }
            else -> Unit
        }
    }

    private fun sanitizeModel(model: JsonValue, path: String): JsonValue.Object {
        val source = model as? JsonValue.Object
            ?: settingsError("INVALID_VALUE", "$path must be an object")
        assertNoExecutableFields(source, path)
        val builder = JsonValue.Object.Builder()
        for (field in modelFields) {
            val item = source[field] ?: continue
            builder.put(field, portableModelField(field, item, path))
        }
        val result = builder.build()
        requireTextField(result, "id", path)
        requireTextField(result, "model", path)
        return result
    }

    private fun portableModelField(field: String, item: JsonValue, path: String): JsonValue =
        when {
            field == "baseUrl" -> portableUrl(item, path)
            field in numericModelFields -> finiteNumber(item, field, path)
            else -> scalar(item, field, path)
        }

    private fun requireTextField(value: JsonValue.Object, field: String, path: String) {
        val text = (value[field] as? JsonValue.Text)?.value
        if (text.isNullOrEmpty()) settingsError("INVALID_VALUE", "$path.$field is required")
    }

    private fun sanitizeTool(tool: JsonValue, path: String): JsonValue.Object {
        val source = tool as? JsonValue.Object
            ?: settingsError("INVALID_VALUE", "$path must be an object")
        assertToolNestedFields(source, path)
        val builder = JsonValue.Object.Builder()
        for (field in toolFields) {
            val item = source[field] ?: continue
            builder.put(field, scalar(item, field, path))
        }
        val result = builder.build()
        requireTextField(result, "id", path)
        return result
    }

    private fun assertToolNestedFields(source: JsonValue.Object, path: String) {
        for (key in source.keys()) {
            if (key in toolFields) continue
            val item = source[key] ?: JsonValue.Null
            if (item is JsonValue.Object || item is JsonValue.Array) {
                assertNoExecutableFields(item, "$path.$key")
            }
        }
    }

    private fun collection(input: JsonValue, kind: String): List<JsonValue> {
        if (input is JsonValue.Array) return input.values
        if (input is JsonValue.Object) {
            val plural = if (kind == SanitizedPortableConfig.KIND_MODEL) "models" else "tools"
            val items = input[plural]
            if (items is JsonValue.Array) return items.values
            return listOf(input)
        }
        settingsError("INVALID_VALUE", "$kind config must be an object or array")
    }

    private fun portableUrl(value: JsonValue, path: String): JsonValue.Text {
        val raw = (value as? JsonValue.Text)?.value
            ?: settingsError("INVALID_VALUE", "$path.baseUrl must be a non-empty string")
        if (raw.isEmpty() || raw.length > 2048) {
            settingsError("INVALID_VALUE", "$path.baseUrl must be a non-empty string")
        }
        val uri = parseUri(raw, path)
        validateUriParts(uri, path)
        validateUriHost(uri, path)
        return JsonValue.Text(raw)
    }

    private fun parseUri(raw: String, path: String): URI =
        try {
            URI(raw)
        } catch (failure: Exception) {
            settingsError("INVALID_VALUE", "$path.baseUrl is not a URL")
        }

    private fun validateUriParts(uri: URI, path: String) {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") ||
            uri.rawAuthority.isNullOrEmpty() ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            uri.port > 65535
        ) {
            settingsError("INVALID_VALUE", "$path.baseUrl must be credential-free http(s) without query or fragment")
        }
    }

    private fun validateUriHost(uri: URI, path: String) {
        if (uri.host.isNullOrEmpty() && !allowsIdnAuthority(uri.rawAuthority, uri.port)) {
            settingsError("INVALID_VALUE", "$path.baseUrl must include a host")
        }
    }

    /**
     * java.net.URI keeps an IDN authority raw instead of resolving a host.
     * Accept that frozen desktop-compatible form, but still reject an
     * unparseable port. IPv6 hosts are handled by URI with a non-null host.
     */
    private fun allowsIdnAuthority(authority: String, port: Int): Boolean =
        authority.any { it.code > 0x7f } && (':' !in authority || port >= 0)

    private fun finiteNumber(value: JsonValue, field: String, path: String): JsonValue.Number {
        val number = value as? JsonValue.Number
            ?: settingsError("INVALID_VALUE", "$path.$field must be finite")
        val asDouble = number.literal.toDoubleOrNull()
        if (asDouble == null || !asDouble.isFinite()) {
            settingsError("INVALID_VALUE", "$path.$field must be finite")
        }
        return number
    }

    private fun scalar(value: JsonValue, field: String, path: String, maximum: Int = 512): JsonValue =
        when (value) {
            is JsonValue.Text -> if (value.value.length <= maximum) {
                value
            } else {
                settingsError("INVALID_VALUE", "$path.$field has invalid type or length")
            }
            is JsonValue.Bool -> value
            else -> settingsError("INVALID_VALUE", "$path.$field has invalid type or length")
        }

    private fun validateGenericJson(value: String, key: String): String {
        if (Utf8Text.byteLength(value) > MAX_VALUE_BYTES) {
            settingsError("VALUE_TOO_LARGE", "$key exceeds 1 MiB")
        }
        val trimmed = value.trimStart()
        if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
            val parsed = parseStructured(value, key)
            assertNoSecretFields(parsed, "$.$key")
            assertNoExecutableFields(parsed, "$.$key")
        }
        return value
    }

    private fun forbiddenCredentialField(key: String): Boolean {
        val normalized = normalizeCredentialKey(key)
        return normalized in credentialKeys || credentialSuffixes.any { normalized.endsWith(it) }
    }
}
