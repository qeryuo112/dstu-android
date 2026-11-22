package com.deepstudent.services.backup.backend

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ContinuityFixtureTest {
    @Test fun frozenFixtureMatchesIndependentlyVerifiedArchiveAndPairedIdentity() {
        val result = ContinuityValidator.validate(ContinuityFixture.bytes, ContinuityFixture.trust)
        assertTrue(result is ContinuityValidation.Matched)
        result as ContinuityValidation.Matched
        assertEquals("5a0e16c0-0000-4000-8000-000000000002", result.snapshotEpoch)
        assertEquals(ContinuityFixture.trust.archive.sha256, result.archiveSha256)
        assertEquals(ContinuityFixture.trust.verifiedSidecarSha256, result.sidecarSha256)
        assertEquals(ContinuityFixture.trust.installation, result.sourceInstallation)
        assertEquals("continuity-takeover/${result.snapshotEpoch}/${result.archiveSha256}", result.takeoverKey)
        assertEquals(5L, result.state.upload.lastSeq)
        assertEquals(listOf(ContinuityCursor("device-91bc4de0", 12)), result.state.cursors)
        assertEquals(setOf(3L, 7L), result.state.watermarks.map { it.lastAppliedOffset }.toSet())
        assertEquals(listOf(ContinuityPublication("device-7f3a91c2", "blob", "op-legacy-1", 4)), result.state.publications)
        assertEquals(listOf("legacy/object/one"), result.state.processedKeys)
    }

    @Test fun validationDoesNotMutateInputOrExposeContentsViaToString() {
        val bytes = ContinuityFixture.bytes.copyOf()
        val before = bytes.copyOf()
        val result = ContinuityValidator.validate(bytes, ContinuityFixture.trust) as ContinuityValidation.Matched
        assertArrayEquals(before, bytes)
        for (value in listOf(result.toString(), result.state.toString(), result.state.upload.toString(),
            result.state.repository.toString(), ContinuityFixture.trust.toString(), result.state.publications.single().toString())) {
            assertFalse(value.contains("device-")); assertFalse(value.contains("file:")); assertFalse(value.contains("default"))
        }
        @Suppress("UNCHECKED_CAST")
        try { (result.state.cursors as MutableList<ContinuityCursor>).clear(); fail("list was mutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(1, result.state.cursors.size)
    }

    @Test fun inputDigestIsAnIndependentBindingAndTamperingFailsBeforeParsing() {
        val tampered = ContinuityFixture.input(ContinuityFixture.root.changeState("uploadSeq") { rows ->
            rows.changeRow(0) { it.change("last_seq", JsonPrimitive(0)) }
        })
        ContinuityFixture.rejected(ContinuityValidator.validate(tampered, ContinuityFixture.trust), ContinuityFailure.TRUST_MISMATCH)
        val mismatched = ContinuityFixture.trust.copy(verifiedSidecarSha256 = "f".repeat(64))
        ContinuityFixture.rejected(ContinuityValidator.validate(ContinuityFixture.bytes, mismatched), ContinuityFailure.TRUST_MISMATCH)
    }

    @Test fun inputChangesAfterEntryCannotAffectTheAuthenticatedCopy() {
        val mutable = ContinuityFixture.bytes.copyOf()
        val result = ContinuityValidator.validate(mutable, ContinuityFixture.trust) { mutable.fill(0) }
        assertTrue(result is ContinuityValidation.Matched)
        assertEquals(5L, (result as ContinuityValidation.Matched).state.upload.lastSeq)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAValidationFailure() {
        val failure = CancellationException("cancelled")
        try { ContinuityValidator.validate(ContinuityFixture.bytes, ContinuityFixture.trust) { throw failure }; fail("not cancelled") }
        catch (actual: CancellationException) { assertSame(failure, actual) }
        var calls = 0
        try {
            ContinuityValidator.validate(ContinuityFixture.bytes, ContinuityFixture.trust) {
                calls++
                if (calls == 2) throw failure
            }
            fail("not cancelled in parser")
        } catch (actual: CancellationException) { assertSame(failure, actual) }
    }
}
