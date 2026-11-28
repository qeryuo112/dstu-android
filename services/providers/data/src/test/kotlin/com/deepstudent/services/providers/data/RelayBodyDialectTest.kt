package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RelayBodyDialectTest {
    @Test
    fun `openrouter folds planned effort into the reasoning object and drops legacy keys`() {
        val body = JsonValue.parseObject(
            """{"model":"gpt-6-fixture","reasoning_effort":"xhigh","reasoning":{"max_tokens":512,"enabled":true}}""",
        )
        val resolved = s308Resolved("openrouter", model = "gpt-6-fixture", reasoning = s308Reasoning("max"))
        val transformed = RelayBodyDialect.transform(body, resolved)
        val reasoning = transformed.s308Obj("reasoning")!!
        assertEquals("max", reasoning.s308Text("effort"))
        assertEquals(null, reasoning.s308Obj("max_tokens"))
        assertEquals(null, reasoning.s308Text("enabled"))
        assertEquals(null, transformed.s308Text("reasoning_effort"))
    }

    @Test
    fun `openrouter keeps non-max efforts and preserves extra reasoning order`() {
        val body = JsonValue.parseObject(
            """{"model":"m","reasoning_effort":"high","reasoning":{"enabled":true,"max_tokens":8}}""",
        )
        val resolved = s308Resolved("openrouter", reasoning = s308Reasoning("high"))
        val reasoning = RelayBodyDialect.transform(body, resolved).s308Obj("reasoning")!!
        assertEquals("high", reasoning.s308Text("effort"))
        assertEquals(null, reasoning.s308Text("enabled"))
        assertEquals(null, reasoning.s308Text("max_tokens"))
    }

    @Test
    fun `relay hosts upgrade a requested max effort in place`() {
        val body = JsonValue.parseObject("""{"model":"m","reasoning_effort":"xhigh"}""")
        val relay = RelayBodyDialect.transform(body, s308Resolved("custom", reasoning = s308Reasoning("max")))
        assertEquals("max", relay.s308Text("reasoning_effort"))
        val general = RelayBodyDialect.transform(body, s308Resolved("general", reasoning = s308Reasoning("max")))
        assertEquals("max", general.s308Text("reasoning_effort"))
    }

    @Test
    fun `plain xhigh stays xhigh without a requested max`() {
        val body = JsonValue.parseObject("""{"model":"m","reasoning_effort":"xhigh"}""")
        val same = RelayBodyDialect.transform(body, s308Resolved("custom", reasoning = s308Reasoning("xhigh")))
        assertSame(body, same)
        assertEquals("xhigh", same.s308Text("reasoning_effort"))
    }

    @Test
    fun `non relay members and non gpt families never upgrade max`() {
        val body = JsonValue.parseObject("""{"model":"m","reasoning_effort":"xhigh"}""")
        val ollama = RelayBodyDialect.transform(body, s308Resolved("ollama", reasoning = s308Reasoning("max")))
        assertEquals("xhigh", ollama.s308Text("reasoning_effort"))
        val plainOpenrouter = RelayBodyDialect.transform(
            body,
            s308Resolved("openrouter", model = "fixture-router", reasoning = s308Reasoning("max")),
        )
        val reasoning = checkNotNull(plainOpenrouter.s308Obj("reasoning"))
        assertEquals("xhigh", reasoning.s308Text("effort"))
    }

    @Test
    fun `bodies without planned effort pass through untouched`() {
        val body = JsonValue.parseObject("""{"model":"m","reasoning":{"max_tokens":8}}""")
        val openrouter = RelayBodyDialect.transform(body, s308Resolved("openrouter", reasoning = s308Reasoning()))
        assertSame(body, openrouter)
        val custom = RelayBodyDialect.transform(body, s308Resolved("custom", reasoning = s308Reasoning()))
        assertSame(body, custom)
    }

    @Test
    fun `max capable gpt family upgrades openrouter fold even on non relay`() {
        val body = JsonValue.parseObject("""{"model":"m","reasoning_effort":"xhigh"}""")
        val upgraded = RelayBodyDialect.transform(
            body,
            s308Resolved("openrouter", model = "gpt-6-fixture", reasoning = s308Reasoning("max")),
        )
        assertEquals("max", checkNotNull(upgraded.s308Obj("reasoning")).s308Text("effort"))
    }

    @Test
    fun `max capable family prefixes follow the frozen rule`() {
        assertEquals(true, RelayBodyDialect.isMaxCapableGptFamily("gpt-6-fixture"))
        assertEquals(true, RelayBodyDialect.isMaxCapableGptFamily("openrouter/gpt-5.6"))
        assertEquals(false, RelayBodyDialect.isMaxCapableGptFamily("gpt-6x"))
        assertEquals(false, RelayBodyDialect.isMaxCapableGptFamily("fixture-model"))
    }

    @Test
    fun `openrouter detection uses provider, scope or host`() {
        assertEquals(true, RelayBodyDialect.isOpenrouter("openrouter", "openrouter", "https://x.example/v1"))
        assertEquals(true, RelayBodyDialect.isOpenrouter("custom", "openrouter", "https://x.example/v1"))
        assertEquals(true, RelayBodyDialect.isOpenrouter("custom", "custom", "https://api.openrouter.ai/api/v1"))
        assertEquals(true, RelayBodyDialect.isOpenrouter("custom", "custom", "https://openrouter.ai/api/v1"))
        assertEquals(false, RelayBodyDialect.isOpenrouter("custom", "custom", "https://relay.example/v1"))
    }
}
