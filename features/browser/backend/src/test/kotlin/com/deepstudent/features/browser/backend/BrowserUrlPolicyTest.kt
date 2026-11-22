package com.deepstudent.features.browser.backend

import com.deepstudent.features.browser.backend.domain.BrowserUrlPolicy
import org.junit.Assert.*
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test fun allowsHttpsAndExplicitLoopbackOnly() {
        val production = BrowserUrlPolicy()
        val testing = BrowserUrlPolicy(allowLoopbackHttp = true)
        assertTrue(production.allows("https://example.org/login"))
        assertFalse(production.allows("http://127.0.0.1:8080/login"))
        assertTrue(testing.allows("http://127.0.0.1:8080/login"))
        listOf("http://example.org", "file:///data/user/0", "javascript:alert(1)", "https://x@y.com")
            .forEach { assertFalse(it, testing.allows(it)) }
    }
}
