package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.CodexRequestAuth
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

/**
 * Frozen desktop `openai-codex/manager.ts` state machine.
 *
 * The commit gate serializes login persistence, refresh persistence and logout
 * so a completed save is never overwritten by a concurrent cancel or logout.
 * Read-only status never touches the gate or the network.
 */
internal class CodexAuthCore(
    val network: NetworkTransport,
    val vault: CodexSessionVault,
    val logger: Logger,
    val endpoints: CodexOAuthEndpoints,
    val clock: () -> Long,
    val random: SecureRandom,
    val version: String,
) {
    val state = CodexAuthState()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val trace = CodexAuthTrace(logger)
    private val refresh = CodexRefreshPipeline(this)

    private val gate = Mutex()
    private val loadGate = Mutex()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        loadGate.withLock {
            if (loaded) return
            loaded = true
            loadSession()
        }
    }

    private suspend fun loadSession() {
        val bytes = try {
            vault.load()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            trace.record("load", "CREDENTIAL_STORE")
            state.loadFailure(coreFailure(CodexAuthFailureCode.CREDENTIAL_STORE, CodexAuthStage.LOAD))
            return
        } ?: run {
            state.loadSession(null)
            return
        }
        val session = try {
            CodexSessionCodec.decode(bytes)
        } catch (_: IllegalArgumentException) {
            trace.record("load", "CREDENTIAL_STORE")
            state.loadFailure(coreFailure(CodexAuthFailureCode.CREDENTIAL_STORE, CodexAuthStage.LOAD))
            null
        } finally {
            bytes.fill(0)
        }
        if (session != null) {
            state.loadSession(session)
            trace.record("load", "AUTHENTICATED", generation = 1)
        }
    }

    suspend fun status(): CodexAuthStatus {
        ensureLoaded()
        return state.read().toStatus()
    }

    suspend fun requestAuth(forceRefresh: Boolean, cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth> {
        ensureLoaded()
        return guarded(cancellation, CodexAuthStage.REFRESH) {
            val initial = state.read()
            guardSnapshot(initial)?.let { return@guarded it }
            val refreshed = refreshWhenExpiring(initial, forceRefresh, cancellation)
            if (refreshed != null) return@guarded refreshed
            liveLease()
        }
    }

    /** Shared reauthentication/signed-out guard for both the fresh and the re-read snapshot. */
    private fun guardSnapshot(snapshot: CodexAuthSnapshot): ProviderResult<CodexRequestAuth>? = when {
        snapshot.requiresReauthentication() -> reauthentication()
        snapshot.session == null -> signedOut()
        else -> null
    }

    /** Force or an expiring session goes through refresh; a transient failure may keep the lease. */
    private suspend fun refreshWhenExpiring(
        initial: CodexAuthSnapshot,
        forceRefresh: Boolean,
        cancellation: ProviderCancellation,
    ): ProviderResult<CodexRequestAuth>? {
        val expires = checkNotNull(initial.session).expiresAtMillis
        if (!forceRefresh && expires > clock() + REFRESH_WINDOW_MILLIS) return null
        return try {
            ProviderResult.Success(refreshInternal(initial.generation, forceRefresh, cancellation))
        } catch (failure: CodexAuthException) {
            if (transientGrace(forceRefresh, failure.failure, expires)) {
                null
            } else {
                ProviderResult.Failure(failure.failure.asProviderFailure())
            }
        }
    }

    private fun transientGrace(forceRefresh: Boolean, failure: CodexAuthFailure, expiresAtMillis: Long): Boolean =
        !forceRefresh && failure.retryable && expiresAtMillis > clock() + MIN_REMAINING_MILLIS

    private suspend fun liveLease(): ProviderResult<CodexRequestAuth> {
        val live = state.read()
        guardSnapshot(live)?.let { return it }
        val session = checkNotNull(live.session)
        if (session.expiresAtMillis <= clock()) return reauthentication()
        return ProviderResult.Success(lease(session, live.generation))
    }

    suspend fun refreshAfterUnauthorized(observedGeneration: Long, cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth> {
        ensureLoaded()
        return guarded(cancellation, CodexAuthStage.REFRESH) {
            ProviderResult.Success(refreshInternal(observedGeneration, true, cancellation))
        }
    }

    fun markReauthenticationRequired(observedGeneration: Long) {
        state.markReauthenticationRequired(observedGeneration)
        trace.record("reauth_required", "GENERATION", generation = observedGeneration)
    }

    /** Caller must hold no other gate. Returns a lease even if the generation moved while refreshing. */
    suspend fun refreshInternal(observedGeneration: Long, force: Boolean, cancellation: ProviderCancellation): CodexRequestAuth {
        cancellation.check()
        return gate.withLock { refresh.perform(observedGeneration, force, cancellation) }
    }

    suspend fun persistAndInstall(attemptId: String, session: CodexSession) {
        gate.withLock {
            if (state.read().attempt?.id != attemptId) throw CodexAuthException(cancelledFailure())
            try {
                CodexSessionCodec.encode(session).also { vault.save(it) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                state.finishAttemptError(attemptId, coreFailure(CodexAuthFailureCode.CREDENTIAL_STORE, CodexAuthStage.TOKEN_EXCHANGE))
                throw CodexAuthException(coreFailure(CodexAuthFailureCode.CREDENTIAL_STORE, CodexAuthStage.TOKEN_EXCHANGE))
            }
            if (!state.commitLogin(attemptId, session)) throw CodexAuthException(cancelledFailure())
            trace.record("login", "COMPLETED", generation = state.read().generation)
        }
    }

    suspend fun <T> commitGate(block: suspend () -> T): T = gate.withLock { block() }

    suspend fun logout(): ProviderResult<Unit> {
        ensureLoaded()
        return gate.withLock {
            val attempt = state.read().attempt
            if (attempt != null) {
                state.removeAttempt()
                attempt.cancel.complete(Unit)
            }
            val refreshToken = state.read().session?.refreshToken
            try {
                vault.clear()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                trace.record("logout", "CREDENTIAL_STORE")
                return@withLock ProviderResult.Failure(
                    coreFailure(CodexAuthFailureCode.CREDENTIAL_STORE, CodexAuthStage.LOGOUT).asProviderFailure(),
                )
            }
            state.read().attempt?.let { late ->
                state.removeAttempt()
                late.cancel.complete(Unit)
            }
            state.signedOut()
            trace.record("logout", "COMPLETED")
            if (refreshToken != null) revoke(refreshToken)
            ProviderResult.Success(Unit)
        }
    }

    private suspend fun revoke(refreshToken: String) {
        val request = try {
            CodexAuthProtocol.revokeRequest(endpoints, refreshToken)
        } catch (_: IllegalArgumentException) {
            return
        }
        runCatching { network.execute(request, HttpLimits(MAX_BODY_BYTES)) }
    }

    suspend fun tokenRequest(stage: CodexAuthStage, fields: Map<String, String>, cancellation: ProviderCancellation): CodexTokenResponse {
        val request = CodexAuthProtocol.tokenRequest(endpoints, fields, stage, cancellation)
        val response = execute(request, cancellation, stage)
        if (response.status !in 200..299) {
            throw CodexAuthException(CodexTokenPolicy.oauthRejection(stage, response.status, response.content))
        }
        return try {
            CodexTokenPolicy.decodeToken(response.content)
        } catch (failure: CodexWireFailure) {
            throw CodexAuthException(coreFailure(CodexAuthFailureCode.MALFORMED_RESPONSE, stage))
        }
    }

    suspend fun execute(
        request: com.deepstudent.platform.network.NetworkRequest,
        cancellation: ProviderCancellation,
        stage: CodexAuthStage,
    ): CodexHttpResult.Response {
        cancellation.check()
        return when (val result = CodexAuthProtocol.executeJson(network, request, cancellation)) {
            is CodexHttpResult.Response -> result
            is CodexHttpResult.Network -> throw CodexAuthException(networkFailure(result.error, stage))
        }
    }

    fun lease(session: CodexSession, generation: Long): CodexRequestAuth {
        val token = session.accessToken.toByteArray(Charsets.UTF_8)
        try {
            return CodexRequestAuth(ProviderCredential.takeOwnership(token), session.accountId, generation, session.fedramp)
        } catch (failure: IllegalArgumentException) {
            token.fill(0)
            throw CodexAuthException(coreFailure(CodexAuthFailureCode.SIGNED_OUT, CodexAuthStage.LOAD))
        }
    }

    fun close() {
        state.read().attempt?.cancel?.complete(Unit)
        scope.cancel()
    }

    private suspend fun <T> guarded(
        cancellation: ProviderCancellation,
        stage: CodexAuthStage,
        block: suspend () -> ProviderResult<T>,
    ): ProviderResult<T> = try {
        block().also { cancellation.check() }
    } catch (failure: CodexAuthException) {
        currentCoroutineContext().ensureActive()
        ProviderResult.Failure(failure.failure.asProviderFailure())
    } catch (failure: CancellationException) {
        currentCoroutineContext().ensureActive()
        trace.record("cancelled", stage.name)
        ProviderResult.Cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        ProviderResult.Failure(coreFailure(CodexAuthFailureCode.INTERNAL, stage).asProviderFailure())
    }

    private fun networkFailure(error: NetworkError, stage: CodexAuthStage): CodexAuthFailure = when (error) {
        NetworkError.RESPONSE_TOO_LARGE -> coreFailure(CodexAuthFailureCode.RESPONSE_TOO_LARGE, stage)
        else -> coreFailure(CodexAuthFailureCode.NETWORK, stage, retryable = true)
    }

    private fun reauthentication(): ProviderResult<CodexRequestAuth> = ProviderResult.Failure(reauthFailure().asProviderFailure())

    private fun signedOut(): ProviderResult<CodexRequestAuth> = ProviderResult.Failure(signedOutFailure().asProviderFailure())

    private fun CodexAuthSnapshot.toStatus() = CodexAuthStatus(
        phase = phase,
        hasUsableSession = hasUsableSession(),
        account = session?.let { CodexAccountSummary(CodexAuthProtocol.accountHint(it.accountId), it.email, it.planType, it.fedramp) },
        expiresAtMillis = attempt?.expiresAtMillis ?: session?.expiresAtMillis,
        generation = generation,
        login = attempt?.let {
            CodexLoginPrompt(
                it.kind,
                it.id,
                it.prompt.authorizationUrl,
                it.prompt.verificationUrl,
                it.prompt.userCode,
                it.prompt.pollIntervalSeconds,
            )
        },
        lastFailure = lastFailure,
    )

    companion object {
        const val REFRESH_WINDOW_MILLIS = 5 * 60 * 1000L
        const val MIN_REMAINING_MILLIS = 30 * 1000L

        private const val MAX_BODY_BYTES = 512 * 1024
    }
}

internal fun reauthFailure(): CodexAuthFailure = coreFailure(CodexAuthFailureCode.REAUTHENTICATION_REQUIRED, CodexAuthStage.REFRESH)

internal fun signedOutFailure(): CodexAuthFailure = coreFailure(CodexAuthFailureCode.SIGNED_OUT, CodexAuthStage.LOAD)

internal fun cancelledFailure(): CodexAuthFailure = coreFailure(CodexAuthFailureCode.CANCELLED, CodexAuthStage.CALLBACK)

internal fun codexReauthenticationFailure(): CodexAuthFailure =
    coreFailure(CodexAuthFailureCode.REAUTHENTICATION_REQUIRED, CodexAuthStage.REFRESH)

internal fun coreFailure(
    code: CodexAuthFailureCode,
    stage: CodexAuthStage,
    retryable: Boolean = false,
    reauthenticationRequired: Boolean = false,
): CodexAuthFailure = CodexAuthFailure(code, stage, retryable, reauthenticationRequired)

internal fun ProviderResult<CodexRequestAuth>.leaseOrThrow(): CodexRequestAuth = when (this) {
    is ProviderResult.Success -> value
    is ProviderResult.Failure -> throw CodexAuthException(CodexAuthFailure(failure.code.toAuthCode(), CodexAuthStage.REFRESH, false, false))
    ProviderResult.Cancelled -> throw CodexAuthException(cancelledFailure())
}

private fun com.deepstudent.services.providers.api.ProviderFailureCode.toAuthCode(): CodexAuthFailureCode = when (this) {
    com.deepstudent.services.providers.api.ProviderFailureCode.REAUTHENTICATION_REQUIRED -> CodexAuthFailureCode.REAUTHENTICATION_REQUIRED
    com.deepstudent.services.providers.api.ProviderFailureCode.CREDENTIAL_UNAVAILABLE -> CodexAuthFailureCode.SIGNED_OUT
    else -> CodexAuthFailureCode.INTERNAL
}
