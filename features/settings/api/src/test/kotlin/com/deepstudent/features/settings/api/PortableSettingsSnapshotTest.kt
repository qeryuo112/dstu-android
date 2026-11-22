package com.deepstudent.features.settings.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsSnapshotTest {
    private val fixture = RepoFixtures.json("fixtures/portable-config.json")

    @Test
    fun captureReadsOnlyPortableKeysAndNormalizesValues() {
        val settings = mapOf(
            "theme_palette" to "custom",
            "theme_custom_color" to "#A1B2C3",
        )
        val reader = SettingReader { settings[it] }
        val snapshot = PortableSettingsSnapshots.capture(reader)
        assertEquals(listOf("theme_custom_color", "theme_palette"), snapshot.rows.map { it.key }.sorted())
        assertEquals("#a1b2c3", snapshot.rows.first { it.key == "theme_custom_color" }.value)
    }

    @Test
    fun replayValidatesWholeSnapshotBeforeWriting() {
        val writes = ArrayList<Pair<String, String>>()
        val writer = SettingWriter { key, value -> writes += key to value }
        val invalid = PortableSettingsSnapshot(
            version = 1,
            rows = listOf(
                PortableSettingRow("theme", "dark"),
                PortableSettingRow("unknown.future.setting", "x"),
            ),
        )
        assertThrows(DstuException::class.java) {
            PortableSettingsSnapshots.replay(writer, invalid)
        }
        assertTrue(writes.isEmpty())

        val valid = PortableSettingsSnapshot(1, listOf(PortableSettingRow("theme", "dark")))
        PortableSettingsSnapshots.replay(writer, valid)
        assertEquals(listOf("theme" to "dark"), writes)
    }

    @Test
    fun snapshotEnvelopeRoundTripsWithVersionOneAndRows() {
        val snapshot = PortableSettingsSnapshot(1, listOf(PortableSettingRow("theme", "dark")))
        val encoded = PortableSettingsSnapshots.encode(snapshot)
        assertEquals(snapshot, PortableSettingsSnapshots.decode(JsonValue.parseObject(encoded, "snapshot")))
    }

    @Test
    fun remoteRowsUseExactKeysAndRejectTrustLocalAndSecretKeys() {
        val published = (fixture["directions"] as JsonValue.Object)["Upload"] as JsonValue.Object
        val rows = (published["published"] as JsonValue.Array).values.map { item ->
            val row = item as JsonValue.Object
            RemotePreferenceRow(
                key = JsonFields.string(row, "key"),
                value = JsonFields.string(row, "value"),
                updatedAt = JsonFields.string(row, "updatedAt"),
            )
        }
        val encoded = RemotePreferenceRows.encode(rows)
        assertEquals(rows, RemotePreferenceRows.decode(encoded))

        for (key in listOf(
            "skill.ui.trust_overrides",
            "chat.defaultPermissionPreset",
            "ds.ui.themeVariant",
            "plugin.schedule.demo.job",
            "secret.plugin.demo.note",
            "tool_approval.global_bypass",
        )) {
            val failure = assertThrows(DstuException::class.java) {
                RemotePreferenceRows.decode(
                    """[{"key":"$key","value":"x","updatedAt":"2026-11-16T00:00:00.000Z"}]""",
                )
            }
            assertEquals("NON_PORTABLE_REMOTE", failure.error.code)
        }
    }

    @Test
    fun remoteRowsRejectExtraKeysAndNonStrictTimestamps() {
        val failure = assertThrows(DstuException::class.java) {
            RemotePreferenceRows.decode(
                """[{"key":"theme","value":"dark","updatedAt":"2026-11-16T00:00:00Z","extra":1}]""",
            )
        }
        assertEquals("INVALID_REMOTE", failure.error.code)
        assertEquals(
            "INVALID_TIMESTAMP",
            assertThrows(DstuException::class.java) {
                RemotePreferenceRows.decode(
                    """[{"key":"theme","value":"dark","updatedAt":"2026-02-30T00:00:00Z"}]""",
                )
            }.error.code,
        )
        assertEquals(
            "INVALID_TIMESTAMP",
            assertThrows(DstuException::class.java) {
                StrictTimestamp.parseMillis("2026-11-16T00:00:00+24:00")
            }.error.code,
        )
    }

    @Test
    fun appearanceCaptureUsesDesktopProducerKeysAndReplayIsPrevalidated() {
        val appearance = fixture["appearance"] as JsonValue.Object
        val fields = appearance["fields"] as JsonValue.Object
        val mode = fields["mode"] as JsonValue.Object
        val palette = fields["palette"] as JsonValue.Object
        val custom = fields["customColor"] as JsonValue.Object
        val settings = mapOf(
            JsonFields.string(palette, "key") to JsonFields.string(palette, "value"),
            JsonFields.string(custom, "key") to JsonFields.string(custom, "value"),
        )
        val local = mapOf(JsonFields.string(mode, "key") to JsonFields.string(mode, "value"))
        val snapshot = AppearanceSettings.capture(
            SettingReader { settings[it] },
            LocalSettingReader { local[it] },
        )
        assertEquals("localStorage", snapshot.mode?.source)
        assertEquals("dstu-theme-mode", snapshot.mode?.key)
        assertEquals("settings", snapshot.palette?.source)
        assertEquals("theme_palette", snapshot.palette?.key)
        assertEquals("settings", snapshot.customColor?.source)
        assertEquals("#a1b2c3", snapshot.customColor?.value)

        val settingWrites = ArrayList<Pair<String, String>>()
        val localWrites = ArrayList<Pair<String, String>>()
        AppearanceSettings.replay(
            SettingWriter { key, value -> settingWrites += key to value },
            LocalSettingWriter { key, value -> localWrites += key to value },
            snapshot,
        )
        assertTrue(settingWrites.any { it == "theme_palette" to "custom" })
        assertTrue(localWrites.any { it == "dstu-theme-mode" to "dark" })

        val invalid = snapshot.copy(customColor = AppearanceValue("settings", "theme_custom_color", "#zzzzzz"))
        assertThrows(DstuException::class.java) {
            AppearanceSettings.replay(
                SettingWriter { key, value -> settingWrites += key to value },
                LocalSettingWriter { key, value -> localWrites += key to value },
                invalid,
            )
        }
    }
}
