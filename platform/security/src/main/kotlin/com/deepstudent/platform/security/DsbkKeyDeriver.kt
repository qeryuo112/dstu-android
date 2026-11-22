package com.deepstudent.platform.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

interface DsbkKeyDeriver {
    /** Returns an owned 32-byte key. The caller must erase it after use. */
    fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray
}

class Argon2idKeyDeriver : DsbkKeyDeriver {
    override fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        params.validateBudget()
        params.validateForDerivation()
        if (salt.size < 8) throw DsbkException(DsbkError.KDF_FAILED)
        // All production instances share the gate: concurrency must not multiply
        // the frozen (up to 1 GiB) Argon2 allocation by the number of callers.
        synchronized(DsbkMemoryBudget.operationGate) {
            DsbkMemoryBudget.requireAvailable(params)
            val passwordBytes = try {
                Utf8Bytes.encode(password)
            } catch (_: Exception) {
                throw DsbkException(DsbkError.KDF_FAILED)
            }
            val key = ByteArray(DsbkFormat.KEY_LEN)
            val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB.toInt())
                .withIterations(params.iterations.toInt())
                .withParallelism(params.parallelism.toInt())
            val parameters = builder.build()
            try {
                val generator = Argon2BytesGenerator()
                generator.init(parameters)
                generator.generateBytes(passwordBytes, key)
                return key
            } catch (_: OutOfMemoryError) {
                key.wipe()
                throw DsbkException(DsbkError.KDF_BUDGET_EXCEEDED)
            } catch (_: Exception) {
                key.wipe()
                throw DsbkException(DsbkError.KDF_FAILED)
            } finally {
                passwordBytes.wipe()
                parameters.clear()
                builder.clear()
            }
        }
    }

}
