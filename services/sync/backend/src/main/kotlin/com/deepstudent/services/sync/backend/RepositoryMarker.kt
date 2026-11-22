package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import java.time.Instant

internal const val MARKER_KEY = PairingService.ENCRYPTION_MARKER_KEY
internal const val MARKER_BYTES = 64 * 1024

internal class RepositoryMarker(val creator: String, val createdAt: String, val verifier: SyncPasswordVerifier?) {
    fun encodeV2(key: SyncPasswordVerifier): ByteArray {
        val verifier = JsonValue.Object.Builder().put("kdf", "argon2id").put("mCost", key.memoryKiB)
            .put("tCost", key.iterations).put("pCost", key.parallelism).put("salt", key.saltHex)
            .put("digest", key.digestHex).build()
        return JsonValue.Object.Builder().put("version", 2).put("createdByDevice", creator)
            .put("createdAt", createdAt).put("keyVerifier", verifier).build().literal().toByteArray()
    }

    companion object {
        private val salt = Regex("[0-9a-fA-F]{16,128}")
        private val digest = Regex("[0-9a-fA-F]{64}")

        fun decode(bytes: ByteArray): RepositoryMarker = try {
            require(bytes.size <= MARKER_BYTES)
            val marker = WireValues.parse(bytes)
            val creator = WireValues.text(marker["createdByDevice"]).also { require(it.isNotBlank()) }
            val createdAt = WireValues.text(marker["createdAt"]).also { Instant.parse(it) }
            val key = marker["keyVerifier"]
            val verifier = when (WireValues.unsigned(marker["version"], 2uL).toInt()) {
                1 -> { require(key == null || key == JsonValue.Null); null }
                2 -> decodeVerifier(WireValues.obj(key))
                else -> invalidMarker()
            }
            RepositoryMarker(creator, createdAt, verifier)
        } catch (_: Exception) { invalidMarker() }

        private fun decodeVerifier(key: JsonValue.Object): SyncPasswordVerifier {
            require(WireValues.text(key["kdf"]) == "argon2id")
            return SyncPasswordVerifier(parameter(key, "mCost"), parameter(key, "tCost"), parameter(key, "pCost"),
                WireValues.text(key["salt"]).also { require(salt.matches(it) && it.length % 2 == 0) },
                WireValues.text(key["digest"]).also { require(digest.matches(it)) })
        }

        private fun parameter(key: JsonValue.Object, name: String): Long =
            WireValues.unsigned(key[name], UInt.MAX_VALUE.toULong()).toLong()
    }
}

internal fun invalidMarker(): Nothing = throw SyncFailure(SyncError.INVALID_ENCRYPTION_MARKER)
