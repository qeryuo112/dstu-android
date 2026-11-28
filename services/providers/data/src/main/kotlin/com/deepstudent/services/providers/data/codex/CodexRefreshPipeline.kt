package com.deepstudent.services.providers.data.codex

import com.deepstudent.services.providers.api.CodexRequestAuth
import com.deepstudent.services.providers.api.ProviderCancellation
import kotlin.coroutines.cancellation.CancellationException

/**
 * Locked refresh pipeline: reuse checks, token exchange, merge, identity validation,
 * persistence and the generation-aware commit. Every stage preserves the frozen desktop
 * ordering, including the stale-generation rechecks before identity and persistence.
 * The caller holds the commit gate for the whole run.
 */
internal class CodexRefreshPipeline(private val core: CodexAuthCore) {
    suspend fun perform(observedGeneration: Long, force: Boolean, cancellation: ProviderCancellation): CodexRequestAuth {
        val before = core.state.read()
        reusableLease(before, observedGeneration, force)?.let { return it }
        beginRefresh(before, observedGeneration)
        val current = before.session ?: throw CodexAuthException(signedOutFailure())
        val tokens = exchangeRefreshToken(observedGeneration, current, cancellation)
        val refreshed = mergeRefreshedSession(observedGeneration, current, tokens)
        if (staleGeneration(observedGeneration, STALE_OUTCOME)) return leaseCurrent()
        validateRefreshedIdentity(observedGeneration, current, refreshed)
        persistRefreshedSession(observedGeneration, refreshed)
        if (staleGeneration(observedGeneration, null)) return leaseCurrent()
        return commitRefresh(observedGeneration, refreshed)
    }

    /** Stage: skip the network when the generation moved or the stored session is still fresh. */
    private fun reusableLease(before: CodexAuthSnapshot, observedGeneration: Long, force: Boolean): CodexRequestAuth? {
        if (before.requiresReauthentication()) throw CodexAuthException(reauthFailure())
        val current = before.session ?: throw CodexAuthException(signedOutFailure())
        if (before.generation != observedGeneration ||
            (!force && current.expiresAtMillis > core.clock() + CodexAuthCore.REFRESH_WINDOW_MILLIS)
        ) {
            return leaseCurrent()
        }
        return null
    }

    /** Stage: park the phase under a visible refresh unless a login attempt already covers it. */
    private fun beginRefresh(before: CodexAuthSnapshot, observedGeneration: Long) {
        if (before.attempt == null && before.phase != CodexAuthPhase.REFRESHING) {
            core.state.update { it.copy(phase = CodexAuthPhase.REFRESHING) }
        }
        core.trace.record("refresh", "STARTED", generation = observedGeneration)
    }

    private suspend fun exchangeRefreshToken(
        observedGeneration: Long,
        current: CodexSession,
        cancellation: ProviderCancellation,
    ): CodexTokenResponse = try {
        core.tokenRequest(CodexAuthStage.REFRESH, refreshFields(current), cancellation)
    } catch (failure: CodexAuthException) {
        recordFailure(observedGeneration, failure.failure, persistenceUncertain = false)
        throw failure
    }

    private fun refreshFields(current: CodexSession): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to current.refreshToken,
        "client_id" to core.endpoints.clientId,
    )

    private fun mergeRefreshedSession(observedGeneration: Long, current: CodexSession, tokens: CodexTokenResponse): CodexSession = try {
        CodexTokenPolicy.mergeRefreshSession(current, tokens, core.clock())
    } catch (failure: CodexWireFailure) {
        recordFailure(observedGeneration, failure.toFailure(), persistenceUncertain = false)
        throw CodexAuthException(failure.toFailure())
    }

    private fun staleGeneration(observedGeneration: Long, outcome: String?): Boolean {
        val stale = core.state.read().generation != observedGeneration
        if (stale && outcome != null) core.trace.record("refresh", outcome, generation = observedGeneration)
        return stale
    }

    private fun validateRefreshedIdentity(observedGeneration: Long, requested: CodexSession, refreshed: CodexSession) {
        val live = core.state.read().session ?: throw CodexAuthException(signedOutFailure())
        try {
            CodexTokenPolicy.validateRefreshIdentity(live, requested, refreshed)
        } catch (failure: CodexWireFailure) {
            recordFailure(observedGeneration, failure.toFailure(), persistenceUncertain = false)
            throw CodexAuthException(failure.toFailure())
        }
    }

    private suspend fun persistRefreshedSession(observedGeneration: Long, refreshed: CodexSession) {
        try {
            CodexSessionCodec.encode(refreshed).also { core.vault.save(it) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            val storeFailure = coreFailure(CodexAuthFailureCode.CREDENTIAL_STORE, CodexAuthStage.REFRESH)
            recordFailure(observedGeneration, storeFailure, persistenceUncertain = true)
            throw CodexAuthException(storeFailure)
        }
    }

    private fun commitRefresh(observedGeneration: Long, refreshed: CodexSession): CodexRequestAuth {
        val committed = core.state.commitRefresh(observedGeneration, refreshed)
        core.trace.record("refresh", "COMPLETED", generation = committed.generation)
        return core.lease(refreshed, committed.generation)
    }

    private fun leaseCurrent(): CodexRequestAuth {
        val live = core.state.read()
        val session = live.session ?: throw CodexAuthException(signedOutFailure())
        return core.lease(session, live.generation)
    }

    private fun recordFailure(observedGeneration: Long, failure: CodexAuthFailure, persistenceUncertain: Boolean) {
        core.state.update { it.recordRefreshFailure(observedGeneration, failure, persistenceUncertain) }
        core.trace.record("refresh", failure.code.name, generation = observedGeneration)
    }

    private companion object {
        const val STALE_OUTCOME = "STALE"
    }
}
