package com.deepstudent.services.sync.data

import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.PendingPublication
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SqlUploadStateTest {
    private val root = Files.createTempDirectory("sync-publication-").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private fun database() = DatabaseFactory.openLocal(File(root, "publication.db"))

    @Test fun reservationReopensAndCompletionIsScopedByInstanceAndDevice() {
        val publication = PendingPublication("key", 1, 123, byteArrayOf(3, 4), listOf(1, 2))
        database().use { db ->
            val journal = SqlUploadState(db, "instance-a", "device-a")
            journal.reserve(publication)
            assertEquals(0, journal.published())
            assertNull(SqlUploadState(db, "instance-b", "device-a").pending())
            assertNull(SqlUploadState(db, "instance-a", "device-b").pending())
        }
        database().use { db ->
            val journal = SqlUploadState(db, "instance-a", "device-a")
            assertEquals("key", journal.pending()?.key)
            assertEquals(listOf(1L, 2L), journal.pending()?.changeLogIds)
            journal.complete(publication)
            assertEquals(1, journal.published())
            assertNull(journal.pending())
        }
    }

    @Test fun failedReadbackAndDomainCommitReuseExactReservedObject() = runBlocking {
        val remote = Provider()
        val domain = Domain()
        val codec = object : SyncCompression {
            override fun encode(bytes: ByteArray): ByteArray = bytes
            override fun decode(bytes: ByteArray): ByteArray = bytes
        }
        var reservedKey = ""
        database().use { db ->
            val journal = SqlUploadState(db, "instance", "device")
            remote.corrupt = true
            assertThrows(SyncFailure::class.java) { runBlocking { ChangeTransfer(remote, codec, journal, domain, "device").upload(3) } }
            reservedKey = journal.pending()!!.key
            assertEquals(0, journal.published())
            assertEquals(false, domain.marked)
        }
        remote.corrupt = false
        database().use { db ->
            val journal = SqlUploadState(db, "instance", "device")
            domain.fail = true
            assertThrows(IllegalStateException::class.java) { runBlocking { ChangeTransfer(remote, codec, journal, domain, "device").resumePending() } }
            assertNotNull(journal.pending())
            domain.fail = false
            ChangeTransfer(remote, codec, journal, domain, "device").resumePending()
            assertEquals(1, journal.published())
            assertNull(journal.pending())
            assertEquals(listOf(reservedKey), remote.objects.keys.toList())
            assertEquals(1, remote.puts)
        }
    }

    @Test fun corruptReservationFailsWithoutAcknowledgement() {
        database().use { db ->
            val journal = SqlUploadState(db, "instance", "device")
            journal.reserve(PendingPublication("key", 1, 123, byteArrayOf(1), listOf(1)))
            db.execute("UPDATE sync_pending_publication SET payload=?", listOf(SqlValue.Blob(byteArrayOf(2))))
            assertThrows(SyncFailure::class.java) { journal.pending() }
            assertEquals(0, journal.published())
        }
    }

    private class Provider : ObjectProvider {
        val objects = mutableMapOf<String, ByteArray>()
        var corrupt = false
        var puts = 0
        override suspend fun get(key: String): ByteArray? = objects[key]?.let { if (corrupt) byteArrayOf(0) else it.copyOf() }
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) { check(!ifAbsent || key !in objects); objects[key] = value.copyOf(); puts++ }
        override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) { objects.remove(key) }
    }

    private class Domain : DomainSyncAdapter {
        var fail = false
        var marked = false
        override fun pending(): List<ByteArray> = if (marked) emptyList() else listOf(
            """{"table_name":"notes","record_id":"n","operation":"Insert","data":{},"changed_at":"2026-11-18T00:00:00Z","change_log_id":1}""".toByteArray(),
        )
        override fun markPublished(changeLogIds: List<Long>, version: Long) { if (fail) error("public fixture failure"); marked = true }
        override fun cursor(device: String): Long = 0
        override fun apply(device: String, seq: Long, changes: List<ByteArray>) = Unit
    }
}
