package com.deepstudent.platform.network.internal

import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.ResponseHead
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal fun NetworkRequest.toOkHttpRequest(): Request {
    try {
        val parsed = url.toHttpUrlOrNull() ?: throw NetworkException(NetworkError.INVALID_REQUEST)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw NetworkException(NetworkError.INVALID_REQUEST)
        }
        val builder = Request.Builder().url(parsed)
        headers.forEach { builder.addHeader(it.name, it.value) }
        return builder.method(method, body?.toRequestBody()).build()
    } catch (_: IllegalArgumentException) {
        throw NetworkException(NetworkError.INVALID_REQUEST)
    }
}

internal fun Response.toResponseHead(): ResponseHead {
    val values = headers.map { (name, value) -> NetworkHeader(name, value) }
    return ResponseHead(code, values)
}
