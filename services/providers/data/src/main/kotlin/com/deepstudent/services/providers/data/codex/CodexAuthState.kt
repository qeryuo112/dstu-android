package com.deepstudent.services.providers.data.codex

import kotlinx.coroutines.CompletableDeferred

/** Browser half of a live attempt; the PKCE verifier never leaves this process. */
internal data class CodexBrowserAttempt(val redirectUri: String, val state: String, val verifier: String)

/** Device half once the user-code response has been accepted. */
internal data class CodexDeviceAttempt(val deviceAuthId: String, val intervalSeconds: Int)

/** Fields shown while a login is in progress; URLs and codes are never logged. */
internal data class CodexAttemptPrompt(
    val authorizationUrl: String? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val pollIntervalSeconds: Int? = null,
)

internal data class CodexAttemptProgress(val previousPhase: CodexAuthPhase? = null, val completing: Boolean = false)

internal data class CodexAuthAttempt(
    val id: String,
    val kind: CodexLoginKind,
    val expiresAtMillis: Long,
    val browser: CodexBrowserAttempt? = null,
    val device: CodexDeviceAttempt? = null,
    val prompt: CodexAttemptPrompt = CodexAttemptPrompt(),
    val cancel: CompletableDeferred<Unit> = CompletableDeferred(),
    private val progress: CodexAttemptProgress = CodexAttemptProgress(),
) {
    val previousPhase: CodexAuthPhase? get() = progress.previousPhase
    val completing: Boolean get() = progress.completing

    fun covering(phase: CodexAuthPhase) = copy(progress = progress.copy(previousPhase = phase))

    fun claimed() = copy(progress = progress.copy(completing = true))

    init {
        require(id.isNotBlank() && expiresAtMillis > 0) { "invalid Codex attempt" }
    }

    override fun toString(): String = "CodexAuthAttempt(kind=$kind, expiresAtMillis=$expiresAtMillis, completing=$completing)"
}

internal data class CodexAuthSnapshot(
    val session: CodexSession?,
    val generation: Long,
    val phase: CodexAuthPhase,
    val attempt: CodexAuthAttempt?,
    val lastFailure: CodexAuthFailure?,
) {
    fun basePhase(): CodexAuthPhase = if (session != null) CodexAuthPhase.AUTHENTICATED else CodexAuthPhase.SIGNED_OUT

    fun requiresReauthentication(): Boolean =
        phase == CodexAuthPhase.REAUTHENTICATION_REQUIRED || attempt?.previousPhase == CodexAuthPhase.REAUTHENTICATION_REQUIRED

    fun hasUsableSession(): Boolean = session != null && !requiresReauthentication()

    fun stablePhase(next: CodexAuthPhase): CodexAuthSnapshot {
        val live = attempt ?: return copy(phase = next)
        return copy(phase = CodexAuthPhase.AUTHORIZING, attempt = live.covering(next))
    }

    fun restorePhase(previous: CodexAuthPhase?): CodexAuthPhase = when {
        previous == CodexAuthPhase.REAUTHENTICATION_REQUIRED -> CodexAuthPhase.REAUTHENTICATION_REQUIRED
        previous == CodexAuthPhase.ERROR && session == null -> CodexAuthPhase.ERROR
        else -> basePhase()
    }

    fun recordRefreshFailure(observedGeneration: Long, failure: CodexAuthFailure, persistenceUncertain: Boolean): CodexAuthSnapshot {
        if (generation != observedGeneration) return this
        val next = when {
            persistenceUncertain || failure.reauthenticationRequired -> CodexAuthPhase.REAUTHENTICATION_REQUIRED
            session != null -> CodexAuthPhase.AUTHENTICATED
            else -> CodexAuthPhase.ERROR
        }
        return copy(lastFailure = failure).stablePhase(next)
    }
}

/** Snapshots change only inside [update]; no network or storage call happens while holding the monitor. */
internal class CodexAuthState {
    private val lock = Any()

    @Volatile
    private var current = CodexAuthSnapshot(
        session = null,
        generation = 1,
        phase = CodexAuthPhase.SIGNED_OUT,
        attempt = null,
        lastFailure = null,
    )

    fun read(): CodexAuthSnapshot = current

    fun update(block: (CodexAuthSnapshot) -> CodexAuthSnapshot): CodexAuthSnapshot = synchronized(lock) {
        block(current).also { current = it }
    }

    fun loadSession(session: CodexSession?): CodexAuthSnapshot = update {
        it.copy(session = session, phase = if (session != null) CodexAuthPhase.AUTHENTICATED else CodexAuthPhase.SIGNED_OUT)
    }

    fun loadFailure(failure: CodexAuthFailure): CodexAuthSnapshot = update {
        it.copy(session = null, attempt = null, phase = CodexAuthPhase.ERROR, lastFailure = failure)
    }

    fun installAttempt(attempt: CodexAuthAttempt): CodexAuthSnapshot = update { state ->
        if (state.attempt != null) throw CodexAuthException(codexFailure(CodexAuthFailureCode.LOGIN_BUSY, attempt.stage()))
        state.copy(attempt = attempt.covering(state.phase), phase = CodexAuthPhase.AUTHORIZING, lastFailure = null)
    }

    fun claimAttempt(attemptId: String): Boolean = synchronized(lock) {
        val live = current.attempt?.takeIf { it.id == attemptId && !it.completing } ?: return false
        current = current.copy(attempt = live.claimed())
        true
    }

    fun finishAttemptError(attemptId: String, failure: CodexAuthFailure): CodexAuthSnapshot = update { state ->
        val attempt = state.attempt?.takeIf { it.id == attemptId } ?: return@update state
        val phase = when {
            failure.reauthenticationRequired -> CodexAuthPhase.REAUTHENTICATION_REQUIRED
            state.phase == CodexAuthPhase.AUTHORIZING -> state.restorePhase(attempt.previousPhase)
            else -> state.basePhase()
        }
        state.copy(attempt = null, phase = phase, lastFailure = failure)
    }

    /** Removes the live attempt, restoring the phase it covered. Used by cancel/logout. */
    fun removeAttempt(): CodexAuthAttempt? = synchronized(lock) {
        val attempt = current.attempt ?: return null
        val phase = if (current.phase == CodexAuthPhase.AUTHORIZING) current.restorePhase(attempt.previousPhase) else current.phase
        current = current.copy(attempt = null, phase = phase)
        attempt
    }

    fun commitLogin(attemptId: String, session: CodexSession): Boolean = synchronized(lock) {
        if (current.attempt?.id != attemptId) return false
        current = current.copy(
            session = session,
            attempt = null,
            generation = current.generation + 1,
            phase = CodexAuthPhase.AUTHENTICATED,
            lastFailure = null,
        )
        true
    }

    fun commitRefresh(observedGeneration: Long, session: CodexSession): CodexAuthSnapshot = update { state ->
        if (state.generation != observedGeneration) return@update state
        state.copy(session = session, generation = state.generation + 1).stablePhase(CodexAuthPhase.AUTHENTICATED).copy(lastFailure = null)
    }

    fun markReauthenticationRequired(observedGeneration: Long): CodexAuthSnapshot = update { state ->
        if (state.generation != observedGeneration) return@update state
        state.copy(lastFailure = codexReauthenticationFailure()).stablePhase(CodexAuthPhase.REAUTHENTICATION_REQUIRED)
    }

    fun signedOut(): CodexAuthSnapshot = update {
        it.copy(session = null, attempt = null, generation = it.generation + 1, phase = CodexAuthPhase.SIGNED_OUT, lastFailure = null)
    }

    private fun CodexAuthAttempt.stage(): CodexAuthStage =
        if (kind == CodexLoginKind.BROWSER) CodexAuthStage.AUTHORIZATION else CodexAuthStage.DEVICE_AUTHORIZATION
}
