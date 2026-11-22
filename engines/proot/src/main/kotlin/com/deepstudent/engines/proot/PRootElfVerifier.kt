package com.deepstudent.engines.proot

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PRootElfVerifier {
    fun requireAndroidAarch64Pie(file: File) {
        val bytes = file.readBytes()
        require(bytes.size >= ELF_HEADER_BYTES) { "PRoot binary is not ELF64" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "PRoot binary is not ELF"
        }
        require(bytes[4].toInt() == 2 && bytes[5].toInt() == 1) { "PRoot binary is not ELF64 LE" }
        require(header.getShort(16).toInt() == 3) { "PRoot binary is not ET_DYN" }
        require(header.getShort(18).toInt() == 183) { "PRoot binary is not AArch64" }
        verifyLoadAlignment(header, bytes.size)
    }

    private fun verifyLoadAlignment(header: ByteBuffer, fileBytes: Int) {
        val programOffset = header.getLong(32)
        val entryBytes = header.getShort(54).toInt()
        val entryCount = header.getShort(56).toInt()
        require(entryBytes >= 56 && entryCount in 1..128) { "PRoot has invalid program headers" }
        val tableBytes = entryBytes.toLong() * entryCount
        require(programOffset >= 0 && programOffset + tableBytes <= fileBytes) {
            "PRoot program header table is truncated"
        }
        var loads = 0
        for (index in 0 until entryCount) {
            val offset = programOffset.toInt() + index * entryBytes
            if (header.getInt(offset) != 1) continue
            loads++
            val virtualAddress = header.getLong(offset + 16)
            val fileOffset = header.getLong(offset + 8)
            val align = header.getLong(offset + 48)
            require(align >= PAGE_16K) { "PRoot LOAD segment is not 16KiB aligned" }
            require((virtualAddress - fileOffset) and (align - 1) == 0L) {
                "PRoot LOAD segment congruence is invalid"
            }
        }
        require(loads > 0) { "PRoot binary has no LOAD segment" }
    }

    private companion object {
        const val ELF_HEADER_BYTES = 64
        const val PAGE_16K = 16_384L
    }
}
