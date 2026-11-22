package com.deepstudent.platform.network.internal

import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.SseDecoder
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(NetworkException(NetworkError.CONNECTION))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, unclaimed, _ -> unclaimed.close() }
        }
    })
}

internal suspend fun readHttpBody(response: Response, limits: HttpLimits): ByteArray {
    val body = response.body ?: return ByteArray(0)
    if (body.contentLength() > limits.maxResponseBytes) throw NetworkException(NetworkError.RESPONSE_TOO_LARGE)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val input = body.byteStream()
    while (true) {
        val count = input.readCancellable(buffer)
        if (count == -1) return output.toByteArray()
        if (count > limits.maxResponseBytes - output.size()) throw NetworkException(NetworkError.RESPONSE_TOO_LARGE)
        output.write(buffer, 0, count)
    }
}

internal suspend fun readSseBody(
    response: Response,
    limits: SseLimits,
    onEvent: suspend (SseEvent) -> Unit,
): SseEnd {
    val decoder = SseDecoder(limits)
    val input = response.body?.byteStream() ?: return decoder.finish()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.readCancellable(buffer)
        if (count == -1) return decoder.finish()
        for (event in decoder.accept(buffer, length = count)) {
            currentCoroutineContext().ensureActive()
            onEvent(event)
        }
    }
}

private suspend fun InputStream.readCancellable(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
    try {
        val count = read(buffer)
        currentCoroutineContext().ensureActive()
        count
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        throw NetworkException(NetworkError.CONNECTION)
    }
}
