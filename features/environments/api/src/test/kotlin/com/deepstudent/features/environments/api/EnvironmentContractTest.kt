package com.deepstudent.features.environments.api

import org.junit.Assert.*
import org.junit.Test

class EnvironmentContractTest {
    private val manifest = EnvironmentCatalog.UbuntuNobleAarch64

    @Test fun frozenManifestIsStableAndBudgeted() {
        assertEquals("91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa", manifest.sha256)
        assertEquals(64_133_552L, manifest.archiveBytes)
        assertTrue(manifest.minimumFreeBytes >= manifest.archiveBytes + manifest.extractionBytesBudget)
        assertSame(manifest, EnvironmentCatalog.find(manifest.id))
    }

    @Test fun identityAndArchiveMustBeSafeBasenames() {
        listOf("../root", ".", "..", "/root", "a\nforged-event").forEach { value ->
            invalid { manifest.copy(id = value) }
            invalid { manifest.copy(archive = manifest.archive.copy(root = value)) }
        }
        invalid { manifest.copy(archive = manifest.archive.copy(name = "../root.tar.xz")) }
        invalid { manifest.copy(archive = manifest.archive.copy(sha256 = "not-a-hash")) }
    }

    @Test fun budgetCheckCannotOverflowLong() {
        invalid { manifest.copy(budget = RootfsResourceBudget(1024, 1024)) }
        invalid { manifest.copy(archive = manifest.archive.copy(bytes = Long.MAX_VALUE),
            budget = RootfsResourceBudget(1, Long.MAX_VALUE)) }
    }

    @Test fun taskIdentityCannotInjectPathsOrLogs() {
        listOf("../etc/passwd", "task-id\ninjected", "short").forEach { value -> invalid { EnvironmentTaskId(value) } }
    }

    private fun invalid(block: () -> Unit) {
        try { block(); fail("must reject invalid input") } catch (_: IllegalArgumentException) { }
    }
}
