package com.deepstudent.services.resources.backend

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.resources.api.AttachmentContent
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.CreateAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentServiceTest {
    @Test fun bytesAreSnapshottedOnInputAndOutput() {
        val original = byteArrayOf(1, 2, 3)
        val input = CreateAttachment("a.txt", "text/plain", original)
        original[0] = 9
        val returned = input.bytes
        returned[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), input.bytes)
    }

    @Test fun rejectsInvalidInputAndCooperativeCancellation() {
        val repository = FakeAttachments()
        val service = AttachmentService(repository)
        assertThrows(DstuException::class.java) {
            runBlocking { service.create(CreateAttachment("", "text/plain", byteArrayOf())) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancel()
                service.create(CreateAttachment("a.txt", "text/plain", byteArrayOf()))
            }
        }
        assertEquals(0, repository.writes)
    }

    @Test fun failureLogsExcludeUserTextAndExceptionMessages() {
        val records = mutableListOf<LogRecord>()
        val service = AttachmentService(FakeAttachments(), object : Logger {
            override fun log(record: LogRecord) { records.add(record) }
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.create(CreateAttachment("secret-name", "text/plain", "secret-body".toByteArray())) }
        }
        assertEquals(1, records.size)
        val record = records.single().toString()
        org.junit.Assert.assertFalse(record.contains("secret"))
        org.junit.Assert.assertFalse(record.contains("unexpected"))
        org.junit.Assert.assertTrue(record.contains("resources.operation"))
    }
}

private class FakeAttachments : AttachmentRepository {
    var writes = 0
    override suspend fun create(command: CreateAttachment): AttachmentMetadata { writes++; error("unexpected") }
    override suspend fun get(id: String, includeDeleted: Boolean): AttachmentContent? = null
    override suspend fun resolve(resourceId: ResourceId): AttachmentMetadata? = null
    override suspend fun rename(id: String, name: String): AttachmentMetadata = error("unexpected")
    override suspend fun delete(id: String): AttachmentMetadata = error("unexpected")
    override suspend fun restore(id: String): AttachmentMetadata = error("unexpected")
    override suspend fun verify(id: String): AttachmentVerification = AttachmentVerification.NOT_FOUND
}
