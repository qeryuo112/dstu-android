package com.deepstudent.features.cards.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateContractTest {
    private val fixture = RepoFixtures.json("fixtures/templates-assets.json")
    private val template = fixture["template"] as JsonValue.Object
    private val expectedWire = template["expectedWire"] as JsonValue.Object
    private val expectedRow = template["expectedRow"] as JsonValue.Object

    @Test
    fun wireInsertAndUpdateRoundTripExactly() {
        for (name in listOf("insert", "update")) {
            val wire = JsonFields.string(expectedWire, name)
            val decoded = TemplateCodec.decodeChangeRecord(wire)
            assertEquals(wire, TemplateCodec.encodeChangeRecord(decoded))
        }
    }

    @Test
    fun rowFieldsUseExactSnakeCaseAndOrder() {
        val decoded = TemplateCodec.decodeChangeRecord(JsonFields.string(expectedWire, "update"))
        assertEquals(TemplateContract.ROW_FIELDS, decoded.data.let { row ->
            TemplateCodec.encodeRowObject(row).keys()
        })
        assertEquals("Updated public template fixture.", decoded.data.description)
        assertEquals(0L, decoded.data.localVersion)
        assertEquals(null, decoded.data.deviceId)
        assertEquals(null, decoded.data.deletedAt)
    }

    @Test
    fun fieldRulesAddsExplicitNullsAndKeepsCreateOrder() {
        val create = template["createRequest"] as JsonValue.Object
        val rulesValue = create["field_extraction_rules"] as JsonValue.Object
        val rules = LinkedHashMap<String, TemplateFieldRule>()
        for (name in rulesValue.keys()) {
            rules[name] = TemplateFieldRule.decode(rulesValue[name] as JsonValue.Object)
        }
        assertEquals(
            JsonFields.string(expectedRow, "field_extraction_rules_json"),
            TemplateFieldRules.encode(rules),
        )
        assertEquals(
            JsonFields.string(expectedRow, "fields_json"),
            TemplateFieldRules.encodeFields(listOf("Front", "Back", "Tags")),
        )
    }

    @Test
    fun capturePredicateMatchesFrozenSqlTriggers() {
        val base = TemplateCodec.decodeRow(expectedRow)
        assertTrue(base.copy(userState = TemplateUserState(0, 0, 0)).captureEligible)
        assertFalse(base.copy(userState = TemplateUserState(1, 0, 0)).captureEligible)
        assertTrue(base.copy(userState = TemplateUserState(1, 1, 0)).captureEligible)
        assertTrue(base.copy(userState = TemplateUserState(1, 0, 1)).captureEligible)
        assertFalse(base.copy(userState = TemplateUserState(2, 0, 0)).captureEligible)
        assertFalse(base.executable)
    }

    @Test
    fun templateScriptAndUnknownFieldsRemainOpaqueData() {
        val wire = JsonFields.string(expectedWire, "insert")
        val parsed = JsonValue.parseObject(wire, "wire")
        val data = parsed["data"] as JsonValue.Object
        val builder = JsonValue.Object.Builder()
        builder.putAll(data)
        builder.put(
            "future_template_metadata",
            JsonValue.obj(
                "source" to JsonValue.text("future-desktop"),
                "trusted" to JsonValue.bool(false),
            ),
        )
        val rootBuilder = JsonValue.Object.Builder()
        for (key in parsed.keys()) {
            rootBuilder.put(key, if (key == "data") builder.build() else parsed[key] ?: JsonValue.Null)
        }
        val decoded = TemplateCodec.decodeChangeRecord(rootBuilder.build())
        assertEquals(
            """{"source":"future-desktop","trusted":false}""",
            decoded.data.unknownFields["future_template_metadata"]?.canonical(),
        )
        assertTrue(decoded.data.frontTemplate.contains("<script>"))
        val roundTrip = TemplateCodec.decodeChangeRecord(TemplateCodec.encodeChangeRecord(decoded))
        assertEquals(
            """{"source":"future-desktop","trusted":false}""",
            roundTrip.data.unknownFields["future_template_metadata"]?.canonical(),
        )
        assertFalse(roundTrip.data.executable)
    }

    @Test
    fun missingNullableColumnIsRejectedInsteadOfSilentlyNormalized() {
        val data = JsonValue.parseObject(JsonFields.string(expectedWire, "insert"), "wire")["data"] as JsonValue.Object
        val builder = JsonValue.Object.Builder()
        for (key in data.keys()) if (key != "description") builder.put(key, data[key] ?: JsonValue.Null)
        val thrown = assertThrows(DstuException::class.java) { TemplateCodec.decodeRow(builder.build()) }
        assertEquals("INVALID_TEMPLATE_ROW", thrown.error.code)
    }

    @Test
    fun operationAndTableAreFixed() {
        val decoded = TemplateCodec.decodeChangeRecord(JsonFields.string(expectedWire, "insert"))
        assertEquals(TemplateContract.TABLE, decoded.tableName)
        assertEquals(TemplateContract.DATABASE, decoded.databaseName)
        assertEquals(TemplateOperation.INSERT, decoded.operation)
        assertNotNull(decoded.data.id)
    }
}
