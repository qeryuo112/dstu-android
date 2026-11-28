package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.CodexRequestAuth
import com.deepstudent.services.providers.api.CodexRequestAuthPort
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Android-native OpenAI Codex account lifecycle: PKCE browser login, device code
 * login, encrypted session persistence, generation-aware refresh and logout.
 *
 * Frozen desktop source: `llm-providers/src/openai-codex/{manager,manager-values,protocol,store}.ts`.
 * Browser callback ownership (the loopback socket) stays with the app; this service only
 * validates the callback against the live attempt state it issued. Device polling and the
 * locked refresh stages live in their collaborators.
 */
class CodexOAuthService internal constructor(private val core: CodexAuthCore, private val endpoints: CodexOAuthEndpoints) :
    CodexAuthSettingsPort,
    CodexRequestAuthPort,
    AutoCloseable {
    private val devicePolling = CodexDevicePolling(core, endpoints)

    constructor(
        network: NetworkTransport,
        vault: CodexSessionVault,
        logger: Logger = NoopLogger,
    ) : this(
        core = CodexAuthCore(
            network = network,
            vault = vault,
            logger = logger,
            endpoints = CodexOAuthEndpoints.PRODUCTION,
            clock = System::currentTimeMillis,
            random = SecureRandom(),
            version = VERSION,
        ),
        endpoints = CodexOAuthEndpoints.PRODUCTION,
    )

    internal constructor(
        network: NetworkTransport,
        vault: CodexSessionVault,
        logger: Logger,
        endpoints: CodexOAuthEndpoints,
        clock: () -> Long,
        random: SecureRandom,
        version: String,
    ) : this(
        core = CodexAuthCore(network, vault, logger, endpoints, clock, random, version),
        endpoints = endpoints,
    )

    override suspend fun status(): CodexAuthStatus = core.status()

    override suspend fun startBrowserLogin(
        redirectUri: String,
        cancellation: ProviderCancellation,
    ): ProviderResult<CodexBrowserLoginStart> {
        core.ensureLoaded()
        if (!validRedirect(redirectUri)) {
            core.trace.record("authorize", "INVALID_REDIRECT")
            return providerFailure(CodexAuthFailureCode.INTERNAL, CodexAuthStage.AUTHORIZATION)
        }
        val now = core.clock()
        val pkce = try {
            CodexAuthProtocol.generatePkce(core.random)
        } catch (_: Exception) {
            return providerFailure(CodexAuthFailureCode.INTERNAL, CodexAuthStage.AUTHORIZATION)
        }
        val state = CodexAuthProtocol.generateState(core.random)
        val attempt = CodexAuthAttempt(
            id = UUID.randomUUID().toString(),
            kind = CodexLoginKind.BROWSER,
            expiresAtMillis = saturatingAdd(now, BROWSER_ATTEMPT_MILLIS),
            browser = CodexBrowserAttempt(redirectUri = redirectUri, state = state, verifier = pkce.verifier),
            prompt = CodexAttemptPrompt(authorizationUrl = authorizationUrl(redirectUri, pkce.challenge, state)),
        )
        return try {
            cancellation.check()
            core.state.installAttempt(attempt)
            core.trace.record("authorize", "STARTED")
            ProviderResult.Success(
                CodexBrowserLoginStart(attempt.id, attempt.prompt.authorizationUrl.orEmpty(), redirectUri, attempt.expiresAtMillis),
            )
        } catch (failure: CodexAuthException) {
            providerFailure(failure.failure)
        } catch (_: CancellationException) {
            core.state.removeAttempt()?.cancel?.complete(Unit)
            ProviderResult.Cancelled
        }
    }

    override suspend fun finishBrowserLogin(attemptId: String, callbackUri: String): CodexBrowserLoginResult {
        core.ensureLoaded()
        val attempt = core.state.read().attempt ?: return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.CALLBACK)
        val browser = attempt.browser ?: return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.CALLBACK)
        if (attempt.id != attemptId) return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.CALLBACK)
        if (core.clock() > attempt.expiresAtMillis) return timedOut(attemptId)
        return when (val callback = CodexAuthProtocol.callback(callbackUri, browser.redirectUri, browser.state)) {
            CodexCallback.Invalid -> {
                core.trace.record("callback", "INVALID", generation = core.state.read().generation)
                failed(CodexAuthFailureCode.CALLBACK_INVALID, CodexAuthStage.CALLBACK)
            }

            is CodexCallback.Denied -> denyAttempt(attemptId, callback.code)

            is CodexCallback.Code -> exchange(attemptId, browser, callback.code)
        }
    }

    override suspend fun denyBrowserLogin(attemptId: String, oauthErrorCode: String?): CodexBrowserLoginResult {
        core.ensureLoaded()
        return denyAttempt(attemptId, oauthErrorCode)
    }

    override suspend fun cancelBrowserLogin(attemptId: String): CodexBrowserLoginResult = cancelAttempt(attemptId)

    override suspend fun startDeviceLogin(): ProviderResult<CodexDeviceLoginStart> {
        core.ensureLoaded()
        val attempt = CodexAuthAttempt(
            id = UUID.randomUUID().toString(),
            kind = CodexLoginKind.DEVICE_CODE,
            expiresAtMillis = saturatingAdd(core.clock(), DEVICE_ATTEMPT_MILLIS),
            prompt = CodexAttemptPrompt(verificationUrl = endpoints.deviceVerificationUrl),
        )
        return try {
            core.state.installAttempt(attempt)
            core.trace.record("device_authorize", "STARTED")
            deviceStart(attempt)
        } catch (failure: CodexAuthException) {
            core.state.finishAttemptError(attempt.id, failure.failure)
            providerFailure(failure.failure)
        } catch (_: CancellationException) {
            core.state.removeAttempt()?.cancel?.complete(Unit)
            ProviderResult.Cancelled
        } catch (_: Exception) {
            core.state.finishAttemptError(attempt.id, coreFailure(CodexAuthFailureCode.INTERNAL, CodexAuthStage.DEVICE_AUTHORIZATION))
            providerFailure(CodexAuthFailureCode.INTERNAL, CodexAuthStage.DEVICE_AUTHORIZATION)
        }
    }

    /** Accepts the user-code response, re-arms the attempt and launches the background poll. */
    private suspend fun deviceStart(attempt: CodexAuthAttempt): ProviderResult<CodexDeviceLoginStart> {
        val code = devicePolling.requestDeviceCode(attempt)
        val current = core.state.read().attempt
        if (current?.id != attempt.id || current.completing) throw CodexAuthException(cancelledFailure())
        val updated = current.copy(
            expiresAtMillis = saturatingAdd(core.clock(), TimeUnit.SECONDS.toMillis(code.expiresInSeconds)),
            device = CodexDeviceAttempt(code.deviceAuthId, code.intervalSeconds),
            prompt = current.prompt.copy(userCode = code.userCode, pollIntervalSeconds = code.intervalSeconds),
        )
        core.state.update { state -> if (state.attempt?.id == attempt.id) state.copy(attempt = updated) else state }
        core.scope.launch { devicePolling.poll(updated) }
        return ProviderResult.Success(
            CodexDeviceLoginStart(
                attemptId = updated.id,
                verificationUrl = endpoints.deviceVerificationUrl,
                userCode = code.userCode,
                expiresAtMillis = updated.expiresAtMillis,
                pollIntervalSeconds = code.intervalSeconds,
            ),
        )
    }

    override suspend fun cancelLogin(attemptId: String): CodexBrowserLoginResult = cancelAttempt(attemptId)

    override suspend fun logout(): ProviderResult<Unit> = core.logout()

    override suspend fun requestAuth(cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth> =
        core.requestAuth(forceRefresh = false, cancellation = cancellation)

    override suspend fun refreshAfterUnauthorized(generation: Long, cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth> =
        core.refreshAfterUnauthorized(generation, cancellation)

    override suspend fun markReauthenticationRequired(generation: Long) = core.markReauthenticationRequired(generation)

    override fun close() = core.close()

    private suspend fun exchange(attemptId: String, browser: CodexBrowserAttempt, code: String): CodexBrowserLoginResult {
        if (!core.state.claimAttempt(attemptId)) return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.TOKEN_EXCHANGE)
        core.trace.record("token_exchange", "STARTED")
        return try {
            val tokens = core.tokenRequest(
                CodexAuthStage.TOKEN_EXCHANGE,
                linkedMapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to browser.redirectUri,
                    "client_id" to endpoints.clientId,
                    "code_verifier" to browser.verifier,
                ),
                ProviderCancellation { false },
            )
            val session = CodexTokenPolicy.sessionFromInitialTokens(tokens, core.clock())
            core.persistAndInstall(attemptId, session)
            CodexBrowserLoginResult.Completed(core.status())
        } catch (failure: CodexAuthException) {
            core.state.finishAttemptError(attemptId, failure.failure)
            CodexBrowserLoginResult.Failed(failure.failure)
        } catch (_: CancellationException) {
            core.state.removeAttempt()?.let { it.cancel.complete(Unit) }
            CodexBrowserLoginResult.Cancelled(attemptId)
        } catch (_: Exception) {
            val failure = coreFailure(CodexAuthFailureCode.INTERNAL, CodexAuthStage.TOKEN_EXCHANGE)
            core.state.finishAttemptError(attemptId, failure)
            CodexBrowserLoginResult.Failed(failure)
        }
    }

    private suspend fun denyAttempt(attemptId: String, oauthErrorCode: String?): CodexBrowserLoginResult {
        val removed = core.state.claimAttempt(attemptId)
        if (!removed) return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.CALLBACK)
        core.state.removeAttempt()?.cancel?.complete(Unit)
        val failure = coreFailure(
            code = CodexAuthFailureCode.OAUTH_DENIED,
            stage = CodexAuthStage.CALLBACK,
            retryable = oauthErrorCode == "temporarily_unavailable",
        )
        core.state.finishAttemptError(attemptId, failure)
        core.trace.record("callback", "DENIED")
        return CodexBrowserLoginResult.Denied(failure)
    }

    private suspend fun cancelAttempt(attemptId: String): CodexBrowserLoginResult {
        core.ensureLoaded()
        val removed = core.state.claimAttempt(attemptId)
        if (!removed) return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.CALLBACK)
        val attempt = core.state.removeAttempt() ?: return failed(CodexAuthFailureCode.ATTEMPT_NOT_FOUND, CodexAuthStage.CALLBACK)
        attempt.cancel.complete(Unit)
        core.trace.record("login_cancel", "CANCELLED")
        return CodexBrowserLoginResult.Cancelled(attemptId)
    }

    private fun timedOut(attemptId: String): CodexBrowserLoginResult {
        val removed = core.state.removeAttempt()
        removed?.cancel?.complete(Unit)
        core.trace.record("callback", "TIMEOUT")
        return CodexBrowserLoginResult.TimedOut(attemptId)
    }

    private fun authorizationUrl(redirectUri: String, challenge: String, state: String): String =
        CodexAuthProtocol.buildAuthorizationUrl(endpoints, redirectUri, challenge, state)

    private fun validRedirect(value: String): Boolean {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme == "http" && uri.host == "localhost" && uri.userInfo == null && uri.fragment == null &&
            uri.path == CALLBACK_PATH && uri.port in 1..65535
    }

    private fun failed(code: CodexAuthFailureCode, stage: CodexAuthStage): CodexBrowserLoginResult.Failed =
        CodexBrowserLoginResult.Failed(coreFailure(code, stage))

    private fun providerFailure(failure: CodexAuthFailure): ProviderResult<Nothing> = ProviderResult.Failure(failure.asProviderFailure())

    private fun providerFailure(code: CodexAuthFailureCode, stage: CodexAuthStage): ProviderResult<Nothing> =
        providerFailure(coreFailure(code, stage))

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val CALLBACK_PATH = "/auth/callback"
        const val BROWSER_ATTEMPT_MILLIS = 300_000L
        const val DEVICE_ATTEMPT_MILLIS = 900_000L
        const val VERSION = "0.11.0"
    }
}
