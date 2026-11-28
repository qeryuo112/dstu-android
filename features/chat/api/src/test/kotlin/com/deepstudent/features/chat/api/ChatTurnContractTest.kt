package com.deepstudent.features.chat.api

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnContractTest {
    private val taskId = TaskId.parse("task_contract")

    @Test fun payloadSupportsOptionalModelAndCredentialReference() {
        val request = decode(
            JsonValue.obj(
                "endpoint" to JsonValue.text("https://example.test/v1"),
                "prompt" to JsonValue.text("hello"),
                "model" to JsonValue.text("model-a"),
                "credentialReference" to JsonValue.text("secret:key-a"),
            ),
        )
        assertEquals("model-a", request.model)
        assertEquals("secret:key-a", request.credentialReference)
    }

    @Test fun payloadAcceptsLegacyFieldsAndExplicitNulls() {
        val legacy = decode(
            JsonValue.obj(
                "endpoint" to JsonValue.text("https://example.test/v1"),
                "prompt" to JsonValue.text("hello"),
            ),
        )
        assertNull(legacy.model)
        assertNull(legacy.credentialReference)
        val nulls = decode(
            JsonValue.obj(
                "endpoint" to JsonValue.text("https://example.test/v1"),
                "prompt" to JsonValue.text("hello"),
                "model" to JsonValue.Null,
                "credentialReference" to JsonValue.Null,
            ),
        )
        assertNull(nulls.model)
        assertNull(nulls.credentialReference)
    }

    @Test fun malformedOptionalFieldsAreRejected() {
        assertTrue(
            runCatching {
                decode(
                    JsonValue.obj(
                        "endpoint" to JsonValue.text("https://example.test/v1"),
                        "prompt" to JsonValue.text("hello"),
                        "model" to JsonValue.parse("1"),
                    ),
                )
            }.isFailure,
        )
    }

    @Test fun requestAndUsageRedactSensitiveValues() {
        val request = ChatTurnRequest(
            taskId,
            1,
            ChatTurnContract.sessionId(taskId),
            "https://secret-endpoint.test",
            "confidential prompt",
            "model-a",
            "credential-secret",
        )
        val usage = ChatTurnUsage(ChatTurnUsageMetadata("provider-secret", "model-secret", "config-secret", "adapter-secret"), 1)
        val text = request.toString() + usage.toString()
        assertTrue("credential-secret" !in text)
        assertTrue("confidential prompt" !in text)
        assertTrue("secret-endpoint" !in text)
        assertTrue("provider-secret" !in text)
    }

    @Test fun sessionAndIdsAreStableAndMatchS301SessionDerivation() {
        assertEquals("sess_contract", ChatTurnContract.sessionId(taskId))
        assertEquals("sess_chat_test", ChatTurnContract.sessionId(TaskId.parse("task_chat_test")))
        val first = ChatTurnIds.of(taskId, 2)
        val second = ChatTurnIds.of(taskId, 2)
        assertEquals(first, second)
        assertTrue(first.userId.startsWith("msg_"))
        assertTrue(first.assistantId.startsWith("msg_"))
        assertTrue(first.blockId.startsWith("blk_"))
        assertTrue(first.userId != first.assistantId)
    }

    @Test fun usageAcceptanceAndBusinessStateMappingAreExplicit() {
        val usage = ChatTurnUsage(ChatTurnUsageMetadata("p", "m", "c", "a"), promptTokens = 3, completionTokens = 2)
        assertEquals(3L, usage.promptTokens)
        assertEquals(RuntimeState.CANCELLED, ChatTurnBusinessStates.fromFactStatus("cancelled"))
        assertEquals(RuntimeState.FAILED, ChatTurnBusinessStates.fromFactStatus("error"))
        assertNull(ChatTurnBusinessStates.fromFactStatus("running"))
    }

    private fun decode(payload: JsonValue.Object) = ChatTurnPayloadDecoder.decode(payload, taskId, 1)
}
