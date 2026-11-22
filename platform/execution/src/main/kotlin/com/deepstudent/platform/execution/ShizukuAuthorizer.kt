package com.deepstudent.platform.execution

import android.os.RemoteException

import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuStatus
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the connection state machine and the verified backend identity.
 *
 * Permission is checked with a fresh binder call on every refresh. The public
 * `Shizuku.checkSelfPermission()` helper caches a granted result in a static
 * field, so a revocation made in the Shizuku app would otherwise stay invisible
 * until the Android process restarted.
 */
internal class ShizukuAuthorizer(
    private val source: ShizukuConnectionSource,
    private val log: ExecutionLogSink = AndroidExecutionLogSink,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMillis: Long = 2_000,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statusFlow = MutableStateFlow(ShizukuStatus())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val stateLock = Any()
    private var epoch = 0L
    private var pendingPermission: PermissionResult? = null

    @Volatile
    private var lastBinding: ShizukuBinding? = null

    @Volatile
    private var verifiedBindingId: String? = null

    @Volatile
    private var verifiedUid: Int? = null

    @Volatile
    private var everAuthorized = false

    private var monitorJob: Job? = null

    private val connectionListener = object : ShizukuConnectionListener {
        override fun onBinderReceived() {
            scope.launch { refresh() }
        }

        override fun onBinderDied() {
            markBinderDead()
        }
    }

    val status: StateFlow<ShizukuStatus> = statusFlow.asStateFlow()
    var onInvalidated: (ExecutionFailureCode) -> Unit = {}

    @Synchronized
    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        source.addListener(connectionListener)
        scope.launch { refresh() }
        monitorJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                refresh()
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        monitorJob?.cancel()
        monitorJob = null
        source.removeListener(connectionListener)
    }

    suspend fun refresh() {
        if (closed.get()) {
            publish(
                ShizukuStatus(
                    state = ShizukuConnectionState.CLOSED,
                    diagnostic = ExecutionFailureCode.SYSTEM_CLOSED,
                ),
            )
            return
        }
        refreshMutex.withLock {
            val version = synchronized(stateLock) { epoch }
            withContext(ioDispatcher) { refreshLocked(version) }
        }
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val result = PermissionResult(onResult)
        val accepted = synchronized(stateLock) {
            if (closed.get() || !statusFlow.value.canRequestPermission || pendingPermission != null) {
                false
            } else {
                pendingPermission = result
                true
            }
        }
        if (!accepted) {
            result.complete(false)
            return
        }
        source.requestPermission { granted ->
            if (closed.get()) {
                finishPermission(result, false)
            } else {
                scope.launch {
                    refresh()
                    finishPermission(result, granted && statusFlow.value.canExecute)
                }
            }
        }
    }

    private fun finishPermission(result: PermissionResult, granted: Boolean) {
        synchronized(stateLock) {
            if (pendingPermission === result) pendingPermission = null
        }
        result.complete(granted)
    }

    fun authorizedBinding(): ShizukuBinding? = synchronized(stateLock) {
        if (closed.get() || statusFlow.value.state != ShizukuConnectionState.READY) null
        else lastBinding
    }

    fun recordVerifiedUid(binding: ShizukuBinding, uid: Int) {
        synchronized(stateLock) {
            if (closed.get() || lastBinding?.bindingId != binding.bindingId) return
            if (statusFlow.value.state != ShizukuConnectionState.READY) return
            verifiedBindingId = binding.bindingId
            verifiedUid = uid
            publish(statusFlow.value.copy(observedUid = uid))
        }
    }

    fun clearVerifiedUid(binding: ShizukuBinding) {
        synchronized(stateLock) {
            if (closed.get() || lastBinding?.bindingId != binding.bindingId) return
            verifiedBindingId = null
            verifiedUid = null
            publish(statusFlow.value.copy(observedUid = null, diagnostic = ExecutionFailureCode.UID_MISMATCH))
        }
    }

    fun markBinderDead() {
        synchronized(stateLock) {
            if (closed.get()) return
            epoch++
            pendingPermission?.complete(false)
            pendingPermission = null
            val previous = statusFlow.value
            clearBinding()
            publish(
                ShizukuStatus(
                    state = ShizukuConnectionState.BINDER_DEAD,
                    installedVersionName = previous.installedVersionName,
                    serviceVersion = previous.serviceVersion,
                    backendUid = previous.backendUid,
                    diagnostic = ExecutionFailureCode.BINDER_DEAD,
                ),
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scope.cancel()
        source.close()
        synchronized(stateLock) {
            epoch++
            clearBinding()
            pendingPermission?.complete(false)
            pendingPermission = null
            publish(
                ShizukuStatus(
                    state = ShizukuConnectionState.CLOSED,
                    diagnostic = ExecutionFailureCode.SYSTEM_CLOSED,
                ),
            )
        }
    }

    private fun refreshLocked(version: Long) {
        val installed: String?
        val binding: ShizukuBinding?
        try {
            installed = source.installedVersionName()
            binding = source.currentBinding()
        } catch (error: Exception) {
            log.recordFailure(ExecutionStage.BINDING_LOOKUP, error)
            markRefreshFailure(version, error)
            return
        }
        if (binding == null) {
            commit(version) { publishUnavailable(installed, statusFlow.value) }
            return
        }
        val granted = readPermission(binding, version) ?: return
        commit(version) { publishBinding(binding, installed, granted) }
    }

    private fun readPermission(binding: ShizukuBinding, version: Long): Boolean? {
        if (binding.backendUid != 0 && binding.backendUid != 2000) return false
        return try {
            if (!binding.isAlive()) {
                markRefreshBinderDead(version)
                null
            } else {
                val granted = binding.transport.checkSelfPermission()
                if (granted) verifyGrantedUid(binding)
                granted
            }
        } catch (error: SecurityException) {
            log.recordFailure(ExecutionStage.PERMISSION_CHECK, error)
            false
        } catch (error: Exception) {
            log.recordFailure(ExecutionStage.PERMISSION_CHECK, error)
            markRefreshFailure(version, error)
            null
        }
    }

    private fun verifyGrantedUid(binding: ShizukuBinding) {
        log.atStage(ExecutionStage.UID_PROBE) {
            if (binding.transport.uid() != binding.backendUid) {
                throw ProcessRunFailure(ExecutionFailureCode.UID_MISMATCH)
            }
        }
    }

    private fun markRefreshFailure(version: Long, failure: Exception) {
        if (failure is RemoteException) {
            markRefreshBinderDead(version)
            return
        }
        commit(version) {
            epoch++
            val previous = statusFlow.value
            clearBinding()
            publish(
                previous.copy(
                    state = ShizukuConnectionState.PROTOCOL_ERROR,
                    observedUid = null,
                    diagnostic = if (failure is ProcessRunFailure) failure.code else ExecutionFailureCode.BINDER_PROTOCOL_FAILED,
                ),
            )
        }
    }

    private fun markRefreshBinderDead(version: Long) {
        synchronized(stateLock) {
            if (epoch == version) markBinderDead()
        }
    }

    private fun publishBinding(binding: ShizukuBinding, installed: String?, granted: Boolean) {
        val previousBinding = lastBinding
        if (previousBinding != null && previousBinding.bindingId != binding.bindingId) {
            onInvalidated(ExecutionFailureCode.BINDER_DEAD)
            clearBinding()
        }
        lastBinding = binding
        val trusted = binding.backendUid == 0 || binding.backendUid == 2000
        if (!granted || !trusted) {
            verifiedBindingId = null
            verifiedUid = null
        }
        if (granted && trusted) everAuthorized = true
        publish(boundShizukuStatus(binding, installed, granted, everAuthorized, observedUidFor(binding)))
    }

    private fun commit(version: Long, update: () -> Unit) {
        synchronized(stateLock) {
            if (!closed.get() && epoch == version) update()
        }
    }

    private fun clearBinding() {
        lastBinding?.transport?.close()
        lastBinding = null
        verifiedBindingId = null
        verifiedUid = null
    }

    private fun observedUidFor(binding: ShizukuBinding): Int? =
        if (verifiedBindingId == binding.bindingId) verifiedUid else null

    private fun publishUnavailable(installedVersion: String?, previous: ShizukuStatus) {
        clearBinding()
        publish(unavailableShizukuStatus(installedVersion, previous))
    }

    private class PermissionResult(private val callback: (Boolean) -> Unit) {
        private val delivered = AtomicBoolean(false)

        fun complete(granted: Boolean) {
            if (delivered.compareAndSet(false, true)) callback(granted)
        }
    }

    private fun publish(next: ShizukuStatus) {
        val previous = statusFlow.value
        next.state.failureCode()?.let { onInvalidated(it) }
        statusFlow.value = next
        if (previous != next) {
            log.emit(
                ExecutionLogEvent(
                    kind = "authorization_state",
                    state = next.state.name,
                    backendUid = next.backendUid,
                    observedUid = next.observedUid,
                ),
            )
        }
    }
}
