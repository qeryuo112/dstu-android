package com.deepstudent.services.providers.api

/**
 * Account credential lease for a single Codex request.
 *
 * The credential is owned by this value and is wiped by [close]. Account IDs
 * and token bytes never appear in diagnostics.
 */
class CodexRequestAuth(val credential: ProviderCredential, val accountId: String, val generation: Long, val fedramp: Boolean = false) :
    AutoCloseable {
    private var closed = false

    init {
        require(accountId.trim().isNotEmpty()) { "account id is required" }
        require(generation >= 0) { "invalid auth generation" }
    }

    @Synchronized
    fun accountIdForRequest(): String {
        check(!closed) { "request auth is closed" }
        return accountId.trim()
    }

    @Synchronized
    fun isClosed(): Boolean = closed

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            credential.close()
        }
    }

    override fun toString(): String = "CodexRequestAuth(account=redacted, generation=$generation, " +
        "fedramp=$fedramp, closed=$closed)"
}

/** Account-auth bridge used only after the Responses route is validated. */
interface CodexRequestAuthPort {
    suspend fun requestAuth(cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth>

    suspend fun refreshAfterUnauthorized(generation: Long, cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth>

    suspend fun markReauthenticationRequired(generation: Long)
}
