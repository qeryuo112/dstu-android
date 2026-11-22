package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.backend.transfer.SyncUploadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTest {
    private val change = """{"table_name":"notes","record_id":"n","operation":"Insert","data":{},"changed_at":"2026-01-01T00:00:00Z","change_log_id":4}""".toByteArray()
    private class Provider : ObjectProvider {
        val objects = mutableMapOf<String, ByteArray>()
        var corrupt = false
        override suspend fun get(key: String): ByteArray? = objects[key]?.let { if (corrupt) byteArrayOf(0) else it }
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) { objects[key] = value }
        override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) { objects.remove(key) }
    }
    private class Domain : DomainSyncAdapter {
        var list = emptyList<ByteArray>()
        var marked = false
        var cursor = 0L
        override fun pending() = list
        override fun markPublished(changeLogIds: List<Long>, version: Long) { marked = true }
        override fun cursor(device: String) = cursor
        override fun apply(device: String, seq: Long, changes: List<ByteArray>) { cursor = seq; list = changes }
    }
    private class State : SyncUploadState {
        var seq = 0L
        override fun published() = seq
        override fun markPublished(seq: Long) { this.seq = seq }
    }
    private val compression = object : SyncCompression {
        override fun encode(bytes: ByteArray) = bytes
        override fun decode(bytes: ByteArray) = bytes
    }

    @Test fun uploadReadbackAndDownload() = runBlocking {
        val provider = Provider()
        val a = Domain().apply { list = listOf(change) }
        ChangeTransfer(provider, compression, State(), a, "sender").upload(3)
        assertTrue(a.marked)
        val b = Domain()
        ChangeTransfer(provider, compression, State(), b, "receiver").download()
        assertEquals(1, b.cursor)
        assertEquals(1, b.list.size)
    }

    @Test fun failedReadbackNeverMarksPublished() {
        val provider = Provider().apply { corrupt = true }
        val a = Domain().apply { list = listOf(change) }
        val state = State()
        val failure = assertThrows(SyncFailure::class.java) {
            runBlocking { ChangeTransfer(provider, compression, state, a, "sender").upload(3) }
        }
        assertEquals(com.deepstudent.services.sync.api.SyncError.INVALID_REPOSITORY, failure.error)
        assertEquals(0, state.seq)
        assertEquals(false, a.marked)
    }
}
