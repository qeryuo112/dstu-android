package com.deepstudent.services.sync.data.s230

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransferPhase
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.backend.transfer.BlobTransfer
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.SyncBlob
import com.deepstudent.services.sync.backend.transfer.SyncBlobs
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.backend.transfer.SyncPipeline
import com.deepstudent.services.sync.backend.transfer.SyncUploadState
import com.deepstudent.services.sync.data.changelog.SqlPublicationSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Executes the real wire decoder and pipeline with real migrated databases and an in-memory transport. */
class S230PipelineReplayTest {
    private val root = Files.createTempDirectory("s230-pipeline-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun downloadedPartialCommitIsRetriedFromSameWireObject() = runBlocking {
        val logger = S230RecordingLogger()
        S230MigratedFixture(root, s230HostMigrationSource(), logger).use { f ->
            val sender = "remote-device"
            val wireRows = s230Batch().map { JsonValue.parseObject(it.decodeToString()) }
            val payload = SyncBatchCodec.encode(SyncBatch(wireRows, sender, 3u, 1uL, sender, null))
            val key = SyncWireCodec.changeKey(sender, 1uL, 1uL, "s230", false)
            val provider = S230MemoryProvider().apply { objects[key] = payload }
            val pipeline = SyncPipeline(
                ChangeTransfer(
                    provider,
                    S230IdentityCompression,
                    S230MemoryUploadState(),
                    SqlPublicationSet(emptyList(), f.candidate),
                    "local-device",
                ),
                BlobTransfer(provider, S230EmptyBlobs),
                { 3 },
                logger = logger,
            )
            val cursorDevice = SyncWireCodec.deviceHash(sender)
            f.hook = { owner, _ -> if (owner == SharedDatabase.VFS) error("injected primary failure") }
            var failed = false
            try {
                pipeline.synchronize()
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(SyncTransferPhase.FAILED, pipeline.state.value.phase)
            assertEquals(0L, f.candidate.cursor(cursorDevice))
            val firstCorrelation = pipeline.state.value.correlationId
            val firstEvents = logger.records.filter { it.event.startsWith("sync.apply.") }
            assertTrue(firstEvents.isNotEmpty())
            assertTrue(firstEvents.all { it.correlationId?.value == firstCorrelation })
            assertEquals("FAILED", firstEvents.last().field("status"))
            assertEquals("sync.apply.consume", firstEvents.last().event)
            val chat = f.db(SharedDatabase.CHAT_V2)
            assertEquals(1L, chat.query("SELECT COUNT(*) FROM __android_sync_apply_receipts") { it.long(0) }.single())
            f.hook = { _, _ -> }
            assertEquals(SyncResult.Success(Unit), pipeline.synchronize())
            assertEquals(SyncTransferPhase.COMPLETE, pipeline.state.value.phase)
            assertEquals(1L, f.candidate.cursor(cursorDevice))
            val secondCorrelation = pipeline.state.value.correlationId
            val retryEvents = logger.records.filter { it.event.startsWith("sync.apply.") && it.correlationId?.value == secondCorrelation }
            assertEquals(listOf("REPLAYED", "REPLAYED", "REPLAYED", "COMMITTED"), retryEvents.map { it.field("status") })
            assertEquals(listOf("APPLY", "APPLY", "APPLY", "CONSUME"), retryEvents.map { it.field("stage") })
            assertTrue(retryEvents.all { it.field("phase") == "DOWNLOAD" && it.fields["elapsedMs"] is JsonValue.Number })
            assertEquals(
                0L,
                f.db(SharedDatabase.MISTAKES).query("SELECT local_version FROM anki_decks WHERE id='deck'") {
                    it.long(0)
                }.single(),
            )
            assertEquals(
                9007199254740993L,
                f.db(SharedDatabase.LLM_USAGE).query("SELECT prompt_tokens FROM llm_usage_logs WHERE id='usage'") { it.long(0) }.single(),
            )
        }
    }
}

private class S230MemoryProvider : ObjectProvider {
    val objects = mutableMapOf<String, ByteArray>()
    override suspend fun get(key: String): ByteArray? = objects[key]?.copyOf()
    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
        objects[key] = value.copyOf()
    }
    override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }
    override suspend fun delete(key: String) {
        objects.remove(key)
    }
}

private object S230IdentityCompression : SyncCompression {
    override fun encode(bytes: ByteArray): ByteArray = bytes
    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private class S230MemoryUploadState : SyncUploadState {
    private var seq = 0L
    override fun published(): Long = seq
    override fun markPublished(seq: Long) {
        this.seq = seq
    }
}

private object S230EmptyBlobs : SyncBlobs {
    override fun list(): List<SyncBlob> = emptyList()
    override fun read(blob: SyncBlob): ByteArray? = null
    override fun install(blob: SyncBlob, bytes: ByteArray) = error("unexpected blob")
}

private class S230RecordingLogger : Logger {
    val records = mutableListOf<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

private fun LogRecord.field(name: String): String? = (fields[name] as? JsonValue.Text)?.value
