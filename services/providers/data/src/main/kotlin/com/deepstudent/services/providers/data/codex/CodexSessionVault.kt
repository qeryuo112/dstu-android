package com.deepstudent.services.providers.data.codex

/**
 * Encrypted persistence seam for the versioned Codex session serialization.
 *
 * Ownership is deliberately explicit: [load] returns a caller-owned UTF-8
 * copy, while [save] transfers the supplied bytes to the vault. Implementations
 * must zero a saved buffer before returning and must not retain its reference.
 */
interface CodexSessionVault {
    suspend fun load(): ByteArray?

    suspend fun save(serializedUtf8: ByteArray)

    suspend fun clear()
}
