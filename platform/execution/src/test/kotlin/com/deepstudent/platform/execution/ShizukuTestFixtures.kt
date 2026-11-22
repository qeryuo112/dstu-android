package com.deepstudent.platform.execution

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal class FakeConnectionSource : ShizukuConnectionSource {
    @Volatile
    var installedVersion: String? = "13.5.4"
    @Volatile
    var binding: ShizukuBinding? = null
    @Volatile
    var bindingError: Exception? = null
    @Volatile
    var permissionResult: Boolean = false

    val listeners = Collections.synchronizedSet(
        mutableSetOf<ShizukuConnectionListener>(),
    )
    var permissionRequests = 0
    var heldPermission: ((Boolean) -> Unit)? = null
    var holdPermission = false
    @Volatile
    var closed = false

    override fun installedVersionName(): String? = installedVersion

    override fun currentBinding(): ShizukuBinding? {
        bindingError?.let { throw it }
        return binding
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        permissionRequests++
        if (holdPermission) heldPermission = onResult else onResult(permissionResult)
    }

    override fun addListener(listener: ShizukuConnectionListener) {
        listeners += listener
    }

    override fun removeListener(listener: ShizukuConnectionListener) {
        listeners -= listener
    }

    override fun close() {
        closed = true
        listeners.clear()
    }

    fun receiveBinder() {
        synchronized(listeners) { listeners.toList() }.forEach {
            it.onBinderReceived()
        }
    }

    fun killBinder() {
        synchronized(listeners) { listeners.toList() }.forEach {
            it.onBinderDied()
        }
    }
}

internal class FakeServiceTransport(
    @Volatile var uid: Int = 2000,
    @Volatile var process: RemoteProcessHandle? = null,
    @Volatile var permissionGranted: Boolean = true,
    var startError: Exception? = null,
) : ShizukuServiceTransport {
    val requests = Collections.synchronizedList(mutableListOf<List<String>>())
    @Volatile
    var closed = false

    val uidReads = java.util.concurrent.atomic.AtomicInteger()

    override fun uid(): Int {
        uidReads.incrementAndGet()
        return uid
    }

    var permissionCheck: (() -> Boolean)? = null
    var processFactory: (() -> RemoteProcessHandle)? = null

    override fun checkSelfPermission(): Boolean = permissionCheck?.invoke() ?: permissionGranted

    override fun newProcess(argv: Array<String>): RemoteProcessHandle {
        requests += argv.toList()
        startError?.let { throw it }
        return processFactory?.invoke() ?: process ?: FakeRemoteProcess()
    }

    override fun close() {
        closed = true
    }
}

internal class FakeRemoteProcess(
    stdout: String = "",
    stderr: String = "",
    private val exitCode: Int = 0,
    private val onWait: (() -> Unit)? = null,
) : RemoteProcessHandle {
    private val output = ByteArrayInputStream(stdout.toByteArray())
    private val error = ByteArrayInputStream(stderr.toByteArray())
    private val closed = AtomicBoolean(false)
    var destroyCount = 0
    var closeCount = 0

    override fun stdout(): InputStream = output

    override fun stderr(): InputStream = error

    override fun waitFor(): Int {
        onWait?.invoke()
        return exitCode
    }

    override fun destroy() {
        destroyCount++
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount++
        output.close()
        error.close()
    }
}

internal fun binding(
    transport: ShizukuServiceTransport,
    uid: Int = 2000,
    id: String = "binder-$uid",
    alive: () -> Boolean = { true },
): ShizukuBinding = ShizukuBinding(
    bindingId = id,
    backendUid = uid,
    serviceVersion = 13,
    isAlive = alive,
    transport = transport,
)
