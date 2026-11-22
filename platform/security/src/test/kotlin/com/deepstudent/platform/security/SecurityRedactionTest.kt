package com.deepstudent.platform.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SecurityRedactionTest {
    @Test
    fun completeBasicAuthorizationCookieAndEscapedJsonValuesAreRedacted() {
        val inputs = listOf(
            "Authorization: Basic dXNlcjpwYXNz\nrequest failed",
            "Cookie: session=private-cookie; refresh=private-refresh\nrequest failed",
            "Set-Cookie: session=private-cookie; HttpOnly\nrequest failed",
            """{"password":"escaped\"private-json","refresh_token":"private-refresh"}""",
            "url=https://user:private-userinfo@example.test/path?access_token=private-query",
            "-----BEGIN PRIVATE KEY-----\nprivate-incomplete-key",
        )
        val forbidden = listOf("dXNlcjpwYXNz", "private-cookie", "private-refresh", "private-json",
            "private-userinfo", "private-query", "private-incomplete-key")
        for (input in inputs) {
            val safe = SecurityDiagnostics.redact(input)
            forbidden.forEach { assertFalse(safe, safe.contains(it)) }
        }
    }

    @Test
    fun oversizedDiagnosticDoesNotExposeATruncatedSecretTail() {
        val text = "password=\"" + "s".repeat(SecurityDiagnostics.MAX_TEXT_CHARS) + "\""
        assertEquals(SecurityDiagnostics.REDACTED, SecurityDiagnostics.redact(text))
    }

    @Test
    fun auditOnlyAcceptsEnumeratedErrorCodesNotProviderMessagesOrLineInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            SecurityAuditEvent(SecurityOperation.CREDENTIAL_PUT, SecurityOutcome.FAILED, errorCode = "password=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecurityAuditEvent(SecurityOperation.CREDENTIAL_PUT, SecurityOutcome.FAILED, errorCode = "IO_FAILED\nsecret")
        }
        assertEquals(DsbkError.IO_FAILED.fixedMessage, SecurityDiagnostics.forDsbk(DsbkError.IO_FAILED).message)
    }
}
