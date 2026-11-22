package com.deepstudent.services.sync.api

data class SyncConfiguration(
    val endpoint: String,
    val username: String,
    val root: String,
    val deviceId: String,
    val instanceId: String,
    val encrypted: Boolean = false,
) {
    override fun toString(): String = "SyncConfiguration(redacted)"
}

data class PairingRequest(
    val endpoint: String,
    val username: String,
    val root: String,
    val password: CharArray,
    val encryptionPassword: CharArray? = null,
) {
    fun clearSecrets() {
        password.fill('\u0000')
        encryptionPassword?.fill('\u0000')
    }

    override fun toString(): String = "PairingRequest(redacted)"
}

enum class PairingMode { CREATE, JOIN }

enum class SyncError {
    INVALID_CONFIGURATION, INSECURE_ENDPOINT, AUTHENTICATION, NOT_FOUND,
    ALREADY_EXISTS, INCOMPATIBLE_FORMAT, ENCRYPTED_REPOSITORY, INVALID_REPOSITORY,
    ENCRYPTION_PASSWORD_INCORRECT, INVALID_ENCRYPTION_MARKER, ENCRYPTION_MODE_MISMATCH, ENCRYPTED_DATA_INVALID,
    ENCRYPTION_PROOF_REQUIRED, ENCRYPTION_CLAIM_CONFLICT, ENCRYPTION_UPGRADE_UNCERTAIN,
    NETWORK, STORAGE, CREDENTIAL_UNAVAILABLE, CREDENTIAL_INTEGRITY, CREDENTIAL_KEY_LOST, LIMIT_EXCEEDED, CANCELLED,
}

sealed interface SyncResult<out T> {
    data class Success<T>(val value: T) : SyncResult<T>
    data class Failure(val error: SyncError) : SyncResult<Nothing>
}

interface SyncConfigurationService {
    suspend fun current(): SyncResult<SyncConfiguration?>
    suspend fun pair(mode: PairingMode, request: PairingRequest): SyncResult<SyncConfiguration>
    suspend fun updateEncryptionPassword(password: CharArray): SyncResult<Unit>
    suspend fun clear(): SyncResult<Unit>

    /** Explicit, locally confirmed recovery. Does not change device identity or contact the repository. */
    suspend fun resetCredentialsAfterKeyLoss(): SyncResult<Unit> = SyncResult.Failure(SyncError.CREDENTIAL_UNAVAILABLE)
}
