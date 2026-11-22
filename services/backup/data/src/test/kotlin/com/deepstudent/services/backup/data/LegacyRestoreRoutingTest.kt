package com.deepstudent.services.backup.data

import com.deepstudent.services.backup.api.CoverageStatus
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.DomainPreview
import com.deepstudent.services.backup.api.LegacyImportReport
import com.deepstudent.services.backup.api.PlannedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyRestoreRoutingTest {
    private val audit = PlannedFile("databases/audit.db", "legacy-import/audit.db", 512, "0".repeat(64))
    private val coverage = DomainPreview("audit", CoverageStatus.COMPLETE, DomainAction.PRESERVE_INACTIVE, 1, 512)
    private val base = fixturePlan()
    private val plan = base.copy(
        domains = base.domains + coverage, files = base.files + audit,
        source = base.source.copy(legacyImport = LegacyImportReport("3.0.0", "0.20.0", mapOf("vfs" to 20261111L))),
    )

    @Test fun onlyLegacyMayCopyExactAuditPathAndOnlyAsInactiveEvidence() {
        checkPlan(plan)
        val wrongWire = plan.copy(source = plan.source.copy(legacyImport = null))
        assertEquals(RestoreError.UNTRUSTED_TARGET, assertThrows(RestoreFailure::class.java) { checkPlan(wrongWire) }.code)
        val wrongAction = plan.copy(domains = base.domains + coverage.copy(action = DomainAction.STAGE_IN_SLOT))
        assertEquals(RestoreError.INVALID_PLAN, assertThrows(RestoreFailure::class.java) { checkPlan(wrongAction) }.code)
        val active = plan.copy(files = base.files + audit.copy(slotRelativePath = "databases/audit.db"))
        assertEquals(RestoreError.UNTRUSTED_TARGET, assertThrows(RestoreFailure::class.java) { checkPlan(active) }.code)
    }

    @Test fun bothBlobRootsUseSameNamespaceAndCannotFormAFileDirectoryCollision() {
        val files = listOf("vfs_blobs/a", "vfs_blobs/a-b", "vfs_blobs/a/child").mapIndexed { i, target ->
            val wire = if (i % 2 == 0) "assets/$target" else target
            PlannedFile(wire, target, 0, "0".repeat(64))
        }
        val conflict = plan.copy(files = plan.files + files)
        assertEquals(RestoreError.INVALID_PLAN, assertThrows(RestoreFailure::class.java) { checkPlan(conflict) }.code)
    }
}
