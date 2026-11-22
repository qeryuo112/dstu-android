package com.deepstudent.platform.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DataSpaceProcessLockTest {
    @Test fun operatingSystemLockExcludesOtherProcessesAndRecoversOnExit() {
        val root = Files.createTempDirectory("s209-cross-process").toFile()
        try {
            val store = FileDataSpace(root)
            store.acquireActive().close()
            val lock = File(root, ".dataspace/lock")
            val script = """
                import fcntl, sys
                f = open(sys.argv[1], 'r+')
                fcntl.lockf(f, fcntl.LOCK_EX)
                print('locked', flush=True)
                sys.stdin.readline()
            """.trimIndent()
            val process = ProcessBuilder("python3", "-u", "-c", script, lock.absolutePath).start()
            try {
                assertEquals("locked", process.inputStream.bufferedReader().readLine())
                assertBusy { store.acquireActive() }
                assertBusy { store.withMaintenance { } }
            } finally {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            }
            store.acquireActive().use { assertTrue(it.root.isDirectory) }
            store.withMaintenance { assertTrue(active.isDirectory) }
            assertEquals(0, DataSpaceLocks.sizeForTest())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertBusy(block: () -> Unit) {
        try { block(); fail("expected BUSY") } catch (failure: DataSpaceException) {
            assertEquals(DataSpaceError.BUSY, failure.error)
        }
    }
}
