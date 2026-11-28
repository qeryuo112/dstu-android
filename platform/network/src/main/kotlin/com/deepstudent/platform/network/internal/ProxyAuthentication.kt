package com.deepstudent.platform.network.internal

import okhttp3.Authenticator
import okhttp3.Interceptor
import java.io.IOException

internal class ProxyAuthenticationRequired : IOException("network proxy requires authentication")

/** The network layer never retries authentication or reads a proxy's challenge body. */
internal object ProxyAuthentication {
    val authenticator = Authenticator { _, response ->
        // OkHttp calls this once with a synthetic challenge before sending CONNECT.
        if (response.header("Proxy-Authenticate") == "OkHttp-Preemptive") null else throw ProxyAuthenticationRequired()
    }

    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 407) {
            response.close()
            throw ProxyAuthenticationRequired()
        }
        response
    }
}
