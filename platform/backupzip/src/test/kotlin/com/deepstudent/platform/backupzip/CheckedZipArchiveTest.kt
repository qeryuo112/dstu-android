package com.deepstudent.platform.backupzip

import com.deepstudent.services.backup.api.ArchiveEntryKind
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CheckedZipArchiveTest {
    private fun zip(entries: List<Pair<String, Int>>, zip64: Boolean = false, body: (File) -> Unit) {
        val file = Files.createTempFile("dstu-zip-test", ".zip").toFile()
        try {
            ZipArchiveOutputStream(file).use { out ->
                if (zip64) out.setUseZip64(Zip64Mode.Always)
                entries.forEach { (name, mode) ->
                    val e = ZipArchiveEntry(name)
                    e.unixMode = mode
                    out.putArchiveEntry(e)
                    if (!name.endsWith("/")) out.write("test".toByteArray())
                    out.closeArchiveEntry()
                }
            }
            body(file)
        } finally { file.delete() }
    }

    @Test fun readsUtf8RegularFilesAndDirectories() = zip(listOf("目录/" to 0x41ED, "目录/a.txt" to 0x81A4)) { file ->
        CheckedZipArchive(file).use { archive ->
            assertEquals(listOf(ArchiveEntryKind.DIRECTORY, ArchiveEntryKind.FILE), archive.inspect().entries.map { it.kind })
            assertEquals("test", archive.open("目录/a.txt").bufferedReader().use { it.readText() })
        }
    }

    @Test fun identifiesUnixSymlinkAndNeverOpensIt() = zip(listOf("link" to 0xA1FF)) { file ->
        CheckedZipArchive(file).use { archive ->
            assertEquals(ArchiveEntryKind.SYMBOLIC_LINK, archive.inspect().entries.single().kind)
            assertThrows(Exception::class.java) { archive.open("link") }
        }
    }

    @Test fun identifiesUnixSpecialFile() = zip(listOf("pipe" to 0x11A4)) { file ->
        CheckedZipArchive(file).use { archive ->
            assertEquals(ArchiveEntryKind.OTHER, archive.inspect().entries.single().kind)
            assertThrows(Exception::class.java) { archive.open("pipe") }
        }
    }

    @Test fun rejectsDuplicateAndTraversalNames() {
        for (entries in listOf(listOf("same" to 0x81A4, "same" to 0x81A4), listOf("../escape" to 0x81A4))) {
            zip(entries) { file -> CheckedZipArchive(file).use { archive -> assertThrows(Exception::class.java) { archive.inspect() } } }
        }
    }

    @Test fun rejectsBackslashName() = zip(listOf("a!b" to 0x81A4)) { file ->
        val bytes = file.readBytes()
        for (i in 1 until bytes.size - 1) {
            if (bytes[i - 1] == 'a'.code.toByte() && bytes[i] == '!'.code.toByte() && bytes[i + 1] == 'b'.code.toByte()) bytes[i] = 92
        }
        file.writeBytes(bytes)
        CheckedZipArchive(file).use { archive -> assertThrows(Exception::class.java) { archive.inspect() } }
    }

    @Test fun rejectsInvalidUtf8Filename() = zip(listOf("a!b" to 0x81A4)) { file ->
        val bytes = file.readBytes()
        for (i in 1 until bytes.size - 1) {
            if (bytes[i - 1] == 'a'.code.toByte() && bytes[i] == '!'.code.toByte() && bytes[i + 1] == 'b'.code.toByte()) bytes[i] = 0xff.toByte()
        }
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) { CheckedZipArchive(file).use { it.inspect() } }
    }

    @Test fun readsValidBoundedZip64Directory() = zip(listOf("last" to 0x81A4), zip64 = true) { file ->
        CheckedZipArchive(file).use { assertEquals("last", it.inspect().entries.single().path) }
    }

    @Test fun rejectsUnderstatedDirectoryCountBeforeOpeningZip() = zip(listOf("last" to 0x81A4)) { file ->
        val bytes = file.readBytes()
        val end = bytes.size - 22
        for (offset in 8..11) bytes[end + offset] = 0
        file.writeBytes(bytes)
        assertThrows(java.io.IOException::class.java) { CheckedZipArchive(file).close() }
    }

    @Test fun refusesSymlinkArchiveFile() = zip(listOf("a" to 0x81A4)) { file ->
        val link = File(file.parentFile, file.name + ".link")
        try {
            Files.createSymbolicLink(link.toPath(), file.toPath())
            assertThrows(Exception::class.java) { CheckedZipArchive(link) }
        } finally { Files.deleteIfExists(link.toPath()) }
    }
}
