package com.deepstudent.services.providers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelListEndpointTest {
    @Test
    fun `derives the three dialect endpoints like the frozen oracle`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models" to ModelListEndpoint.Kind.GEMINI,
            ModelListEndpoint.derive("https://generativelanguage.googleapis.com", "gemini", null),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models" to ModelListEndpoint.Kind.GEMINI,
            ModelListEndpoint.derive("https://generativelanguage.googleapis.com/v1beta", "google", null),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models" to ModelListEndpoint.Kind.ANTHROPIC,
            ModelListEndpoint.derive("https://api.anthropic.com/v1/messages", "anthropic", null),
        )
        assertEquals(
            "https://api.openai.com/v1/models" to ModelListEndpoint.Kind.OPENAI,
            ModelListEndpoint.derive("https://api.openai.com/v1/chat/completions", "openai", null),
        )
        assertEquals(
            "https://gateway.example.com/custom/models" to ModelListEndpoint.Kind.OPENAI,
            ModelListEndpoint.derive("https://gateway.example.com/custom/responses", "openai", null),
        )
    }

    @Test
    fun `explicit openai protocol keeps compatible roots on gemini providers`() {
        val (url, kind) = ModelListEndpoint.derive("https://api.openai.com/v1", "gemini", "openai_responses")
        assertEquals("https://api.openai.com/v1/models", url)
        assertEquals(ModelListEndpoint.Kind.OPENAI, kind)
    }

    @Test
    fun `ollama root base url normalizes to v1`() {
        val (url, kind) = ModelListEndpoint.derive("http://127.0.0.1:11434", "ollama", null)
        assertEquals("http://127.0.0.1:11434/v1/models", url)
        assertEquals(ModelListEndpoint.Kind.OPENAI, kind)
        assertEquals("http://127.0.0.1:11434/v1", ModelListEndpoint.normalizeOllamaRootBaseUrl("http://127.0.0.1:11434"))
        assertEquals("http://127.0.0.1:11434/v1", ModelListEndpoint.normalizeOllamaRootBaseUrl("http://127.0.0.1:11434/v1"))
    }

    @Test
    fun `invalid base urls fail with fixed codes`() {
        assertThrows(ModelListException::class.java) { ModelListEndpoint.derive("not a url", "openai", null) }
        assertThrows(ModelListException::class.java) { ModelListEndpoint.derive("ftp://example.com/v1", "openai", null) }
    }

    @Test
    fun `validation keeps https and loopback rules strict`() {
        ModelListEndpoint.validate("https://api.example.com/v1/models", allowLoopback = false)
        ModelListEndpoint.validate("http://127.0.0.1:11434/v1/models", allowLoopback = true)
        assertThrows(ModelListException::class.java) {
            ModelListEndpoint.validate("http://api.example.com/v1/models", allowLoopback = false)
        }
        assertThrows(ModelListException::class.java) {
            ModelListEndpoint.validate("https://user@example.com/v1/models", allowLoopback = false)
        }
    }
}

class VendorModelHeadersTest {
    @Test
    fun `custom headers come first without hop by hop and auth wins on the same name`() {
        val headers = VendorModelHeaders.build(
            listOf(
                com.deepstudent.services.providers.api.ProviderHeader("X-Trace", "1"),
                com.deepstudent.services.providers.api.ProviderHeader("Host", "evil"),
            ),
            "s308-synthetic-list-key",
            ModelListEndpoint.Kind.OPENAI,
        )
        assertEquals(listOf("X-Trace" to "1", "Authorization" to "Bearer s308-synthetic-list-key"), headers.map { it.name to it.value })
    }

    @Test
    fun `gemini and anthropic dialects carry their own headers`() {
        assertEquals(
            listOf("x-goog-api-key" to "s308-synthetic-list-key"),
            VendorModelHeaders.build(emptyList(), "s308-synthetic-list-key", ModelListEndpoint.Kind.GEMINI).map { it.name to it.value },
        )
        assertEquals(
            listOf("anthropic-version" to "2023-06-01"),
            VendorModelHeaders.build(emptyList(), "", ModelListEndpoint.Kind.ANTHROPIC).map { it.name to it.value },
        )
        val overridden = VendorModelHeaders.build(
            listOf(com.deepstudent.services.providers.api.ProviderHeader("anthropic-version", "1999-01-01")),
            "s308-synthetic-list-key",
            ModelListEndpoint.Kind.ANTHROPIC,
        )
        assertEquals(
            listOf("anthropic-version" to "2023-06-01", "x-api-key" to "s308-synthetic-list-key"),
            overridden.map {
                it.name to
                    it.value
            },
        )
    }

    @Test
    fun `invalid api key characters fail with the fixed code`() {
        assertThrows(ModelListException::class.java) {
            VendorModelHeaders.build(emptyList(), "bad key\r\ninject", ModelListEndpoint.Kind.OPENAI)
        }
    }
}

class VendorModelPageTest {
    @Test
    fun `probe reads integers and numeric strings ignoring zero and overflow`() {
        fun item(json: String) = JsonValues.parse(json)
        assertEquals(
            128_000,
            VendorModelPage.probeU32Field(item("""{"context_length":128000}"""), VendorModelPage.PROBE_CONTEXT_WINDOW_KEYS),
        )
        assertEquals(
            64_000,
            VendorModelPage.probeU32Field(item("""{"context_length":" 64000 "}"""), VendorModelPage.PROBE_CONTEXT_WINDOW_KEYS),
        )
        assertEquals(
            4096,
            VendorModelPage.probeU32Field(
                item("""{"context_length":0,"max_context_length":4096}"""),
                VendorModelPage.PROBE_CONTEXT_WINDOW_KEYS,
            ),
        )
        assertNull(VendorModelPage.probeU32Field(item("""{"context_length":1099511627776}"""), VendorModelPage.PROBE_CONTEXT_WINDOW_KEYS))
        assertEquals(
            8192,
            VendorModelPage.probeU32Field(
                item("""{"top_provider":{"max_completion_tokens":8192}}"""),
                VendorModelPage.PROBE_MAX_OUTPUT_KEYS,
            ),
        )
    }

    @Test
    fun `gemini pages filter generateContent models and keep the token`() {
        val parsed = VendorModelPage.parsePage(
            JsonValues.parse(
                """{"models":[
                   {"name":"models/gemini-3-pro","displayName":"Gemini 3 Pro","supportedGenerationMethods":["generateContent"],"inputTokenLimit":1000000,"outputTokenLimit":65536},
                   {"name":"models/embedding-1","supportedGenerationMethods":["embedContent"]}],
                   "nextPageToken":"next"}""",
            ),
            ModelListEndpoint.Kind.GEMINI,
        )
        assertEquals(listOf("gemini-3-pro"), parsed.models.keys.toList())
        assertEquals("Gemini 3 Pro", parsed.models.getValue("gemini-3-pro").label)
        assertEquals(1_000_000, parsed.models.getValue("gemini-3-pro").contextWindow)
        assertEquals(65_536, parsed.models.getValue("gemini-3-pro").maxOutputTokens)
        assertEquals("next", parsed.cursor)
    }

    @Test
    fun `openai pages exclude audio needles and read cursors`() {
        val parsed = VendorModelPage.parsePage(
            JsonValues.parse(
                """{"data":[{"id":"gpt-5.4-mini","display_name":"GPT-5.4 Mini","context_length":400000},{"id":"gpt-tts-1"}],"has_more":true,"last_id":"gpt-5.4-mini"}""",
            ),
            ModelListEndpoint.Kind.OPENAI,
        )
        assertEquals(listOf("gpt-5.4-mini"), parsed.models.keys.toList())
        assertEquals("gpt-5.4-mini", parsed.cursor)
    }

    @Test
    fun `anthropic pages skip non model items and default cursors to the last id`() {
        val parsed = VendorModelPage.parsePage(
            JsonValues.parse(
                """{"data":[{"id":"claude-sonnet-5","type":"model","display_name":"Claude Sonnet 5","max_output_tokens":64000},{"id":"other","type":"tool"},{"id":"z-model","type":"model"}],"has_more":true}""",
            ),
            ModelListEndpoint.Kind.ANTHROPIC,
        )
        assertEquals(listOf("claude-sonnet-5", "z-model"), parsed.models.keys.toList())
        assertEquals(64_000, parsed.models.getValue("claude-sonnet-5").maxOutputTokens)
        assertEquals("z-model", parsed.cursor)
    }

    @Test
    fun `missing containers fail with fixed codes`() {
        assertThrows(ModelListException::class.java) { VendorModelPage.parsePage(JsonValues.parse("{}"), ModelListEndpoint.Kind.GEMINI) }
        assertThrows(ModelListException::class.java) { VendorModelPage.parsePage(JsonValues.parse("{}"), ModelListEndpoint.Kind.OPENAI) }
    }

    @Test
    fun `pagination queries follow the dialect`() {
        assertEquals(
            "https://a.example/v1/models",
            VendorModelPage.withQuery("https://a.example/v1/models", ModelListEndpoint.Kind.OPENAI, null),
        )
        assertEquals(
            "https://a.example/v1/models?after=b",
            VendorModelPage.withQuery("https://a.example/v1/models", ModelListEndpoint.Kind.OPENAI, "b"),
        )
        assertEquals(
            "https://a.example/v1beta/models?pageSize=100&pageToken=n",
            VendorModelPage.withQuery("https://a.example/v1beta/models", ModelListEndpoint.Kind.GEMINI, "n"),
        )
        assertEquals(
            "https://a.example/v1/models?limit=1000&after_id=n",
            VendorModelPage.withQuery("https://a.example/v1/models", ModelListEndpoint.Kind.ANTHROPIC, "n"),
        )
    }
}

private object JsonValues {
    fun parse(json: String): com.deepstudent.core.common.json.JsonValue.Object =
        com.deepstudent.core.common.json.JsonValue.parseObject(json)
}
