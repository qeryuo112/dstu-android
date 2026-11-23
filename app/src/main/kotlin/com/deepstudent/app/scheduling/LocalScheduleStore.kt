package com.deepstudent.app.scheduling

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deepstudent.platform.background.scheduling.ScheduleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.localSchedules by preferencesDataStore("background_schedules")

/** A device-local index of user schedules, not a second task/execution ledger. */
internal class LocalScheduleStore(context: Context) {
    private val store = context.applicationContext.localSchedules
    val changes: Flow<List<LocalSchedule>> = store.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(PREFIX) && value is String) decode(key.name, value) else null
        }.sortedBy { it.due }
    }

    suspend fun current(): List<LocalSchedule> = changes.first()

    suspend fun find(id: ScheduleId): LocalSchedule? {
        val value = store.data.first()[key(id)] ?: return null
        return ScheduleCodec.decode(id, value)
    }

    suspend fun create(row: LocalSchedule) {
        store.edit {
            check(it[key(row.id)] == null) { "local schedule already exists" }
            check(it.asMap().size < MAX_SCHEDULES) { "local schedule limit reached; remove old schedules first" }
            it[key(row.id)] = ScheduleCodec.encode(row)
        }
    }

    suspend fun put(row: LocalSchedule) {
        store.edit { it[key(row.id)] = ScheduleCodec.encode(row) }
    }
    suspend fun remove(id: ScheduleId) {
        store.edit { it.remove(key(id)) }
    }

    private fun decode(record: String, raw: String): LocalSchedule =
        ScheduleCodec.decode(ScheduleId.parse(record.removePrefix(PREFIX)), raw)

    private fun key(id: ScheduleId): Preferences.Key<String> = stringPreferencesKey(PREFIX + id.value)

    private companion object {
        const val PREFIX = "schedule."
        const val MAX_SCHEDULES = 128
    }
}
