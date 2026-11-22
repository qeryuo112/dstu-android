package com.deepstudent.platform.network.internal

import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkLog
import com.deepstudent.platform.network.NetworkLogEvent
import com.deepstudent.platform.network.NetworkOperation
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response

internal class OkHttpTransportEngine(private val log: NetworkLog) : NetworkTransport {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val sseClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val calls = ActiveCalls()

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse =
        logged(NetworkOperation.HTTP) {
            withResponse(request, streaming = false) { response ->
                HttpResponse(response.toResponseHead(), readHttpBody(response, limits))
            }
        }

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = logged(NetworkOperation.SSE) {
        withResponse(request, streaming = true) { response ->
            onResponse(response.toResponseHead())
            if (!response.isSuccessful) return@withResponse SseEnd.REJECTED_HTTP_STATUS
            val mediaType = response.body?.contentType()
            if (mediaType?.type != "text" || mediaType.subtype != "event-stream") {
                throw NetworkException(NetworkError.SSE_INVALID_CONTENT_TYPE)
            }
            readSseBody(response, limits, onEvent)
        }
    }

    override fun close() {
        if (calls.close()) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun <T> withResponse(
        request: NetworkRequest,
        streaming: Boolean,
        block: suspend (Response) -> T,
    ): T = coroutineScope {
        val call = (if (streaming) sseClient else client).newCall(request.toOkHttpRequest())
        calls.add(call)
        try {
            ownCall(call, block)
        } finally {
            calls.remove(call)
        }
    }

    private suspend fun <T> ownCall(call: Call, block: suspend (Response) -> T): T = coroutineScope {
        val cancelOnParent = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.awaitResponse().use { response -> block(response) }
        } finally {
            cancelOnParent.cancel()
        }
    }

    private suspend fun <T> logged(operation: NetworkOperation, block: suspend () -> T): T {
        val correlationId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        record(correlationId, operation, NetworkPhase.STARTED, started)
        try {
            val result = block()
            currentCoroutineContext().ensureActive()
            val phase = when (result) {
                SseEnd.TRUNCATED -> NetworkPhase.TRUNCATED
                SseEnd.REJECTED_HTTP_STATUS -> NetworkPhase.REJECTED
                else -> NetworkPhase.COMPLETED
            }
            record(correlationId, operation, phase, started)
            return result
        } catch (failure: Exception) {
            val effective = try {
                currentCoroutineContext().ensureActive()
                failure
            } catch (cancelled: CancellationException) {
                cancelled
            }
            if (effective is CancellationException) {
                record(correlationId, operation, NetworkPhase.CANCELLED, started)
                throw effective
            }
            val error = (effective as? NetworkException)?.error ?: NetworkError.INTERNAL
            record(correlationId, operation, NetworkPhase.FAILED, started, error)
            throw NetworkException(error)
        }
    }

    private fun record(
        correlationId: String,
        operation: NetworkOperation,
        phase: NetworkPhase,
        started: Long,
        error: NetworkError? = null,
    ) {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)
        try {
            log.onEvent(NetworkLogEvent(correlationId, operation, phase, elapsed, error))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A diagnostic sink cannot make a transport operation fail.
        }
    }
}
