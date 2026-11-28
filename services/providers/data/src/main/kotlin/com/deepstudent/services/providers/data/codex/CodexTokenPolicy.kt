package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.json.JsonValue

/**
 * Frozen desktop `openai-codex/manager-values.ts` token/session rules.
 *
 * Only typed token responses are accepted. A sequence form is the serde tuple
 * representation the desktop store also understands; optional tails stay null
 * and no malformed token or identity value is repaired.
 */
internal object CodexTokenPolicy {
    private const val DEFAULT_EXPIRES_SECONDS = 3600L
    private const val MIN_EXPIRES_SECONDS = 60L
    private const val MAX_EXPIRES_SECONDS = 86_400L

    fun decodeToken(value: JsonValue?): CodexTokenResponse = when (value) {
        is JsonValue.Object -> decodeObject(value)
        is JsonValue.Array -> decodeSequence(value)
        else -> throw malformed()
    }

    fun extractAccountId(tokens: CodexTokenResponse): String? =
        tokens.idToken?.let(::claimsAccountId) ?: claimsAccountId(tokens.accessToken)

    fun expiryFromResponse(nowMillis: Long, expiresInSeconds: Long?): Long {
        val seconds = (expiresInSeconds ?: DEFAULT_EXPIRES_SECONDS).coerceIn(MIN_EXPIRES_SECONDS, MAX_EXPIRES_SECONDS)
        if (seconds > (Long.MAX_VALUE - nowMillis) / 1000L) return Long.MAX_VALUE
        return nowMillis + seconds * 1000L
    }

    fun sessionFromInitialTokens(tokens: CodexTokenResponse, nowMillis: Long): CodexSession {
        val refresh = tokens.refreshToken?.trim().orEmpty()
        if (refresh.isEmpty()) throw malformed()
        val account = extractAccountId(tokens) ?: throw CodexWireFailure(
            CodexAuthFailureCode.MISSING_ACCOUNT_ID,
            CodexAuthStage.TOKEN_EXCHANGE,
        )
        val metadata = metadata(tokens)
        return CodexSession(
            accessToken = tokens.accessToken.trim(),
            refreshToken = refresh,
            idToken = tokens.idToken?.trim()?.takeIf(String::isNotEmpty),
            expiresAtMillis = expiryFromResponse(nowMillis, tokens.expiresInSeconds),
            account = CodexSessionAccount(account, metadata.email, metadata.planType, metadata.fedramp ?: false),
            lastRefreshAtMillis = nowMillis,
        )
    }

    fun mergeRefreshSession(current: CodexSession, tokens: CodexTokenResponse, refreshedAtMillis: Long): CodexSession {
        val account = refreshAccount(current, tokens)
        val metadata = metadata(tokens)
        return CodexSession(
            accessToken = tokens.accessToken.trim(),
            refreshToken = rotatedOr(tokens.refreshToken, current.refreshToken),
            idToken = rotatedIdOr(tokens.idToken, current.idToken),
            expiresAtMillis = expiryFromResponse(refreshedAtMillis, tokens.expiresInSeconds),
            account = CodexSessionAccount(
                account,
                metadata.email ?: current.email,
                metadata.planType ?: current.planType,
                metadata.fedramp ?: current.fedramp,
            ),
            lastRefreshAtMillis = refreshedAtMillis,
        )
    }

    private fun refreshAccount(current: CodexSession, tokens: CodexTokenResponse): String {
        val currentId = current.accountId.trim()
        if (currentId.isEmpty()) throw CodexWireFailure(CodexAuthFailureCode.MISSING_ACCOUNT_ID, CodexAuthStage.REFRESH)
        val incoming = extractAccountId(tokens)
        if (incoming != null && incoming != currentId) {
            throw CodexWireFailure(CodexAuthFailureCode.ACCOUNT_CHANGED, CodexAuthStage.REFRESH)
        }
        return currentId
    }

    fun validateRefreshIdentity(live: CodexSession, requested: CodexSession, refreshed: CodexSession) {
        val ids = listOf(live, requested, refreshed).map { it.accountId.trim() }
        if (ids.any(String::isEmpty)) throw CodexWireFailure(CodexAuthFailureCode.MISSING_ACCOUNT_ID, CodexAuthStage.REFRESH)
        if (ids[0] != ids[1] || ids[2] != ids[1]) {
            throw CodexWireFailure(CodexAuthFailureCode.ACCOUNT_CHANGED, CodexAuthStage.REFRESH)
        }
    }

    /** A refresh response may omit the rotating fields; an omitted or blank value keeps the stored one. */
    private fun rotatedOr(incoming: String?, stored: String): String = incoming?.trim()?.takeIf(String::isNotEmpty) ?: stored

    private fun rotatedIdOr(incoming: String?, storedId: String?): String? = incoming?.trim()?.takeIf(String::isNotEmpty) ?: storedId

    fun oauthRejection(stage: CodexAuthStage, status: Int, body: JsonValue?): CodexAuthFailure {
        val code = oauthErrorCode(body, status)
        val reauthentication = stage == CodexAuthStage.REFRESH && code in REAUTHENTICATION_CODES
        val rateLimited = code in RATE_LIMIT_CODES
        val permanent = reauthentication ||
            (status in 400..499 && status != 408 && status != 429 && !rateLimited && code != "temporarily_unavailable")
        return CodexAuthFailure(
            code = CodexAuthFailureCode.OAUTH_REJECTED,
            stage = stage,
            retryable = !permanent && !reauthentication,
            reauthenticationRequired = reauthentication,
        )
    }

    fun oauthErrorCode(body: JsonValue?, status: Int): String {
        val sanitized = errorCodeCandidate(body)?.replace(UNSAFE_CODE, "")?.take(64).orEmpty()
        if (sanitized.isNotEmpty()) return sanitized
        return if (status == 0) "http_error" else "http_$status"
    }

    /** Desktop alias chain: error.code, error, code, detail.code, error.type. */
    private fun errorCodeCandidate(body: JsonValue?): String? {
        val root = body as? JsonValue.Object
        val error = root?.get("error")
        return (error as? JsonValue.Object)?.let { text(it["code"]) }
            ?: text(error)
            ?: root?.let { text(it["code"]) }
            ?: (root?.get("detail") as? JsonValue.Object)?.let { text(it["code"]) }
            ?: (error as? JsonValue.Object)?.let { text(it["type"]) }
    }

    fun clampLong(value: Long, minimum: Long, maximum: Long): Long = value.coerceIn(minimum, maximum)

    private fun decodeObject(root: JsonValue.Object): CodexTokenResponse {
        val access = requiredText(root["access_token"]) ?: throw malformed()
        val refresh = optionalText(root["refresh_token"])
        val idToken = optionalText(root["id_token"])
        val expires = optionalInteger(root["expires_in"])
        return CodexTokenResponse(access, refresh, idToken, expires)
    }

    private fun decodeSequence(root: JsonValue.Array): CodexTokenResponse {
        val values = root.values
        if (values.isEmpty() || values.size > 4) throw malformed()
        val access = requiredText(values[0]) ?: throw malformed()
        val refresh = values.getOrNull(1)?.let(::optionalText)
        val idToken = values.getOrNull(2)?.let(::optionalText)
        val expires = values.getOrNull(3)?.let(::optionalInteger)
        return CodexTokenResponse(access, refresh, idToken, expires)
    }

    private fun metadata(tokens: CodexTokenResponse): CodexTokenMetadata {
        val primary = tokens.idToken?.let(::claims)?.let(::metadataFromClaims)
        val secondary = claims(tokens.accessToken)?.let(::metadataFromClaims)
        return CodexTokenMetadata(
            email = primary?.email ?: secondary?.email,
            planType = primary?.planType ?: secondary?.planType,
            fedramp = primary?.fedramp ?: secondary?.fedramp,
        )
    }

    private fun metadataFromClaims(claims: JsonValue.Object): CodexTokenMetadata {
        val auth = claims["https://api.openai.com/auth"] as? JsonValue.Object
        val profile = claims["https://api.openai.com/profile"] as? JsonValue.Object
        return CodexTokenMetadata(
            email = claimedEmail(claims, auth, profile),
            planType = claimedPlan(claims, auth),
            fedramp = claimedFedramp(claims, auth),
        )
    }

    /** Desktop precedence: root claim, then the namespaced auth object, then the profile object. */
    private fun claimedEmail(claims: JsonValue.Object, auth: JsonValue.Object?, profile: JsonValue.Object?): String? =
        (text(claims["email"]) ?: textAt(auth, "email") ?: textAt(profile, "email"))
            ?.trim()?.takeIf(String::isNotEmpty)

    private fun claimedPlan(claims: JsonValue.Object, auth: JsonValue.Object?): String? =
        (text(claims["chatgpt_plan_type"]) ?: text(claims["plan_type"]) ?: textAt(auth, "chatgpt_plan_type") ?: textAt(auth, "plan_type"))
            ?.trim()?.takeIf(String::isNotEmpty)

    private fun claimedFedramp(claims: JsonValue.Object, auth: JsonValue.Object?): Boolean? =
        bool(claims["is_fedramp"]) ?: auth?.let { bool(it["chatgpt_account_is_fedramp"]) ?: bool(it["is_fedramp"]) }

    private fun textAt(claims: JsonValue.Object?, key: String): String? = claims?.let { text(it[key]) }

    private fun claims(token: String): JsonValue.Object? = CodexAuthProtocol.parseJwtClaims(token)

    private fun claimsAccountId(token: String): String? {
        val claims = claims(token) ?: return null
        val auth = claims["https://api.openai.com/auth"] as? JsonValue.Object
        val organizations = claims["organizations"] as? JsonValue.Array
        return sequenceOf(
            text(claims["chatgpt_account_id"]),
            auth?.let { text(it["chatgpt_account_id"]) },
            (organizations?.values?.firstOrNull() as? JsonValue.Object)?.let { text(it["id"]) },
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
    }

    private fun requiredText(value: JsonValue?): String? = when (value) {
        is JsonValue.Text -> value.value.trim().takeIf(String::isNotEmpty)
        else -> null
    }

    /** Absent and explicit-null optional fields both stay null; any other shape is malformed. */
    private fun optionalText(value: JsonValue?): String? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Text -> value.value
        else -> throw malformed()
    }

    private fun optionalInteger(value: JsonValue?): Long? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Number -> value.asLongOrNull() ?: throw malformed()
        else -> throw malformed()
    }

    private fun text(value: JsonValue?): String? = (value as? JsonValue.Text)?.value

    private fun bool(value: JsonValue?): Boolean? = (value as? JsonValue.Bool)?.value

    private fun malformed(): CodexWireFailure = CodexWireFailure(CodexAuthFailureCode.MALFORMED_RESPONSE, CodexAuthStage.TOKEN_EXCHANGE)

    private val REAUTHENTICATION_CODES = setOf("invalid_grant", "invalid_token", "unauthorized")
    private val RATE_LIMIT_CODES = setOf("usage_limit_reached", "usage_not_included", "rate_limit_exceeded", "slow_down")
    private val UNSAFE_CODE = Regex("[^A-Za-z0-9_.-]")
}

internal data class CodexTokenMetadata(val email: String?, val planType: String?, val fedramp: Boolean?)
