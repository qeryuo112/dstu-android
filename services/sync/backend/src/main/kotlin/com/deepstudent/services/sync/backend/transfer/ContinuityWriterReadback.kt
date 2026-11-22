package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ContinuityClaimException
import com.deepstudent.services.sync.backend.ContinuityClaimFailure
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncPayloadCodec

/** Observe the repository's actual publication sequence, not an untrusted sidecar field. */
object ContinuityWriterReadback {
    suspend fun sequence(provider: ObjectProvider, codec: SyncPayloadCodec, writerId: String): Long {
        val keys = provider.list("data_governance/changes") + provider.list("data_governance/v4/shards")
        val writerKeys = keys.asSequence().distinct().map { key ->
            TransferChangeKey.parse(key) ?: throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
        }.filter { it.matchesDevice(writerId) }.toList()
        // Match ChangeTransfer.downloadDevice: different keys for one writer/sequence
        // are a conflicting history, not one valid publication at that maximum.
        if (writerKeys.map { it.sequence }.toSet().size != writerKeys.size) {
            throw ContinuityClaimException(ContinuityClaimFailure.WRITER_SEQUENCE_CONFLICT)
        }
        val observed = writerKeys.maxOfOrNull { it.sequence } ?: 0
        val manifest = DeviceManifests(provider, codec).read(includeSuperseded = true)[writerId] ?: return observed
        if (manifest["superseded_by"] != JsonValue.Null) throw SyncFailure(SyncError.INVALID_REPOSITORY)
        val recorded = (manifest["published_max_seq"] as? JsonValue.Number)?.asLongOrNull()
            ?: throw SyncFailure(SyncError.INVALID_REPOSITORY)
        if (recorded < 0) throw SyncFailure(SyncError.INVALID_REPOSITORY)
        return maxOf(observed, recorded)
    }
}
