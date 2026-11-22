package com.deepstudent.platform.security

enum class CredentialStoreError(val fixedMessage: String) {
    INVALID_KEY("credential key is invalid"),
    INVALID_VALUE("credential value is invalid"),
    MASTER_KEY_UNAVAILABLE("credential master key is unavailable"),
    MASTER_KEY_RESET_NOT_ALLOWED("credential master key reset is not allowed"),
    INTEGRITY_FAILURE("credential data failed authentication"),
    NOT_FOUND("credential was not found"),
    STORAGE_UNAVAILABLE("credential storage is unavailable"),
    CLOSED("credential store is closed"),
}

/** A reset is permissible only for a positively established permanent loss. */
enum class MasterKeyFailure {
    PERMANENTLY_LOST,
    UNCONFIRMED,
}

class CredentialStoreException(
    val error: CredentialStoreError,
    val masterKeyFailure: MasterKeyFailure? =
        if (error == CredentialStoreError.MASTER_KEY_UNAVAILABLE) MasterKeyFailure.UNCONFIRMED else null,
) : Exception(error.fixedMessage)

enum class DsbkError(val fixedMessage: String) {
    HEADER_INVALID("encrypted container header is invalid"),
    VERSION_UNSUPPORTED("encrypted container version is not supported"),
    KDF_BUDGET_EXCEEDED("encrypted container KDF parameters exceed the local budget"),
    KDF_FAILED("encrypted container key derivation failed"),
    MEMORY_BUDGET_EXCEEDED("encrypted container exceeds the local memory budget"),
    V1_CONVERSION_REQUIRED("legacy encrypted container requires separate conversion"),
    CHUNK_INVALID("encrypted container chunk size is invalid"),
    DATA_MISSING("encrypted container has no authenticated data"),
    AUTHENTICATION_FAILED("encrypted container authentication failed"),
    INPUT_LIMIT_EXCEEDED("encrypted container input exceeds its budget"),
    OUTPUT_LIMIT_EXCEEDED("decrypted output exceeds its budget"),
    CRYPTO_FAILED("encrypted container operation failed"),
    IO_FAILED("encrypted container I/O failed"),
    STAGING_FAILED("encrypted container staging activation failed"),
}

class DsbkException(
    val error: DsbkError,
) : Exception(error.fixedMessage)

internal fun masterKeyLost(): CredentialStoreException = CredentialStoreException(
    CredentialStoreError.MASTER_KEY_UNAVAILABLE,
    MasterKeyFailure.PERMANENTLY_LOST,
)

internal fun masterKeyUnconfirmed(): CredentialStoreException = CredentialStoreException(
    CredentialStoreError.MASTER_KEY_UNAVAILABLE,
    MasterKeyFailure.UNCONFIRMED,
)
