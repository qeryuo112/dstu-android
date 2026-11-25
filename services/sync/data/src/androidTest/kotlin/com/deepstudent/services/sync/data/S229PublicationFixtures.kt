package com.deepstudent.services.sync.data

import android.content.Context
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.PublicationOptions
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.changelog.SqlPublicationSet
import com.deepstudent.services.sync.data.changelog.SqlPublicationSource
import com.deepstudent.services.sync.data.changelog.SqlRepositorySeed
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.util.UUID

/** App-private, disposable migrated databases. No credentials or real remote account are used. */
internal class S229PublicationFixture(context: Context) {
    private val root = File(context.cacheDir, "s229-publication-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val migrations = BundledMigrationSource(context)
    val registry: SyncCaptureRegistry by lazy { SyncCaptureRegistry.load(migrations) }

    fun open(owner: SharedDatabase): SqlDatabase = DatabaseRegistry.openShared(root.resolve("${owner.id}.db"), owner, migrations)

    suspend fun <T> withFourOwners(block: suspend (Map<SharedDatabase, SqlDatabase>) -> T): T {
        val owners = linkedMapOf<SharedDatabase, SqlDatabase>()
        try {
            SharedDatabase.entries.forEach { owners[it] = open(it) }
            return block(owners)
        } finally {
            owners.values.toList().asReversed().forEach(SqlDatabase::close)
        }
    }

    fun sources(owners: Map<SharedDatabase, SqlDatabase>): List<SqlPublicationSource> = SharedDatabase.entries.map {
        source(owners.getValue(it), it)
    }

    fun source(db: SqlDatabase, owner: SharedDatabase, check: () -> Unit = {}): SqlPublicationSource =
        SqlPublicationSource(db, owner, registry.rowSyncKeys(owner), check)

    fun journal(vfs: SqlDatabase): SqlUploadState = SqlUploadState(vfs, INSTANCE, DEVICE)

    fun transfer(
        provider: S229MemoryProvider,
        journal: SqlUploadState,
        sources: List<SqlPublicationSource>,
        seed: Boolean = false,
    ): ChangeTransfer = ChangeTransfer(
        provider,
        IdentityCompression,
        journal,
        SqlPublicationSet(sources, NoIncoming),
        DEVICE,
        publication = PublicationOptions(if (seed) SqlRepositorySeed(sources) else null),
    )

    fun close() {
        check(root.deleteRecursively()) { "failed to remove S2.29 test sandbox" }
    }

    companion object {
        const val DEVICE = "s229-fixture-writer"
        private const val INSTANCE = "s229-fixture-repository"
    }
}

internal class S229MemoryProvider : ObjectProvider {
    val objects = linkedMapOf<String, ByteArray>()
    var puts = 0
    var afterPut: suspend () -> Unit = {}
    var cancelAfterPut = false

    override suspend fun get(key: String): ByteArray? = objects[key]?.copyOf()

    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
        check(!ifAbsent || key !in objects)
        objects[key] = value.copyOf()
        puts++
        afterPut()
        if (cancelAfterPut) currentCoroutineContext().cancel(CancellationException("s229 fixture cancellation"))
    }

    override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }

    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    fun onlyBatch(): SyncBatch = SyncBatchCodec.decode(objects.values.single())
}

private object IdentityCompression : SyncCompression {
    override fun encode(bytes: ByteArray): ByteArray = bytes
    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private object NoIncoming : DomainSyncAdapter {
    override fun pending(): List<ByteArray> = emptyList()
    override fun markPublished(changeLogIds: List<Long>, version: Long): Unit = error("must acknowledge the owning database")
    override fun cursor(device: String): Long = 0
    override fun apply(device: String, seq: Long, changes: List<ByteArray>): Unit = error("outgoing publication only")
}

internal fun s229Text(row: JsonValue.Object, key: String): String = (row[key] as JsonValue.Text).value

internal fun s229Resource(db: SqlDatabase, id: String) {
    db.execute(
        "INSERT INTO resources(id,hash,type,created_at,updated_at) VALUES(?,?,'note',1,1)",
        listOf(SqlValue.Text(id), SqlValue.Text(id)),
    )
}

internal fun s229Template(db: SqlDatabase, id: String, builtIn: Long, modified: Long, deleted: Long = 0) {
    db.execute(
        "INSERT INTO custom_anki_templates(id,name,preview_front,preview_back,generation_prompt," +
            "front_template,back_template,css_style,is_built_in,user_modified,user_deleted) " +
            "VALUES(?,?,'f','b','p','f','b','c',?,?,?)",
        listOf(SqlValue.Text(id), SqlValue.Text(id), SqlValue.Integer(builtIn), SqlValue.Integer(modified), SqlValue.Integer(deleted)),
    )
}

internal fun s229ConfirmAll(db: SqlDatabase) = db.execute("UPDATE __change_log SET sync_version=6")

internal fun s229Version(db: SqlDatabase, table: String, id: String): Long = db.query(
    "SELECT COALESCE(sync_version,0) FROM __change_log WHERE table_name=? AND record_id=? ORDER BY id DESC LIMIT 1",
    listOf(SqlValue.Text(table), SqlValue.Text(id)),
) { it.long(0) }.single()

internal fun s229LogCount(db: SqlDatabase, table: String, id: String): Long = db.query(
    "SELECT COUNT(*) FROM __change_log WHERE table_name=? AND record_id=?",
    listOf(SqlValue.Text(table), SqlValue.Text(id)),
) { it.long(0) }.single()

internal fun s229SelectLog(db: SqlDatabase, table: String, id: String, logId: Long) {
    db.execute("DELETE FROM __change_log WHERE table_name<>? OR record_id<>?", listOf(SqlValue.Text(table), SqlValue.Text(id)))
    db.execute("UPDATE __change_log SET id=?", listOf(SqlValue.Integer(logId)))
}
