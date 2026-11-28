package com.deepstudent.services.providers.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContractTest {
    @Test
    fun wireProtocolsAuthAndFailureMessagesAreStable() {
        assertEquals(4, ProviderProtocol.entries.size)
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderProtocol.fromWire(" OPENAI_CHAT_COMPLETIONS "))
        assertEquals(AuthMode.OPENAI_CODEX_OAUTH, AuthMode.fromWire("openai_codex_oauth"))
        assertNull(AuthMode.fromWire("bearer"))
        assertTrue(ProviderFailureCode.entries.all { it.fixedMessage.isNotBlank() })
        assertEquals("provider is not registered", ProviderFailure(ProviderFailureCode.UNKNOWN_PROVIDER).message)
    }

    @Test
    fun credentialsAreOpaqueClosedAndNotDiagnostic() {
        val reference = CredentialReference("provider.openai.default")
        assertFalse(reference.toString().contains("provider.openai.default"))
        val credential = ProviderCredential.fromUtf8("secret-value".toCharArray())
        credential.use { assertEquals("secret-value", it.decodeToString()) }
        credential.close()
        assertFalse(credential.toString().contains("secret-value"))
    }

    @Test
    fun rawInputAllowsUnknownShapeUntilDecoderRuns() {
        val raw = ProviderRawConfig("not-json")
        assertEquals("ProviderRawConfig(redacted)", raw.toString())
        val oversized = ProviderRawConfig("x".repeat(1_000_001))
        assertEquals("ProviderRawConfig(redacted)", oversized.toString())
    }

    @Test
    fun streamResultsAndUsageRejectInvalidTerminalData() {
        val usage = ProviderUsage(inputTokens = 10, outputTokens = 4, totalTokens = 14)
        assertTrue(usage.known)
        val result = ProviderStreamResult("answer", usage, ProviderStopReason.STOP)
        assertEquals("ProviderStreamResult(text=redacted, stopReason=STOP)", result.toString())
        val failure = runCatching { ProviderUsage(inputTokens = 10, outputTokens = 4, totalTokens = 9) }.exceptionOrNull()
        assertTrue("failure=$failure", failure is IllegalArgumentException)
        assertTrue(runCatching { ProviderStreamEvent.Completed(ProviderStopReason.ERROR) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun incrementalSinkIsSuspendingAndReceivesTextUsageCompletion() = runBlocking {
        val events = mutableListOf<ProviderStreamEvent>()
        val sink = ProviderEventSink { events += it }
        sink.emit(ProviderStreamEvent.TextDelta("a"))
        sink.emit(ProviderStreamEvent.ReasoningDelta("think"))
        sink.emit(ProviderStreamEvent.ToolCallDelta(0, id = "call_1", name = "lookup", argumentsFragment = "{}"))
        sink.emit(ProviderStreamEvent.UsageReported(ProviderUsage(outputTokens = 1, cacheWriteInputTokens = 2)))
        sink.emit(ProviderStreamEvent.Completed(ProviderStopReason.STOP))
        assertEquals(5, events.size)
        assertTrue(events.last() is ProviderStreamEvent.Completed)
    }
}
