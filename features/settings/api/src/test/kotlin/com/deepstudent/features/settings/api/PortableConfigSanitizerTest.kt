package com.deepstudent.features.settings.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableConfigSanitizerTest {
    private val fixture = RepoFixtures.json("fixtures/portable-config.json")

    @Test
    fun portableRowsNormalizeWithoutChangingWireValues() {
        val rows = (fixture["portableRows"] as JsonValue.Array).values.map { it as JsonValue.Object }
        for (row in rows) {
            val key = JsonFields.string(row, "key")
            val value = JsonFields.string(row, "value")
            assertEquals(
                PortableConfigSanitizer.normalizePortableValue(key, value),
                PortableConfigSanitizer.normalizePortableValue(key, value),
            )
        }
        assertEquals("dark", PortableConfigSanitizer.normalizePortableValue("theme", "dark"))
        assertEquals("#a1b2c3", PortableConfigSanitizer.normalizePortableValue("theme_custom_color", "#A1B2C3"))
        assertThrows(DstuException::class.java) {
            PortableConfigSanitizer.normalizePortableValue("theme", "neon")
        }
    }

    @Test
    fun nestedCredentialsAndExecutableFieldsAreRejected() {
        assertFailure("portable.model.preferences.v1", modelWith("apiKey", "\"key\""), "NESTED_SECRET")
        assertFailure("portable.model.preferences.v1", modelWith("headers", "{}"), "NESTED_SECRET")
        assertFailure("portable.tool.preferences.v1", toolWith("env", "{}"), "NESTED_SECRET")
        assertFailure("portable.tool.preferences.v1", toolWith("nested", "{\"command\":\"sh\"}"), "EXECUTABLE_FIELD")
        assertFailure("portable.model.preferences.v1", modelWith("nested", "{\"filePath\":\"/bin/sh\"}"), "EXECUTABLE_FIELD")
        assertFailure("pdf.settings", "{\"password\":\"x\"}", "NESTED_SECRET")
        assertFailure("pdf.settings", "{\"command\":\"sh\"}", "EXECUTABLE_FIELD")
    }

    @Test
    fun toolLaunchFieldsAreProjectedOut() {
        val input = """
            {"id":"public-tool","name":"Public tool","description":"read only",
             "transportType":"stdio","command":"sh","args":["-c","true"],
             "enabled":true,"unknown":{"future":1}}
        """.trimIndent()
        val sanitized = PortableConfigSanitizer.sanitizePortableConfig(
            SanitizedPortableConfig.KIND_TOOL,
            JsonValue.parse(input),
        )
        val encoded = sanitized.portableObject()
        val tool = (encoded["tools"] as JsonValue.Array).values[0] as JsonValue.Object
        assertEquals("public-tool", JsonFields.string(tool, "id"))
        assertFalse(tool.containsKey("command"))
        assertFalse(tool.containsKey("args"))
    }

    @Test
    fun validModelVectorProjectionPreservesAllowedNumbersAndBooleans() {
        val input = JsonValue.obj(
            "id" to JsonValue.text("public-model"),
            "name" to JsonValue.text("Public model"),
            "model" to JsonValue.text("model-id"),
            "baseUrl" to JsonValue.text("https://api.example.invalid/v1"),
            "isMultimodal" to JsonValue.bool(true),
            "maxOutputTokens" to JsonValue.integer(4096),
            "temperature" to JsonValue.Number.raw("0.7"),
            "apiKey" to JsonValue.text("secret"),
        )
        val failure = assertThrows(DstuException::class.java) {
            PortableConfigSanitizer.sanitizePortableConfig(SanitizedPortableConfig.KIND_MODEL, input)
        }
        assertEquals("NESTED_SECRET", failure.error.code)

        val safe = JsonValue.obj(
            "id" to JsonValue.text("public-model"),
            "name" to JsonValue.text("Public model"),
            "model" to JsonValue.text("model-id"),
            "baseUrl" to JsonValue.text("https://api.example.invalid/v1"),
            "isMultimodal" to JsonValue.bool(true),
            "maxOutputTokens" to JsonValue.integer(4096),
            "temperature" to JsonValue.Number.raw("0.7"),
        )
        val encoded = PortableConfigSanitizer.sanitizePortableConfig(
            SanitizedPortableConfig.KIND_MODEL,
            safe,
        ).portableObject()
        val model = encoded["models"] as JsonValue.Array
        val item = model.values[0] as JsonValue.Object
        assertEquals("0.7", item["temperature"].toString())
        assertEquals(true, (item["isMultimodal"] as JsonValue.Bool).value)
        assertTrue(item.containsKey("maxOutputTokens"))
    }

    @Test
    fun nonFiniteJsonNumbersAreRejectedWhileLexicalValueIsPreservedForFiniteOnes() {
        assertFailure(
            "portable.model.preferences.v1",
            modelWith("temperature", "1e9999"),
            "INVALID_VALUE",
        )
        assertFailure(
            "portable.model.preferences.v1",
            modelWith("contextWindow", "-1e9999"),
            "INVALID_VALUE",
        )
        val finite = PortableConfigSanitizer.sanitizePortableConfig(
            SanitizedPortableConfig.KIND_MODEL,
            JsonValue.parse(modelWith("temperature", "0.700")),
        ).portableObject()
        val item = (finite["models"] as JsonValue.Array).values[0] as JsonValue.Object
        assertEquals("0.700", item["temperature"].toString())
    }

    @Test
    fun httpUrlsRequireARealHostWithoutCredentialsQueryOrFragment() {
        for (url in listOf(
            "http:relative",
            "https:///path",
            "https://",
            "ftp://host/path",
            "https://user@host/path",
            "https://host/path?query=1",
            "https://host/path#fragment",
        )) {
            assertFailure("portable.model.preferences.v1", modelWith("baseUrl", "\"$url\""), "INVALID_VALUE")
        }
        PortableConfigSanitizer.sanitizePortableConfig(
            SanitizedPortableConfig.KIND_MODEL,
            JsonValue.parse(modelWith("baseUrl", "\"https://api.example.invalid/v1\"")),
        )
    }

    @Test
    fun genericJsonStringsAreBoundedAndValidated() {
        assertEquals("plain-value", PortableConfigSanitizer.normalizePortableValue("pdf.settings", "plain-value"))
        assertEquals("[]", PortableConfigSanitizer.normalizePortableValue("pdf.settings", "[]"))
        assertThrows(DstuException::class.java) {
            PortableConfigSanitizer.normalizePortableValue("pdf.settings", "{broken")
        }
    }

    private fun assertFailure(key: String, value: String, code: String) {
        val failure = assertThrows(DstuException::class.java) {
            PortableConfigSanitizer.normalizePortableValue(key, value)
        }
        assertEquals(code, failure.error.code)
    }

    private fun modelWith(field: String, value: String): String =
        """{"id":"m","model":"x","name":"n","baseUrl":"https://api.example.invalid/v1","$field":$value}"""

    private fun toolWith(field: String, value: String): String =
        """{"id":"t","name":"t","$field":$value}"""
}
