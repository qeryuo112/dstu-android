package com.deepstudent.app.runtime

import java.nio.file.Files
import java.nio.channels.OverlappingFileLockException
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeProcessLockTest {
    @Test fun exclusiveOwnerMustCloseBeforeAnotherOwnerAcquires() {
        val directory = Files.createTempDirectory("runtime-owner-test").toFile()
        try {
            var owner = ""
            RuntimeProcessLock.acquire(directory).use { first ->
                owner = first.ownerId
                assertThrows(OverlappingFileLockException::class.java) { RuntimeProcessLock.acquire(directory) }
            }
            RuntimeProcessLock.acquire(directory).use { second -> assertNotEquals(owner, second.ownerId) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
