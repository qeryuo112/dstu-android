package com.deepstudent.features.browser.backend.domain

import com.deepstudent.features.browser.api.BrowserNavigationPolicy
import java.net.URI

class BrowserUrlPolicy(private val allowLoopbackHttp: Boolean = false) : BrowserNavigationPolicy {
    override fun allows(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return false
        if (url.length > 4096 || uri.userInfo != null || uri.isOpaque) return false
        uri.scheme == "https" || (allowLoopbackHttp && uri.scheme == "http" && host in LOOPBACK)
    }.getOrDefault(false)

    private companion object { val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]") }
}
