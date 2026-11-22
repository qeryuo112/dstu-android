package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.PlainSyncPayload
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncFormatCodec
import com.deepstudent.services.sync.backend.WireValues

class RemoteGate(private val provider: ObjectProvider, private val expectedInstance: String) {
    suspend fun minimumReader(): Int {
        val repository = read(provider.get(PairingService.FORMAT_KEY), provider.get(PairingService.INSTANCE_KEY))
        if (repository.instanceId != expectedInstance) WireValues.invalid()
        return repository.minimumReader
    }

    internal data class Descriptor(val instanceId: String, val minimumReader: Int)

    companion object {
        internal fun read(descriptor: ByteArray?, instance: ByteArray?): Descriptor {
            val root = WireValues.parse(PlainSyncPayload.decode(instance ?: WireValues.invalid()))
            val format = SyncFormatCodec.decode(descriptor?.let { PlainSyncPayload.decode(it) })
            SyncFormatCodec.requireCompatible(format)
            val version = root["format_version"]?.let { WireValues.unsigned(it, UInt.MAX_VALUE.toULong()) } ?: 3uL
            if (version !in 3uL..4uL) throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
            val id = WireValues.text(root["instance_id"]).trim()
            if (id.isEmpty() || id.toByteArray().size > 256 || id.any(Char::isISOControl)) WireValues.invalid()
            return Descriptor(id, SyncFormatCodec.minimumReader(format).toInt())
        }
    }
}
