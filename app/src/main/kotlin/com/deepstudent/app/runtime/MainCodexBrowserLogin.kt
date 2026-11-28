package com.deepstudent.app.runtime

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.data.codex.CodexAuthSettingsPort
import com.deepstudent.services.providers.data.codex.CodexBrowserLoginResult
import com.deepstudent.services.providers.data.codex.CodexBrowserLoginStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Activates the fixed-port callback receiver for browser login, independent of Activity lifetime. */
internal class MainCodexBrowserLogin(private val auth: CodexAuthSettingsPort) : AutoCloseable {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var current: Attempt? = null

    suspend fun begin(): ProviderResult<CodexBrowserLoginStart> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!owner.isActive || current?.job?.isActive == true) {
                return@withContext ProviderResult.Failure(ProviderFailure(ProviderFailureCode.INVALID_CONFIGURATION))
            }
            val listener = CodexLoopbackListener.open()
                ?: return@withContext ProviderResult.Failure(ProviderFailure(ProviderFailureCode.NETWORK))
            startListener(listener)
        }
    }

    private suspend fun startListener(listener: CodexLoopbackListener): ProviderResult<CodexBrowserLoginStart> {
        var launched = false
        try {
            val redirect = "http://localhost:${listener.port}${CodexLoopbackListener.CALLBACK_PATH}"
            val result = auth.startBrowserLogin(redirect)
            if (result is ProviderResult.Success) {
                val id = result.value.attemptId
                val job = owner.launch { listen(listener, id) }
                job.invokeOnCompletion { listener.close() }
                current = Attempt(id, job)
                launched = true
            }
            return result
        } finally {
            if (!launched) listener.close()
        }
    }

    suspend fun cancel(attemptId: String) = lock.withLock {
        val matching = current?.takeIf { it.id == attemptId }
        try {
            auth.cancelLogin(attemptId)
        } finally {
            matching?.job?.cancelAndJoin()
        }
    }

    private suspend fun listen(listener: CodexLoopbackListener, id: String) {
        try {
            withTimeoutOrNull(BROWSER_TIMEOUT_MILLIS) {
                listener.serve { callback -> complete(id, callback) }
            }
        } catch (_: IOException) {
            runCatching {
                AndroidOperationLogger.warn(
                    "providers.codex.browser_callback_closed",
                    fields = JsonValue.Object.Builder().put("owner", "app/providers").put("reason", "LOOPBACK_IO").build(),
                )
            }
        } finally {
            withContext(NonCancellable) { auth.cancelLogin(id) }
        }
    }

    private suspend fun complete(id: String, callback: String): CodexCallbackDisposition {
        val result = auth.finishBrowserLogin(id, callback)
        if (result is CodexBrowserLoginResult.Completed) return CodexCallbackDisposition.COMPLETE
        return if (auth.status().activeAttemptId == id) CodexCallbackDisposition.INVALID else CodexCallbackDisposition.TERMINAL_FAILURE
    }

    override fun close() = owner.cancel()

    private data class Attempt(val id: String, val job: Job)

    private companion object {
        const val BROWSER_TIMEOUT_MILLIS = 300_000L
    }
}
