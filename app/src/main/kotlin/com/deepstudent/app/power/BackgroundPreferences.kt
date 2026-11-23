package com.deepstudent.app.power

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backgroundPowerStore: DataStore<Preferences> by preferencesDataStore(name = "background_power")

/** Stable file name of the local, non-synchronized keep-alive preference. */
const val BACKGROUND_POWER_STORE_NAME: String = "background_power"

/**
 * Local-only keep-alive preference. Nothing here is synchronized or exported: the file stays in this
 * app's private storage, the default is off, and the only public surface is [enabled] and
 * [setEnabled]. Turning it on does not start work; the caller decides what real duties to keep.
 */
class BackgroundPreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.backgroundPowerStore)

    val enabled: Flow<Boolean> = store.data.map { it[KEEP_ALIVE] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[KEEP_ALIVE] = enabled }
    }

    private companion object {
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive_enabled")
    }
}
