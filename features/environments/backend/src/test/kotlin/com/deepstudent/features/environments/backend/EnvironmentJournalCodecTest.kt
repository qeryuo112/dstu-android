package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import org.junit.Assert.*
import org.junit.Test

class EnvironmentJournalCodecTest {
    @Test fun allCheckpointsAndInterruptedPackageRoundTripWithoutLosingPriorActive() {
        EnvironmentCheckpoint.entries.forEach { checkpoint ->
            val original = readyJournal().copy(checkpoint = checkpoint, pendingRecipeId = "git",
                failure = EnvironmentFailure(EnvironmentFailureCode.INTERRUPTED, "interrupted", true))
            assertEquals(original, EnvironmentJournalCodec.decode(EnvironmentJournalCodec.encode(original)))
        }
    }

    @Test fun corruptTypesUnsafeTokensAndMismatchedReceiptsAreRejected() {
        val text = EnvironmentJournalCodec.encode(readyJournal())
        listOf(
            text.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            text.replace("\"failure\":null", "\"failure\":false"),
            text.replace("\"generation-old\"", "\"../escape\""),
            text.replace("\"recipeId\":\"git\"", "\"recipeId\":\"project-venv\""),
            text.replace("\"activeGeneration\":\"generation-old\"", "\"activeGeneration\":\"\""),
        ).forEach { corrupt ->
            assertNotEquals(text, corrupt)
            try { EnvironmentJournalCodec.decode(corrupt); fail("invalid journal must be rejected") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun observerFailureAfterCheckpointCannotRollBackDurableProgress() = kotlinx.coroutines.runBlocking {
        val f = EnvironmentFixture()
        var interrupted = false
        val update: EnvironmentUpdate = { journal, _, _ ->
            if (journal.checkpoint == EnvironmentCheckpoint.HEALTH && !interrupted) {
                interrupted = true
                throw kotlinx.coroutines.CancellationException("observer cut")
            }
        }
        try {
            f.coordinator().installRootfs(com.deepstudent.features.environments.api.EnvironmentTaskId("task-observer"), testManifest, unreadSource, update)
            fail("expected observer interruption")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(EnvironmentCheckpoint.HEALTH, f.store.value?.checkpoint)
        f.coordinator().retry(com.deepstudent.features.environments.api.EnvironmentTaskId("task-observer-retry"), testManifest, null, f.update)
        assertEquals(1, f.count(EnvironmentCheckpoint.EXTRACT))
    }
}
