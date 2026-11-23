package com.deepstudent.app.backup

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrdinaryRestoreBaselineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun fullZipResetsFourLogsOnlyInCandidateAndReopenNeverErasesNewChanges() {
        RestoreBaselineCases(temp.root, ::hostRestoreFixture).fourDatabaseRestore()
        System.getenv("DSTU_S216_HOST_FIXTURE")?.let { path ->
            val directory = temp.newFolder("roundtrip")
            runBlocking { verifyHostRestoreRoundtrip(directory, File(path)) }
        }
    }

    @Test fun cleanupFailureInLastDatabaseCannotSwitchSlotAndRetryRestagesFromTheZip() {
        RestoreBaselineCases(temp.root, ::hostRestoreFixture).failedRestoreCanRetry()
    }
}
