package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginQuotaAndTrustTest {
    @Test
    fun recordDepthAndByteQuotaMatchFrozenLimits() {
        val depth16 = JsonValue.parse("[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]")
        val depth17 = JsonValue.parse("[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]")
        assertEquals(16, depth16.containerDepth())
        PluginQuotaPolicy.validateRecord(depth16, PluginUnknownFields.EMPTY)
        assertEquals(
            "DEPTH_EXCEEDED",
            assertThrows(DstuException::class.java) {
                PluginQuotaPolicy.validateRecord(depth17, PluginUnknownFields.EMPTY)
            }.error.code,
        )

        val fits = JsonValue.text("a".repeat(65_532))
        val over = JsonValue.text("a".repeat(65_533))
        assertEquals(PluginDataContract.MAX_RECORD_BYTES, PluginQuotaPolicy.validateRecord(fits, PluginUnknownFields.EMPTY))
        assertEquals(
            "RECORD_TOO_LARGE",
            assertThrows(DstuException::class.java) {
                PluginQuotaPolicy.validateRecord(over, PluginUnknownFields.EMPTY)
            }.error.code,
        )
    }

    @Test
    fun ownerQuotaCountsLiveRowsAndKeepsTombstonesOut() {
        val full = PluginOwnerUsage(2_000, 5)
        assertFalse(PluginQuotaPolicy.checkPut(full, null, 1, tombstone = false).allowed)
        assertTrue(PluginQuotaPolicy.checkPut(full, null, 1, tombstone = true).allowed)
        assertTrue(PluginQuotaPolicy.checkPut(full, 5, 5, tombstone = false).allowed)

        val bytesFull = PluginOwnerUsage(1, PluginDataContract.MAX_OWNER_BYTES)
        assertFalse(PluginQuotaPolicy.checkPut(bytesFull, null, 1, tombstone = false).allowed)
        assertTrue(PluginQuotaPolicy.checkPut(bytesFull, 1, 1, tombstone = false).allowed)
    }

    @Test
    fun deletingLiveRowReleasesCountAndBytesForLaterInsert() {
        val full = PluginOwnerUsage(2_000, 10)
        val released = PluginQuotaPolicy.checkPut(full, previousLiveBytes = 10, newRecordBytes = 0, tombstone = true)
        assertTrue(released.allowed)
        assertTrue(released.stateValid)
        assertEquals(1_999L, released.records)
        assertEquals(0L, released.bytes)

        val afterDelete = PluginOwnerUsage(released.records.toInt(), released.bytes)
        val restored = PluginQuotaPolicy.checkPut(afterDelete, previousLiveBytes = null, newRecordBytes = 10, tombstone = false)
        assertTrue(restored.allowed)
        assertEquals(2_000L, restored.records)
        assertEquals(10L, restored.bytes)
    }

    @Test
    fun quotaArithmeticOverflowAndInconsistentStateFailClosed() {
        val byteOverflow = PluginQuotaPolicy.checkPut(
            PluginOwnerUsage(0, Long.MAX_VALUE),
            previousLiveBytes = null,
            newRecordBytes = 1,
            tombstone = false,
        )
        assertFalse(byteOverflow.allowed)
        assertFalse(byteOverflow.stateValid)
        assertTrue(byteOverflow.bytes > 0)

        val recordOverflow = PluginQuotaPolicy.checkPut(
            PluginOwnerUsage(Int.MAX_VALUE, 0),
            previousLiveBytes = null,
            newRecordBytes = 0,
            tombstone = false,
        )
        assertFalse(recordOverflow.allowed)
        assertEquals(Int.MAX_VALUE.toLong() + 1L, recordOverflow.records)

        val negativePrevious = PluginQuotaPolicy.checkPut(
            PluginOwnerUsage(1, 10),
            previousLiveBytes = -1,
            newRecordBytes = 1,
            tombstone = false,
        )
        assertFalse(negativePrevious.allowed)
        assertFalse(negativePrevious.stateValid)

        val previousBeyondUsage = PluginQuotaPolicy.checkPut(
            PluginOwnerUsage(1, 10),
            previousLiveBytes = 11,
            newRecordBytes = 1,
            tombstone = false,
        )
        assertFalse(previousBeyondUsage.allowed)
        assertFalse(previousBeyondUsage.stateValid)

        val releaseWithoutLiveRow = PluginQuotaPolicy.checkPut(
            PluginOwnerUsage(0, 0),
            previousLiveBytes = 0,
            newRecordBytes = 0,
            tombstone = true,
        )
        assertFalse(releaseWithoutLiveRow.allowed)
        assertFalse(releaseWithoutLiveRow.stateValid)
    }

    @Test
    fun unknownMergeKeepsOmittedFieldsAndDropsCoveredFields() {
        val envelope = UnknownFieldEnvelope(
            value = JsonValue.parse("""{"n":9007199254740993,"nullable":null}"""),
            hlc = "2026-11-15T16:00:00Z",
            dev = "device-a",
            seq = 0,
        )
        val existing = PluginUnknownFields(mapOf("future" to envelope))
        val incomingData = JsonValue.parse("""{"future":{"n":9007199254740993,"nullable":null}}""")
        val merged = PluginApplyValidator.mergeUnknown(
            recordWithUnknown(existing),
            incomingData,
            PluginUnknownFields.EMPTY,
        )
        assertTrue(merged.fields.isEmpty())

        val preserved = PluginApplyValidator.mergeUnknown(
            recordWithUnknown(existing),
            JsonValue.parse("""{"text":"new"}"""),
            PluginUnknownFields.EMPTY,
        )
        assertTrue(preserved.fields.containsKey("future"))
    }

    @Test
    fun unknownFieldsSnapshotCannotBeMutatedThroughConstructorOrGetter() {
        val envelope = UnknownFieldEnvelope(
            value = JsonValue.text("kept"),
            hlc = "2026-11-15T16:00:00Z",
            dev = "device-a",
            seq = 0,
        )
        val source = mutableMapOf("future" to envelope)
        val unknown = PluginUnknownFields(source)
        val before = unknown.canonical()
        source["other"] = envelope
        assertEquals(before, unknown.canonical())
        assertEquals(1, unknown.fields.size)

        @Suppress("UNCHECKED_CAST")
        val mutableView = unknown.fields as MutableMap<String, UnknownFieldEnvelope>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableView.put("other", envelope)
        }
    }

    @Test
    fun localTrustServicesAndRemoteContentRemainSeparated() {
        val owner = PluginOwnerId.parse("plugin.public")
        assertEquals("secret.plugin.plugin.public.apiKey", PluginLocalKeys.secretKey(owner, "apiKey"))
        assertEquals("plugin.schedule.plugin.public.nightly", PluginLocalKeys.scheduleKey(owner, "nightly"))
        assertTrue(PluginLocalKeys.secretKey(owner, "apiKey").startsWith(PluginLocalKeys.SECRET_PREFIX))

        val policy = FakeLocalPolicy(owner)
        val content = PluginContentDescriptor(
            owner,
            PluginPackageVersion.parse("1.0.0"),
            PluginSourceHash.parse("a".repeat(64)),
            1,
        )
        val inspection = PluginContentPolicy.inspect(content, policy)
        assertFalse(inspection.install.installed)
        assertFalse(inspection.executes)

        policy.setLocalInstall(installed = true, enabled = true, trusted = true, trust = PluginTrust.FULL)
        val uninstall = PluginUninstall.plan(policy, owner, authorized = true, deleteData = false)
        assertFalse(uninstall.installState.trusted)
        assertTrue(uninstall.retainedSyncedData)
        assertFalse(uninstall.executes)
        assertThrows(DstuException::class.java) {
            PluginUninstall.plan(policy, owner, authorized = false)
        }
    }

    private fun recordWithUnknown(unknown: PluginUnknownFields): PluginRecord = PluginRecord(
        ownerId = PluginOwnerId.parse("plugin.public"),
        namespace = PluginNamespace.parse("prefs"),
        recordId = PluginRecordId.parse("item"),
        packageVersion = PluginPackageVersion.parse("1.0.0"),
        sourceHash = PluginSourceHash.parse("a".repeat(64)),
        schemaVersion = 1,
        minWriterSchema = 1,
        state = PluginRecordState(
            dataJson = "{}",
            unknownJson = unknown.canonical(),
            deletedAt = null,
            deviceId = PluginDeviceId.parse("device-a"),
            localVersion = 1,
            updatedAt = "2026-11-15T16:00:00Z",
        ),
    )

    private class FakeLocalPolicy(ownerId: PluginOwnerId) : LocalPluginPolicy {
        private val owner = ownerId
        private var state = LocalPluginInstallState(
            owner, installed = false, enabled = false, trusted = false,
            trust = PluginTrust.UNTRUSTED, fullTrust = false,
        )

        override fun inspect(): LocalPluginInstallState = state

        override fun setLocalInstall(
            installed: Boolean?,
            enabled: Boolean?,
            trusted: Boolean?,
            trust: PluginTrust?,
        ): LocalPluginInstallState {
            val nextTrusted = trusted ?: state.trusted
            val nextTrust = if (nextTrusted) (trust ?: state.trust) else PluginTrust.UNTRUSTED
            state = LocalPluginInstallState(
                owner,
                installed ?: state.installed,
                enabled ?: state.enabled,
                nextTrusted,
                nextTrust,
                nextTrusted && nextTrust == PluginTrust.FULL,
            )
            return state
        }

        override fun uninstall(authorized: Boolean, deleteData: Boolean): LocalPluginUninstall {
            if (!authorized) pluginError("LOCAL_AUTH_REQUIRED", "authorization required")
            state = LocalPluginInstallState(owner, false, false, false, PluginTrust.UNTRUSTED, false)
            return LocalPluginUninstall(state, deleteData)
        }
    }
}
