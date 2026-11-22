package com.deepstudent.features.settings.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingClassificationTest {
    private val fixture = RepoFixtures.json("fixtures/portable-config.json")

    @Test
    fun authorizationBoundariesComeFromFrozenFixture() {
        for (item in (fixture["boundaries"] as JsonValue.Array).values) {
            val boundary = item as JsonValue.Object
            val probe = JsonFields.string(boundary, "probe")
            val scope = JsonFields.string(boundary, "scope")
            assertEquals(probe, scope, SettingClassification.classify(probe).scope.wire)
        }
        val unknown = fixture["unknown"] as JsonValue.Object
        assertEquals(SettingScope.LOCAL, SettingClassification.classify(JsonFields.string(unknown, "key")).scope)
        assertEquals(SettingScope.PORTABLE, SettingClassification.classify("dstu-theme-mode").scope)
        assertEquals(SettingScope.SECRET, SettingClassification.classify("api_configs").scope)
        assertEquals(SettingScope.SECRET, SettingClassification.classify("secret.plugin.demo.note").scope)
        assertEquals(SettingScope.LOCAL, SettingClassification.classify("plugin.schedule.demo.job").scope)
    }

    @Test
    fun all573ContractRowsClassifyToTheirRecordedScope() {
        val contract = RepoFixtures.json("config/contracts/portable-config.json")
        val classification = contract["classification"] as JsonValue.Object
        val rows = (classification["rows"] as JsonValue.Array).values
        assertEquals(573, rows.size)
        var portable = 0
        var local = 0
        var secret = 0
        for (item in rows) {
            val row = item as JsonValue.Object
            val expected = JsonFields.string(row, "scope")
            val actual = SettingClassification.classify(JsonFields.string(row, "key")).scope.wire
            assertEquals("key=${JsonFields.string(row, "key")}", expected, actual)
            when (expected) {
                "portable" -> portable++
                "local" -> local++
                "secret" -> secret++
            }
        }
        assertEquals(20, portable)
        assertEquals(503, local)
        assertEquals(50, secret)
    }

    @Test
    fun unknownKeysDefaultLocalAndInvalidIdsFail() {
        assertEquals(SettingScope.LOCAL, SettingClassification.classify("future.unknown.setting").scope)
        assertThrows(com.deepstudent.core.common.error.DstuException::class.java) {
            SettingClassification.classify("")
        }
        assertThrows(com.deepstudent.core.common.error.DstuException::class.java) {
            SettingClassification.classify("x".repeat(513))
        }
        assertTrue(SettingClassification.PORTABLE_SYNCABLE_KEYS.size == 13)
    }
}
