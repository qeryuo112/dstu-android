package com.deepstudent.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.backup.BackupForegroundRule
import com.deepstudent.services.sync.data.LegacyMarkerWebDavTest
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyMarkerWebDavDeviceTest : LegacyMarkerWebDavTest() {
    @get:Rule val foreground = BackupForegroundRule()
}
