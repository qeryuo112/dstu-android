package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.json.JsonValue

internal data class CodexSessionAccount(
    val id: String,
    val email: String? = null,
    val planType: String? = null,
    val fedramp: Boolean = false,
) {
    override fun toString(): String = "CodexSessionAccount(redacted)"
}

internal data class CodexSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val expiresAtMillis: Long,
    val account: CodexSessionAccount,
    val lastRefreshAtMillis: Long?,
) {
    val accountId: String get() = account.id
    val email: String? get() = account.email
    val planType: String? get() = account.planType
    val fedramp: Boolean get() = account.fedramp

    init {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank()) { "invalid session tokens" }
        require(expiresAtMillis > 0 && accountId.isNotBlank()) { "invalid session identity" }
        require(lastRefreshAtMillis == null || lastRefreshAtMillis >= 0) { "invalid refresh timestamp" }
    }

    override fun toString(): String = "CodexSession(account=redacted, tokens=redacted, expiresAtMillis=$expiresAtMillis, " +
        "fedramp=$fedramp)"
}

/**
 * Versioned wire-compatible session codec. Optional fields are explicit nulls
 * on write, as in the frozen desktop store, and unknown fields are ignored on
 * read. No malformed token or identity value is repaired.
 */
internal object CodexSessionCodec {
    private const val SCHEMA_VERSION = 1L
    private const val MAX_SESSION_BYTES = 512 * 1024
    private val REQUIRED_FIELDS = setOf("schemaVersion", "accessToken", "refreshToken", "expiresAtUnixMs", "accountId")

    fun decode(bytes: ByteArray): CodexSession {
        require(bytes.isNotEmpty() && bytes.size <= MAX_SESSION_BYTES) { "invalid session size" }
        val text = bytes.decodeToString()
        val root = try {
            JsonValue.parseObject(text, "Codex session")
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid session JSON")
        }
        REQUIRED_FIELDS.forEach { require(root.containsKey(it)) { "invalid session fields" } }
        val schema = root.long("schemaVersion")
        require(schema == SCHEMA_VERSION) { "unsupported session schema" }
        val access = root.requiredText("accessToken")
        val refresh = root.requiredText("refreshToken")
        val expires = root.long("expiresAtUnixMs")
        val account = root.requiredText("accountId")
        val idToken = root.optionalText("idToken")
        val email = root.optionalText("email")
        val plan = root.optionalText("planType")
        val fedramp = (root["isFedramp"] as? JsonValue.Bool)?.value ?: false
        val lastRefresh = when (val value = root["lastRefreshAt"]) {
            null, JsonValue.Null -> null
            is JsonValue.Number -> value.asLongOrNull() ?: throw IllegalArgumentException("invalid refresh time")
            else -> throw IllegalArgumentException("invalid refresh time")
        }
        return CodexSession(
            accessToken = access,
            refreshToken = refresh,
            idToken = idToken,
            expiresAtMillis = expires,
            account = CodexSessionAccount(account, email, plan, fedramp),
            lastRefreshAtMillis = lastRefresh,
        )
    }

    fun encode(session: CodexSession): ByteArray {
        val root = JsonValue.Object.Builder()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("idToken", session.idToken?.let(JsonValue::text) ?: JsonValue.Null)
            .put("expiresAtUnixMs", session.expiresAtMillis)
            .put("accountId", session.accountId)
            .put("email", session.email?.let(JsonValue::text) ?: JsonValue.Null)
            .put("planType", session.planType?.let(JsonValue::text) ?: JsonValue.Null)
            .put("isFedramp", session.fedramp)
            .put("lastRefreshAt", session.lastRefreshAtMillis?.let(JsonValue::integer) ?: JsonValue.Null)
            .build()
        val bytes = root.literal().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SESSION_BYTES) { "session is too large" }
        return bytes
    }

    private fun JsonValue.Object.long(key: String): Long {
        val value = this[key] as? JsonValue.Number ?: throw IllegalArgumentException("invalid $key")
        return value.asLongOrNull() ?: throw IllegalArgumentException("invalid $key")
    }

    private fun JsonValue.Object.requiredText(key: String): String {
        val value = this[key] as? JsonValue.Text ?: throw IllegalArgumentException("invalid $key")
        require(value.value.isNotBlank()) { "invalid $key" }
        return value.value.trim()
    }

    private fun JsonValue.Object.optionalText(key: String): String? = when (val value = this[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Text -> value.value.takeIf(String::isNotBlank)
        else -> throw IllegalArgumentException("invalid $key")
    }
}
