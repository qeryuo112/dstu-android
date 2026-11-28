package com.deepstudent.services.providers.data.codex

import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode

/** Internal control-flow carrier; the message never contains tokens, URLs or account values. */
internal class CodexAuthException(val failure: CodexAuthFailure) : Exception(failure.code.name) {
    override fun toString(): String = "CodexAuthException($failure)"
}

internal fun codexFailure(
    code: CodexAuthFailureCode,
    stage: CodexAuthStage,
    retryable: Boolean = false,
    reauthenticationRequired: Boolean = false,
): CodexAuthFailure = CodexAuthFailure(
    code = code,
    stage = stage,
    retryable = retryable,
    reauthenticationRequired = reauthenticationRequired,
)

internal fun CodexWireFailure.toFailure(): CodexAuthFailure = codexFailure(
    code = code,
    stage = stage,
    reauthenticationRequired = code == CodexAuthFailureCode.ACCOUNT_CHANGED,
)

/** Fixed redacted mapping to the shared provider failure vocabulary; no upstream detail survives. */
internal fun CodexAuthFailure.asProviderFailure(): ProviderFailure = ProviderFailure(
    when (code) {
        CodexAuthFailureCode.OAUTH_REJECTED ->
            if (reauthenticationRequired) ProviderFailureCode.REAUTHENTICATION_REQUIRED else ProviderFailureCode.AUTHENTICATION

        else -> checkNotNull(PROVIDER_FAILURE_CODES[code]) { "unmapped Codex failure code $code" }
    },
)

/** Fixed mapping for every failure whose provider code does not depend on the detail flags. */
private val PROVIDER_FAILURE_CODES: Map<CodexAuthFailureCode, ProviderFailureCode> = mapOf(
    CodexAuthFailureCode.SIGNED_OUT to ProviderFailureCode.CREDENTIAL_UNAVAILABLE,
    CodexAuthFailureCode.REAUTHENTICATION_REQUIRED to ProviderFailureCode.REAUTHENTICATION_REQUIRED,
    CodexAuthFailureCode.ACCOUNT_CHANGED to ProviderFailureCode.REAUTHENTICATION_REQUIRED,
    CodexAuthFailureCode.NETWORK to ProviderFailureCode.NETWORK,
    CodexAuthFailureCode.CREDENTIAL_STORE to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.LOGIN_BUSY to ProviderFailureCode.INVALID_CONFIGURATION,
    CodexAuthFailureCode.CALLBACK_UNAVAILABLE to ProviderFailureCode.INVALID_CONFIGURATION,
    CodexAuthFailureCode.MALFORMED_RESPONSE to ProviderFailureCode.PROVIDER_ERROR,
    CodexAuthFailureCode.RESPONSE_TOO_LARGE to ProviderFailureCode.PROVIDER_ERROR,
    CodexAuthFailureCode.MISSING_ACCOUNT_ID to ProviderFailureCode.PROVIDER_ERROR,
    CodexAuthFailureCode.ATTEMPT_NOT_FOUND to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.CALLBACK_INVALID to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.CALLBACK_EXPIRED to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.OAUTH_DENIED to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.CANCELLED to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.TIMEOUT to ProviderFailureCode.INTERNAL,
    CodexAuthFailureCode.INTERNAL to ProviderFailureCode.INTERNAL,
)
