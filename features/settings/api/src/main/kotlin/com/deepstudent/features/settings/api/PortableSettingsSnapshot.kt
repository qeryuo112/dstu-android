package com.deepstudent.features.settings.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

fun interface SettingReader {
    fun get(key: String): String?
}

fun interface SettingWriter {
    fun save(key: String, value: String)
}

fun interface LocalSettingReader {
    fun get(key: String): String?
}

fun interface LocalSettingWriter {
    fun set(key: String, value: String)
}

data class PortableSettingRow(
    val key: String,
    val value: String,
    val scope: SettingScope = SettingScope.PORTABLE,
) {
    init {
        if (scope != SettingScope.PORTABLE) {
            settingsError("NOT_PORTABLE", "$key is not portable")
        }
    }
}

data class PortableSettingsSnapshot(
    val version: Int,
    val rows: List<PortableSettingRow>,
) {
    init {
        if (version != 1) settingsError("INVALID_SNAPSHOT", "portable snapshot must be version 1")
    }
}

object PortableSettingsSnapshots {
    fun capture(settings: SettingReader): PortableSettingsSnapshot {
        val rows = ArrayList<PortableSettingRow>()
        for (key in SettingClassification.PORTABLE_SYNCABLE_KEYS) {
            val value = settings.get(key) ?: continue
            rows += PortableSettingRow(
                key = key,
                value = PortableConfigSanitizer.normalizePortableValue(key, value),
            )
        }
        return PortableSettingsSnapshot(1, rows)
    }

    fun replay(settings: SettingWriter, snapshot: PortableSettingsSnapshot): List<PortableSettingRow> {
        val seen = HashSet<String>()
        val validated = snapshot.rows.map { row ->
            if (!seen.add(row.key)) settingsError("INVALID_SNAPSHOT", "duplicate snapshot key ${row.key}")
            PortableSettingRow(
                key = row.key,
                value = PortableConfigSanitizer.normalizePortableValue(row.key, row.value),
            )
        }
        validated.forEach { settings.save(it.key, it.value) }
        return validated
    }

    fun decode(value: JsonValue.Object): PortableSettingsSnapshot {
        if (JsonFields.integer(value, "version") != 1L) {
            settingsError("INVALID_SNAPSHOT", "portable snapshot must be version 1")
        }
        val rowsValue = value["rows"] as? JsonValue.Array
            ?: settingsError("INVALID_SNAPSHOT", "portable snapshot must be version 1 with rows")
        val rows = rowsValue.values.mapIndexed { index, item ->
            val row = item as? JsonValue.Object
                ?: settingsError("INVALID_SNAPSHOT", "snapshot row $index must be an object")
            PortableSettingRow(
                key = JsonFields.string(row, "key", "rows[$index].key"),
                value = JsonFields.string(row, "value", "rows[$index].value"),
                scope = SettingScope.PORTABLE,
            )
        }
        return PortableSettingsSnapshot(1, rows)
    }

    fun encode(snapshot: PortableSettingsSnapshot): String {
        val rows = snapshot.rows.map { row ->
            JsonValue.obj(
                "key" to JsonValue.text(row.key),
                "value" to JsonValue.text(row.value),
                "scope" to JsonValue.text(row.scope.wire),
            )
        }
        return JsonValue.obj(
            "version" to JsonValue.integer(1),
            "rows" to JsonValue.Array(rows),
        ).literal()
    }
}

data class RemotePreferenceRow(
    val key: String,
    val value: String,
    val updatedAt: String,
)

object RemotePreferenceRows {
    private val requiredKeys = setOf("key", "updatedAt", "value")

    fun decode(text: String): List<RemotePreferenceRow> {
        val parsed = try {
            JsonValue.parse(text)
        } catch (failure: Exception) {
            settingsError("INVALID_REMOTE", "remote preference file cannot be decoded")
        }
        val array = parsed as? JsonValue.Array
            ?: settingsError("INVALID_REMOTE", "remote preference file must contain an array")
        return array.values.mapIndexed { index, item ->
            val row = item as? JsonValue.Object
                ?: settingsError("INVALID_REMOTE", "remote preference row $index must be an object")
            if (row.keys().toSet() != requiredKeys) {
                settingsError("INVALID_REMOTE", "remote preference row $index must have exactly key, value, updatedAt")
            }
            val key = JsonFields.string(row, "key", "row[$index].key")
            val value = JsonFields.string(row, "value", "row[$index].value")
            val updatedAt = JsonFields.string(row, "updatedAt", "row[$index].updatedAt")
            if (SettingClassification.classify(key).scope != SettingScope.PORTABLE) {
                settingsError("NON_PORTABLE_REMOTE", "remote row $index contains non-portable key $key")
            }
            StrictTimestamp.parseMillis(updatedAt)
            RemotePreferenceRow(
                key = key,
                value = PortableConfigSanitizer.normalizePortableValue(key, value),
                updatedAt = updatedAt,
            )
        }
    }

    fun encode(rows: List<RemotePreferenceRow>): String {
        val values = rows.map { row ->
            JsonValue.obj(
                "key" to JsonValue.text(row.key),
                "value" to JsonValue.text(row.value),
                "updatedAt" to JsonValue.text(row.updatedAt),
            )
        }
        return JsonValue.Array(values).literal()
    }
}

enum class SyncDirection(val wire: String) {
    UPLOAD("Upload"),
    DOWNLOAD("Download"),
    BIDIRECTIONAL("Bidirectional"),
    ;

    companion object {
        fun fromWire(value: String): SyncDirection =
            entries.firstOrNull { it.wire == value }
                ?: settingsError("INVALID_DIRECTION", "unsupported sync direction $value")
    }
}

object StrictTimestamp {
    private val pattern = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})$",
    )

    fun parseMillis(value: String): Long {
        if (value.length > 40) settingsError("INVALID_TIMESTAMP", "updatedAt must be a bounded RFC3339 timestamp")
        val match = pattern.matchEntire(value)
            ?: settingsError("INVALID_TIMESTAMP", "updatedAt is not strict RFC3339: $value")
        val groups = match.groupValues
        val date = parseDate(groups, value)
        validateClock(groups, value)
        val offsetMinutes = parseOffset(groups[8], value)
        return epochMillis(date, groups, offsetMinutes)
    }

    private fun parseDate(groups: List<String>, value: String): LocalDate {
        val year = groups[1].toInt()
        val date = try {
            LocalDate.of(year, groups[2].toInt(), groups[3].toInt())
        } catch (failure: DateTimeException) {
            settingsError("INVALID_TIMESTAMP", "updatedAt has an invalid calendar field: $value")
        }
        if (year < 1000) {
            settingsError("INVALID_TIMESTAMP", "updatedAt has an invalid calendar field: $value")
        }
        return date
    }

    private fun validateClock(groups: List<String>, value: String) {
        if (groups[4].toInt() > 23 || groups[5].toInt() > 59 || groups[6].toInt() > 59) {
            settingsError("INVALID_TIMESTAMP", "updatedAt has an invalid calendar field: $value")
        }
    }

    private fun parseOffset(zone: String, value: String): Int {
        if (zone == "Z") return 0
        val offsetHour = zone.substring(1, 3).toInt()
        val offsetMinute = zone.substring(4, 6).toInt()
        if (offsetHour > 23 || offsetMinute > 59) {
            settingsError("INVALID_TIMESTAMP", "updatedAt has an invalid offset: $value")
        }
        return (offsetHour * 60 + offsetMinute) * if (zone[0] == '-') -1 else 1
    }

    private fun epochMillis(date: LocalDate, groups: List<String>, offsetMinutes: Int): Long {
        val seconds = LocalDateTime.of(
            date,
            LocalTime.of(groups[4].toInt(), groups[5].toInt(), groups[6].toInt()),
        ).toEpochSecond(ZoneOffset.UTC) - offsetMinutes * 60L
        val millis = groups[7].padEnd(9, '0').substring(0, 3).toInt()
        return seconds * 1000L + millis
    }
}

data class AppearanceValue(
    val source: String,
    val key: String,
    val value: String,
)

data class AppearanceSnapshot(
    val version: Int,
    val mode: AppearanceValue?,
    val palette: AppearanceValue?,
    val customColor: AppearanceValue?,
) {
    init {
        if (version != 1) settingsError("INVALID_SNAPSHOT", "appearance snapshot must be version 1")
    }
}

object AppearanceSettings {
    data class Source(val sqlite: String, val local: String)

    val MODE = Source("theme", "dstu-theme-mode")
    val PALETTE = Source("theme_palette", "dstu-theme-palette")
    val CUSTOM_COLOR = Source("theme_custom_color", "dstu-theme-custom-color")

    fun capture(settings: SettingReader, local: LocalSettingReader): AppearanceSnapshot {
        val mode = appearanceValue(settings, local, MODE, localAuthoritative = true)
        val palette = appearanceValue(settings, local, PALETTE, localAuthoritative = false)
        val customColor = appearanceValue(settings, local, CUSTOM_COLOR, localAuthoritative = false)
        listOf(MODE to mode, PALETTE to palette, CUSTOM_COLOR to customColor)
            .filter { it.second != null }
            .forEach { (source, item) ->
                PortableConfigSanitizer.normalizePortableValue(source.sqlite, item!!.value)
            }
        return AppearanceSnapshot(1, mode, palette, customColor)
    }

    fun replay(
        settings: SettingWriter,
        local: LocalSettingWriter,
        snapshot: AppearanceSnapshot,
    ): List<AppearanceValue> {
        if (snapshot.version != 1) settingsError("INVALID_SNAPSHOT", "appearance snapshot must be version 1")
        val pairs = listOf(
            snapshot.mode?.let { MODE to it },
            snapshot.palette?.let { PALETTE to it },
            snapshot.customColor?.let { CUSTOM_COLOR to it },
        ).filterNotNull()
        val validated = pairs.map { (source, item) ->
            val value = PortableConfigSanitizer.normalizePortableValue(source.sqlite, item.value)
            Triple(source, item, value)
        }
        for ((source, _, value) in validated) {
            settings.save(source.sqlite, value)
            local.set(source.local, value)
        }
        return validated.map { (source, item, value) ->
            AppearanceValue(item.source, source.local, value)
        }
    }

    private fun appearanceValue(
        settings: SettingReader,
        local: LocalSettingReader,
        source: Source,
        localAuthoritative: Boolean,
    ): AppearanceValue? {
        val localValue = local.get(source.local)
        val sqliteValue = settings.get(source.sqlite)
        val value = if (localAuthoritative) localValue ?: sqliteValue else sqliteValue ?: localValue
        if (value == null) return null
        val fromLocal = if (localAuthoritative) localValue != null else sqliteValue == null
        return AppearanceValue(
            source = if (fromLocal) "localStorage" else "settings",
            key = if (fromLocal) source.local else source.sqlite,
            value = value,
        )
    }
}
