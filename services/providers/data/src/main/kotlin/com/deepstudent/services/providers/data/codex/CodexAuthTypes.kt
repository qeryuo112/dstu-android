package com.deepstudent.services.providers.data.codex

import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderResult

enum class CodexAuthPhase {
    SIGNED_OUT,
    AUTHORIZING,
    AUTHENTICATED,
    REFRESHING,
    REAUTHENTICATION_REQUIRED,
    ERROR,
}

enum class CodexLoginKind { BROWSER, DEVICE_CODE }

enum class CodexAuthStage {
    LOAD,
    AUTHORIZATION,
    DEVICE_AUTHORIZATION,
    DEVICE_POLL,
    TOKEN_EXCHANGE,
    REFRESH,
    REVOKE,
    LOGOUT,
    CALLBACK,
    INTERNAL,
}

enum class CodexAuthFailureCode {
    LOGIN_BUSY,
    ATTEMPT_NOT_FOUND,
    CALLBACK_INVALID,
    CALLBACK_EXPIRED,
    CALLBACK_UNAVAILABLE,
    OAUTH_DENIED,
    OAUTH_REJECTED,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    MISSING_ACCOUNT_ID,
    ACCOUNT_CHANGED,
    SIGNED_OUT,
    REAUTHENTICATION_REQUIRED,
    CREDENTIAL_STORE,
    NETWORK,
    CANCELLED,
    TIMEOUT,
    INTERNAL,
}

data class CodexAuthFailure(
    val code: CodexAuthFailureCode,
    val stage: CodexAuthStage,
    val retryable: Boolean,
    val reauthenticationRequired: Boolean,
) {
    override fun toString(): String = "CodexAuthFailure(code=$code, stage=$stage, retryable=$retryable, " +
        "reauthenticationRequired=$reauthenticationRequired)"
}

data class CodexAccountSummary(val hint: String?, val email: String?, val planType: String?, val fedramp: Boolean) {
    override fun toString(): String = "CodexAccountSummary(redacted)"
}

data class CodexLoginPrompt(
    val kind: CodexLoginKind,
    val attemptId: String,
    val authorizationUrl: String?,
    val verificationUrl: String?,
    val userCode: String?,
    val pollIntervalSeconds: Int?,
) {
    override fun toString(): String = "CodexLoginPrompt(kind=$kind, prompt=redacted)"
}

data class CodexAuthStatus(
    val phase: CodexAuthPhase,
    val hasUsableSession: Boolean,
    val account: CodexAccountSummary?,
    val expiresAtMillis: Long?,
    val generation: Long,
    val login: CodexLoginPrompt?,
    val lastFailure: CodexAuthFailure?,
) {
    val accountHint: String? get() = account?.hint
    val email: String? get() = account?.email
    val planType: String? get() = account?.planType
    val fedramp: Boolean get() = account?.fedramp == true
    val activeLoginKind: CodexLoginKind? get() = login?.kind
    val activeAttemptId: String? get() = login?.attemptId
    val authorizationUrl: String? get() = login?.authorizationUrl
    val verificationUrl: String? get() = login?.verificationUrl
    val userCode: String? get() = login?.userCode
    val pollIntervalSeconds: Int? get() = login?.pollIntervalSeconds

    override fun toString(): String = "CodexAuthStatus(phase=$phase, hasUsableSession=$hasUsableSession, " +
        "account=redacted, generation=$generation, activeLoginKind=$activeLoginKind, " +
        "activeAttemptId=$activeAttemptId, lastFailure=$lastFailure)"
}

data class CodexBrowserLoginStart(
    val attemptId: String,
    val authorizationUrl: String,
    val redirectUri: String,
    val expiresAtMillis: Long,
) {
    init {
        require(attemptId.isNotBlank()) { "attempt id is required" }
        require(authorizationUrl.isNotBlank()) { "authorization URL is required" }
        require(redirectUri.isNotBlank()) { "redirect URI is required" }
        require(expiresAtMillis > 0) { "invalid attempt expiry" }
    }

    override fun toString(): String = "CodexBrowserLoginStart(attemptId=$attemptId, authorizationUrl=redacted, " +
        "redirectUri=redacted, expiresAtMillis=$expiresAtMillis)"
}

/**
 * Flat device-code start, mirroring the frozen desktop `DeviceLoginStart`.
 * Device login has no browser authorization URL, so none is invented here.
 */
data class CodexDeviceLoginStart(
    val attemptId: String,
    val verificationUrl: String,
    val userCode: String,
    val expiresAtMillis: Long,
    val pollIntervalSeconds: Int,
) {
    init {
        require(attemptId.isNotBlank()) { "attempt id is required" }
        require(verificationUrl.isNotBlank()) { "verification URL is required" }
        require(userCode.isNotBlank()) { "user code is required" }
        require(expiresAtMillis > 0) { "invalid attempt expiry" }
    }

    override fun toString(): String = "CodexDeviceLoginStart(attemptId=$attemptId, userCode=redacted, " +
        "verificationUrl=redacted, expiresAtMillis=$expiresAtMillis, pollIntervalSeconds=$pollIntervalSeconds)"
}

sealed interface CodexBrowserLoginResult {
    data class Completed(val status: CodexAuthStatus) : CodexBrowserLoginResult
    data class Denied(val failure: CodexAuthFailure) : CodexBrowserLoginResult
    data class Cancelled(val attemptId: String) : CodexBrowserLoginResult
    data class Failed(val failure: CodexAuthFailure) : CodexBrowserLoginResult
    data class TimedOut(val attemptId: String) : CodexBrowserLoginResult
}

/** Browser callback shape returned by a platform-owned receiver. */
sealed interface CodexBrowserCallback {
    data class Completed(val callbackUri: String) : CodexBrowserCallback
    data class Denied(val oauthErrorCode: String? = null) : CodexBrowserCallback
    data class Cancelled(val attemptId: String) : CodexBrowserCallback
    data class TimedOut(val attemptId: String) : CodexBrowserCallback
}

/** Narrow settings/commands port; activation of a local browser receiver stays app-owned. */
interface CodexAuthSettingsPort {
    suspend fun status(): CodexAuthStatus

    /** [redirectUri] is produced by the parent's fixed local callback receiver. */
    suspend fun startBrowserLogin(
        redirectUri: String,
        cancellation: ProviderCancellation = ProviderCancellation { false },
    ): ProviderResult<CodexBrowserLoginStart>

    /** Atomically consumes the matching, live browser attempt and exchanges the code. */
    suspend fun finishBrowserLogin(attemptId: String, callbackUri: String): CodexBrowserLoginResult

    suspend fun denyBrowserLogin(attemptId: String, oauthErrorCode: String? = null): CodexBrowserLoginResult

    suspend fun cancelBrowserLogin(attemptId: String): CodexBrowserLoginResult

    suspend fun startDeviceLogin(): ProviderResult<CodexDeviceLoginStart>

    suspend fun cancelLogin(attemptId: String): CodexBrowserLoginResult

    suspend fun logout(): ProviderResult<Unit>
}
