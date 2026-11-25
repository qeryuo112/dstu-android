package com.deepstudent.app.sync

import com.deepstudent.app.backup.hostRestoreFixture
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.security.KeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionApplyHostTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun realFactoryUsesOnlyRegisteredMigratedDomainTables() = runBlocking { fixture().roundtrip(this) }

    @Test fun failedPrimaryCommitIsSafeToReplay() = runBlocking { fixture().replay(this) }

    private fun fixture() = ProductionApplyCases(hostRestoreFixture(directory.root).migrations, directory.root, NoopLogger) { root ->
        SecurityFactory.create(root, FixtureKeyWrapper)
    }
}

private object FixtureKeyWrapper : KeyWrapper {
    override fun wrap(plaintext: ByteArray) = plaintext.copyOf()
    override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
}
