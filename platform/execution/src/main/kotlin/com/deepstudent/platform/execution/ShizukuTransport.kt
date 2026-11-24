package com.deepstudent.platform.execution

import java.io.Closeable
import java.io.InputStream

internal data class ShizukuBinding(
    val bindingId: String,
    val backendUid: Int,
    val serviceVersion: Int,
    val isAlive: () -> Boolean,
    val transport: ShizukuServiceTransport,
)

internal interface ShizukuServiceTransport {
    /** Authorized fresh probe only. Pre-grant identity comes from SDK bindApplication metadata. */
    fun uid(): Int

    /**
     * Fresh binder check. `Shizuku.checkSelfPermission()` caches the grant for
     * the lifetime of the process, which hides a revocation until restart.
     */
    fun checkSelfPermission(): Boolean

    fun newProcess(argv: Array<String>): RemoteProcessHandle

    /** Approved shell owners hold a control pipe; old fixed diagnostics still use EOF stdin. */
    fun newOwnedProcess(argv: Array<String>): RemoteProcessHandle = throw java.io.IOException("owned shell unavailable")

    fun close()
}

internal interface RemoteProcessHandle : Closeable {
    fun stdout(): InputStream

    fun stderr(): InputStream

    fun waitFor(): Int

    fun destroy()
}

internal interface ShizukuConnectionListener {
    fun onBinderReceived()

    fun onBinderDied()
}

internal interface ShizukuConnectionSource : Closeable {
    fun installedVersionName(): String?

    fun currentBinding(): ShizukuBinding?

    fun requestPermission(onResult: (Boolean) -> Unit)

    fun addListener(listener: ShizukuConnectionListener)

    fun removeListener(listener: ShizukuConnectionListener)
}
