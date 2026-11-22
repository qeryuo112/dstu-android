package com.deepstudent.platform.security

/** Resource admission is separate from the frozen DSBK protocol limits. */
internal object DsbkMemoryBudget {
    private const val BLOCK_AND_OBJECT_BYTES = 1152L
    private const val BASE_HEADROOM = 32L * 1024L * 1024L
    // v1 retains ciphertext while GCM returns plaintext and the in-memory
    // staging buffer grows/copies. Reserve for those overlapping arrays.
    const val V1_ARRAY_FACTOR = 4L
    const val V2_ARRAY_FACTOR = 3L
    val operationGate = Any()

    fun requireAvailable(
        params: DsbkKdfParams,
        availableBytes: Long? = null,
        reserveBytes: Long = 0,
    ) {
        val base = baseBytes(params)
        if (reserveBytes < 0 || reserveBytes > Long.MAX_VALUE - base) {
            throw DsbkException(DsbkError.MEMORY_BUDGET_EXCEEDED)
        }
        val available = availableBytes ?: availableAfterCollectionIfNeeded(base + reserveBytes)
        if (available < base) throw DsbkException(DsbkError.KDF_BUDGET_EXCEEDED)
        if (reserveBytes > available - base) throw DsbkException(DsbkError.MEMORY_BUDGET_EXCEEDED)
    }

    /** Unreachable pools count as used until GC; never rely on an optimistic max-heap-only estimate. */
    fun availableHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long {
        val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
        return (runtime.maxMemory() - used).coerceAtLeast(0)
    }

    fun v1PlaintextCapacity(params: DsbkKdfParams, maxOutputBytes: Long): Long {
        val base = baseBytes(params)
        val target = minOf(DsbkCrypto.MAX_IN_MEMORY_BYTES, maxOutputBytes) + DsbkFormat.GCM_TAG_LEN
        val available = availableAfterCollectionIfNeeded(base + target * V1_ARRAY_FACTOR)
        requireAvailable(params, available)
        return ((available - base) / V1_ARRAY_FACTOR - DsbkFormat.GCM_TAG_LEN).coerceAtLeast(0)
    }

    private fun baseBytes(params: DsbkKdfParams): Long {
        params.validateBudget()
        return params.memoryKiB * BLOCK_AND_OBJECT_BYTES + BASE_HEADROOM
    }

    private fun availableAfterCollectionIfNeeded(required: Long): Long {
        val first = availableHeapBytes()
        if (first >= required) return first
        // Repeat once after a GC request so unreachable previous Argon2 pools
        // do not indefinitely reject valid consecutive operations. If the VM
        // cannot reclaim them, still fail closed rather than allocating.
        Runtime.getRuntime().gc()
        return availableHeapBytes()
    }
}
