package com.deepstudent.app.power

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackgroundPreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun preferences(): BackgroundPreferences {
        val file = File(folder.newFolder(), "$BACKGROUND_POWER_STORE_NAME.preferences_pb")
        return BackgroundPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @Test
    fun keepAliveDefaultsToOffAndOnlyTheBooleanRoundTrips() = runBlocking {
        val preferences = preferences()
        assertEquals(false, preferences.enabled.first())
        preferences.setEnabled(true)
        assertEquals(true, preferences.enabled.first())
        preferences.setEnabled(false)
        assertEquals(false, preferences.enabled.first())
    }
}
