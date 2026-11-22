package com.deepstudent.platform.execution

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * The fixed 13.1.5 SDK exposes getBinder(), NOT getService()/requireService().
 * SDK handshake metadata is available before authorization. Only permission and
 * authorized execution use fresh AIDL calls; raw metadata queries require a grant.
 */
internal class AndroidShizukuConnectionSource(
    context: Context,
    private val log: ExecutionLogSink = AndroidExecutionLogSink,
) : ShizukuConnectionSource {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = mutableSetOf<ShizukuConnectionListener>()
    private var closed = false
    private var sdkListenersRegistered = false
    private var receivedBinder: IBinder? = null
    private var cachedBinder: IBinder? = null
    private var cachedBinding: ShizukuBinding? = null
    private var pendingPermission: PermissionRequest? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        synchronized(lock) { receivedBinder = Shizuku.getBinder() }
        notifyListeners(received = true)
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        synchronized(lock) {
            receivedBinder = null
            cachedBinder = null
            cachedBinding = null
        }
        finishPendingPermission(false)
        notifyListeners(received = false)
    }

    override fun installedVersionName(): String? = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0).versionName
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    override fun currentBinding(): ShizukuBinding? {
        val binder = Shizuku.getBinder() ?: return null
        if (!binder.isBinderAlive) return null
        synchronized(lock) {
            // A live binder alone does not mean attachApplication has completed.
            if (closed || receivedBinder !== binder) return null
            if (cachedBinder === binder) return cachedBinding
        }
        val binding = AndroidShizukuAccess.binding(binder, log)
        synchronized(lock) {
            // A death/replacement during either IPC cannot install a stale binding.
            if (closed || !binder.isBinderAlive || Shizuku.getBinder() !== binder) return null
            cachedBinder = binder
            cachedBinding = binding
        }
        return binding
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        val request = synchronized(lock) {
            if (closed || pendingPermission != null) null else {
                PermissionRequest(NEXT_PERMISSION_CODE.incrementAndGet(), onResult).also { pendingPermission = it }
            }
        }
        if (request == null) {
            onResult(false)
            return
        }
        mainHandler.post { beginPermissionRequest(request) }
    }

    private fun beginPermissionRequest(request: PermissionRequest) {
        val current = synchronized(lock) { !closed && pendingPermission === request }
        if (!current) return
        if (!Shizuku.pingBinder()) {
            finishPermissionRequest(request, false)
            return
        }
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == request.code) {
                finishPermissionRequest(request, result == PackageManager.PERMISSION_GRANTED)
            }
        }
        request.listener = listener
        try {
            Shizuku.addRequestPermissionResultListener(listener, mainHandler)
            log.atStage(ExecutionStage.PERMISSION_REQUEST) { Shizuku.requestPermission(request.code) }
        } catch (error: Exception) {
            finishPermissionRequest(request, false)
        }
    }

    override fun addListener(listener: ShizukuConnectionListener) {
        synchronized(lock) {
            if (closed) return
            listeners += listener
        }
        mainHandler.post { reconcileSdkListeners() }
    }

    override fun removeListener(listener: ShizukuConnectionListener) {
        synchronized(lock) { listeners -= listener }
        mainHandler.post { reconcileSdkListeners() }
    }

    private fun reconcileSdkListeners() {
        val needed = synchronized(lock) { !closed && listeners.isNotEmpty() }
        if (needed == sdkListenersRegistered) return
        if (needed) {
            Shizuku.addBinderReceivedListenerSticky(binderReceived, mainHandler)
            Shizuku.addBinderDeadListener(binderDead, mainHandler)
        } else {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
        }
        sdkListenersRegistered = needed
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            listeners.clear()
            receivedBinder = null
            cachedBinder = null
            cachedBinding = null
        }
        mainHandler.post {
            reconcileSdkListeners()
            finishPendingPermission(false)
        }
    }

    private fun finishPendingPermission(granted: Boolean) {
        val request = synchronized(lock) { pendingPermission } ?: return
        finishPermissionRequest(request, granted)
    }

    private fun finishPermissionRequest(request: PermissionRequest, granted: Boolean) {
        val accepted = synchronized(lock) {
            if (pendingPermission !== request) false else {
                pendingPermission = null
                true
            }
        }
        if (!accepted) return
        request.listener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        request.onResult(granted)
    }

    private fun notifyListeners(received: Boolean) {
        val snapshot = synchronized(lock) { if (closed) emptyList() else listeners.toList() }
        snapshot.forEach {
            if (received) it.onBinderReceived() else it.onBinderDied()
        }
    }

    private class PermissionRequest(val code: Int, val onResult: (Boolean) -> Unit) {
        var listener: Shizuku.OnRequestPermissionResultListener? = null
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        val NEXT_PERMISSION_CODE = AtomicInteger(2200)
    }
}
