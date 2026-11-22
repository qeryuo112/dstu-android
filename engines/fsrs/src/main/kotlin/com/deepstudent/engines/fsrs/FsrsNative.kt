package com.deepstudent.engines.fsrs

/** JNI surface for the frozen fsrs 6.6.2 crate. */
object FsrsNative {
    private const val LIBRARY = "dstu_fsrs"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary(LIBRARY)
        loaded = true
    }

    fun isLoaded(): Boolean = loaded

    @JvmStatic
    external fun version(): String

    @JvmStatic
    external fun normalizeParameters(parameters: FloatArray): FloatArray

    @JvmStatic
    external fun nextStates(
        parameters: FloatArray,
        hasMemory: Boolean,
        stability: Float,
        difficulty: Float,
        desiredRetention: Float,
        daysElapsed: Long,
    ): FloatArray

    @JvmStatic
    external fun memoryState(
        parameters: FloatArray,
        ratings: IntArray,
        deltas: LongArray,
        hasStarting: Boolean,
        stability: Float,
        difficulty: Float,
    ): FloatArray

    @JvmStatic
    external fun evaluate(
        parameters: FloatArray,
        offsets: IntArray,
        ratings: IntArray,
        deltas: LongArray,
        cancelToken: Long,
    ): FloatArray

    @JvmStatic
    external fun optimize(
        offsets: IntArray,
        ratings: IntArray,
        deltas: LongArray,
        cardIds: LongArray,
        enableShortTerm: Boolean,
        numRelearningSteps: Int,
        cancelToken: Long,
    ): FloatArray

    @JvmStatic
    external fun createCancelToken(): Long

    @JvmStatic
    external fun cancelToken(pointer: Long)

    @JvmStatic
    external fun destroyCancelToken(pointer: Long)
}
