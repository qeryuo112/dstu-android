package com.deepstudent.platform.security

private const val BACKUP_LIMIT = 10L * 1024 * 1024 * 1024

internal fun proofHeader(bytes: ByteArray, length: Long): DsbkParsedHeader = DsbkPreflightReader.read(
    bytes.inputStream(), length, BACKUP_LIMIT, BACKUP_LIMIT,
    DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB, DsbkCrypto.PRODUCTION_MAX_CHUNK_BYTES,
)

internal fun passwordProofLength(header: DsbkParsedHeader): Int {
    val size = checkNotNull(header.info.containerBytes)
    val v2 = header.v2 ?: return size.toInt()
    val first = DsbkFormat.V2_HEADER_LEN + v2.chunkSize + DsbkFormat.GCM_TAG_LEN
    return minOf(size, first.toLong()).toInt()
}

internal class DiscardProofPlaintext : DsbkSink {
    override var stagedBytes: Long = 0
        private set
    override fun write(bytes: ByteArray, offset: Int, length: Int) { stagedBytes += length }
    override fun commit() = Unit
    override fun abort() { stagedBytes = 0 }
}
