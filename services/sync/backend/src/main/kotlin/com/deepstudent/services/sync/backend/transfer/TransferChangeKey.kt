package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.services.sync.backend.SyncWireCodec

internal data class TransferChangeKey(
    val key: String,
    val pathDevice: String,
    val sequence: Long,
    val version: ULong,
    val operation: String,
    val v4: Boolean,
) {
    fun matchesDevice(device: String): Boolean = pathDevice == device || pathDevice == SyncWireCodec.deviceHash(device)

    companion object {
        private val pattern = Regex("^data_governance/(changes|v4/shards)/([^/]+)/([0-9]{12})-([0-9]+)-([A-Za-z0-9_-]{1,128})\\.json\\.zst$")

        fun parse(key: String): TransferChangeKey? {
            val match = pattern.matchEntire(key) ?: return null
            val sequence = match.groupValues[3].toLongOrNull() ?: return null
            val version = match.groupValues[4].toULongOrNull() ?: return null
            return TransferChangeKey(key, match.groupValues[2], sequence, version, match.groupValues[5],
                match.groupValues[1] == "v4/shards")
        }
    }
}
