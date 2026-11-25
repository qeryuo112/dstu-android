package com.deepstudent.services.sync.data.s230

import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class S230CancellationTest {
    @Test fun cancelledRegistrySourceIsNotTranslatedToAnUnavailableContract() {
        val cancelled = CancellationException("cancel fixture")
        val thrown = assertThrows(CancellationException::class.java) {
            SyncCaptureRegistry.load(MigrationSource { throw cancelled })
        }
        assertSame(cancelled, thrown)
    }
}
