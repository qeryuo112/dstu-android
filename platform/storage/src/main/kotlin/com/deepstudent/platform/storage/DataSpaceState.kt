package com.deepstudent.platform.storage

import java.security.MessageDigest

/** Pure, strictly encoded state; the filesystem adapter owns all recovery decisions. */
internal object DataSpaceState {
    private val slotPattern = Regex("slot-[0-9a-f]{64}")
    private val operationPattern = Regex("[A-Za-z0-9._:-]{1,128}")
    private const val MAGIC = "DSTU-DATASPACE-1"
    const val LEGACY = "legacy"
    const val INITIALIZED = "DSTU-INITIALIZED-1\n"
    const val COMMITTED = "DSTU-COMMITTED-1\n"

    fun slotFor(operationId: String): String {
        if (!operationPattern.matches(operationId)) throw DataSpaceException(DataSpaceError.INVALID_OPERATION_ID)
        return "slot-${digest(operationId.toByteArray(Charsets.UTF_8))}"
    }

    fun validSlot(slot: String): Boolean = slot == LEGACY || slotPattern.matches(slot)
    fun validPhysicalSlot(slot: String): Boolean = slotPattern.matches(slot)

    fun encode(slot: String): ByteArray {
        if (!validSlot(slot)) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        val body = "$MAGIC\n$slot\n"
        return "$body${digest(body.toByteArray(Charsets.US_ASCII))}\n".toByteArray(Charsets.US_ASCII)
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size > 256) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        val text = String(bytes, Charsets.US_ASCII)
        val lines = text.split('\n')
        if (lines.size != 4 || lines[0] != MAGIC || lines[3].isNotEmpty()) corrupt()
        val slot = lines[1]
        if (!validSlot(slot) || !bytes.contentEquals(encode(slot))) corrupt()
        return slot
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun corrupt(): Nothing = throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
}
