package com.deepstudent.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataSpaceAndroidTest : DataSpaceSharedTests() {
    override fun temporaryParent(): File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
}
