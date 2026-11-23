package com.deepstudent.app.backup

import com.deepstudent.platform.security.KeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.backup.data.CommitDisposition
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.sync.data.LocalSyncStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID

internal suspend fun verifyHostRestoreRoundtrip(directory: File, data: File) {
    val fixture = hostRestoreFixture(directory)
    val stateRoot = directory.resolve("writer")
    val openSecurity = { SecurityFactory.create(directory.resolve("secrets"), Wrapper) }
    RestoreSyncScenario(fixture, directory, data) { checkNotNull(System.getenv("DSTU_S216_HOST_$it")) }
        .verify(stateRoot, openSecurity) { zip ->
            val operation = UUID.randomUUID().toString()
            assertEquals(
                RestoreExecutionResult.Committed(operation, CommitDisposition.NEWLY_COMMITTED),
                fixture.restore(zip, operationId = operation),
            )
            openSecurity().use { security -> assertTrue(LocalSyncStore(stateRoot, security.credentials).rotateWriterForRestore(operation)) }
            RestoreSyncScenario.RestoredSlot(fixture.active, operation)
        }
}

private object Wrapper : KeyWrapper {
    override fun wrap(plaintext: ByteArray) = plaintext.copyOf()
    override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
}
