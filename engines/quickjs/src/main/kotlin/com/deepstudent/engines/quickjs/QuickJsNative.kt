package com.deepstudent.engines.quickjs

internal interface QuickJsHostBridge {
    fun onCall(tool: String, argsJson: String): String
}

internal object QuickJsNative {
    init {
        System.loadLibrary("dstu_quickjs")
    }

    external fun nativeCreate(
        bridge: QuickJsHostBridge,
        maxCalls: Int,
        wallMillis: Long,
        memoryBytes: Long,
        stackBytes: Long,
        maxTicks: Int,
        resultBytes: Int,
    ): Long

    external fun nativeExecute(handle: Long, source: String): String
    external fun nativeInterrupt(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeVersion(): String
}
