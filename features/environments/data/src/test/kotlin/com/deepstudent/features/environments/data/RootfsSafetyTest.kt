package com.deepstudent.features.environments.data

import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractionBudgetIsEnforcedBeforeAnOversizedEntryIsWritten() {
        val original = EnvironmentCatalog.UbuntuNobleAarch64
        val manifest = original.copy(budget = original.budget.copy(extractionBytes = 1))
        val policy = RootfsExtractionPolicy(manifest)
        val error = runCatching {
            policy.accept(RootfsEntryDescriptor("${manifest.archiveRoot}/too-big", RootfsEntryType.REGULAR, 2))
        }.exceptionOrNull()
        assertTrue(error is EnvironmentCommandException)
        assertEquals(EnvironmentFailureCode.ENTRY_LIMIT, (error as EnvironmentCommandException).failure.code)
    }

    @Test fun corruptedGenerationCannotEscapePrivateStagingOrGenerations() {
        val paths = EnvironmentPaths(temporary.root)
        for (value in listOf("../outside", "/outside", "a/b", "..", ".", "a\\b")) {
            assertTrue(runCatching { paths.generationRoot(value) }.isFailure)
            assertTrue(runCatching { paths.stagingRoot(value) }.isFailure)
        }
    }

    @Test fun dnsUsesOnlyExplicitNetworkAddressesAndRefreshesOnReentry() {
        val runtime = File(temporary.root, "runtime")
        val paths = EnvironmentPaths(temporary.root, runtime, listOf("192.0.2.53"))
        paths.ensureDirectories()
        EnvironmentBindings.ensureResolvConf(paths)
        assertEquals("nameserver 192.0.2.53\n", paths.resolvConf.readText())
        val updated = EnvironmentPaths(temporary.root, runtime, listOf("2001:db8::53"))
        EnvironmentBindings.ensureResolvConf(updated)
        assertEquals("nameserver 2001:db8::53\n", paths.resolvConf.readText())
        assertFalse(EnvironmentBindings.binds(EnvironmentPaths(temporary.newFolder())).any { it.guestPath == "/etc/resolv.conf" })
    }
}
