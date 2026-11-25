package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Values, not just field names, must be allowlisted: an ID, path or credential may be any text. */
internal enum class S231Database { VFS, CHAT_V2, MISTAKES, LLM_USAGE }
internal enum class S231Owner {
    DATABASE,
    RUNTIME,
    RUNTIME_RECOVERY,
    SYNC,
    SYNC_APPLY,
    SYNC_CONFIGURATION,
    SYNC_CLAIM,
    BACKUP,
    NOTES,
    RESOURCES,
    SECURITY,
}
internal enum class S231Stage {
    REGISTRY,
    SOURCE,
    OPEN,
    INSPECT,
    MIGRATION,
    GOVERNANCE,
    WAL,
    READY,
    READ,
    WRITE,
    CLOSE,
    IDLE,
    BLOBS,
    DOWNLOAD,
    UPLOAD,
    APPLY,
    CONSUME,
    COMPLETE,
    FAILED,
    CANCELLED,
    RESERVE,
    REPLAY,
    READBACK,
    ACKNOWLEDGE,
    CLAIM,
    OUTBOX,
    LOOKUP,
    RECONCILE,
    DECIDE,
    RELEASE,
    PUBLISH,
    PREFLIGHT,
    PREPARE,
    DATABASE,
    CLEANUP,
    ACQUIRE,
    SNAPSHOT,
    EXTRACT,
    VERIFY_DATABASES,
    VERIFY_RESOURCES,
    ACTIVATE,
    CREATE,
    GET,
    EDIT,
    DELETE,
    CRYPTO,
    HEADER,
    DISPATCH,
    V1_DECRYPT,
    V2_DECRYPT,
    COMMIT,
    KEY_PROBE,
    RESET_RECHECK,
    RESET_APPLY,
}
internal enum class S231Status { STARTED, RUNNING, SUCCESS, COMMITTED, REPLAYED, FAILED, CANCELLED, TIMED_OUT }
internal enum class S231State {
    CREATED,
    STARTED,
    RUNNING,
    WAITING_APPROVAL,
    PAUSED,
    COMPLETED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}
internal enum class S231Outcome { APPLIED, SKIPPED, BLOCKED, FAILED }

/** Only these closed values, and per-snapshot numeric aliases, survive projection. */
internal data class S231Location(val owner: S231Owner?, val database: S231Database?, val stage: S231Stage?, val phase: S231Stage?)

internal data class S231Progress(val status: S231Status?, val state: S231State?, val outcome: S231Outcome?, val durationMillis: Long?)

internal enum class S231Event {
    SYNC_TRANSFER_PHASE,
    SYNC_TRANSFER_FINISHED,
    SYNC_TRANSFER_UNEXPECTED,
    SYNC_PUBLICATION,
    SYNC_APPLY_OWNER,
    SYNC_APPLY_CONSUME,
    SYNC_CONFIGURATION_STARTED,
    SYNC_CONFIGURATION_FINISHED,
    SYNC_CONFIGURATION_CANCELLED,
    RUNTIME_RECOVERY,
    DATABASE_MIGRATION,
    DATABASE_OPERATION,
    RUNTIME_FINISHED,
    NOTES_OPERATION,
    RESOURCES_OPERATION,
    BACKUP_RESTORE,
    SECURITY_CRYPTO,
}

internal data class S231Entry(
    val level: LogLevel,
    val event: S231Event?,
    val library: Int?,
    val task: Int?,
    val correlation: Int?,
    val location: S231Location,
    val progress: S231Progress,
    val error: S231ErrorCode?,
)

internal sealed interface S231CaptureResult {
    data class Ready(val snapshot: S231Snapshot) : S231CaptureResult
    data class Refused(val error: DstuError) : S231CaptureResult
}

/** An immutable, bounded projection. It never holds the source records or their original IDs. */
internal class S231Snapshot internal constructor(entries: List<S231Entry>) {
    val entries: List<S231Entry> = Collections.unmodifiableList(ArrayList(entries))

    companion object {
        const val MAX_RECORDS = 128

        /**
         * Only in-memory structured records explicitly supplied by a caller are considered.
         * No files, logcat, exception messages, arbitrary event names, or payloads are read.
         * Callers must supply a stable list and must not mutate it while capture is in progress.
         */
        fun capture(records: List<LogRecord>): S231CaptureResult {
            val count = records.size
            if (count > MAX_RECORDS) return S231CaptureResult.Refused(S231ErrorCode.S231_LIMIT_EXCEEDED.asDstuError())
            val projection = S231Projection()
            val safe = ArrayList<S231Entry>(count)
            for (index in 0 until count) safe += projection.entry(records[index])
            // The temporary alias maps die here; no raw identifier leaves the projection.
            return S231CaptureResult.Ready(S231Snapshot(safe))
        }
    }
}

/** Store only keyed digests as alias keys: raw task/library identifiers never enter the buffer. */
internal class S231Projection(initial: List<S231Entry> = emptyList()) {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val tasks = AliasTable(key, initial.maxOfOrNull { it.task ?: 0 } ?: 0)
    private val correlations = AliasTable(key, initial.maxOfOrNull { it.correlation ?: 0 } ?: 0)
    private val libraries = AliasTable(key, initial.maxOfOrNull { it.library ?: 0 } ?: 0)

    fun entry(record: LogRecord): S231Entry {
        val owner = owner(record.fields.text("owner"))
        val correlation = record.correlationId?.value ?: record.fields.correlationId()
        val event = safeEvent(record.event)
        val sourceError = record.error
        return S231Entry(
            level = record.level,
            event = event,
            library = (record.fields.identifier("libraryId") ?: record.fields.identifier("repositoryId"))?.let(libraries::alias),
            task = record.taskId?.value?.let(tasks::alias),
            correlation = correlation?.let(correlations::alias),
            location = location(record.fields, owner),
            progress = progress(record.fields),
            error = if (sourceError != null) {
                if (event in syncEvents && owner in syncOwners) {
                    S231ErrorCode.from(sourceError)
                } else {
                    S231ErrorCode.domain(event, owner, sourceError.code)
                }
            } else {
                syncError(record.fields, owner, event)
            },
        )
    }
}

/** The ring contains at most 128 entries; older digest keys can be safely evicted. */
private class AliasTable(private val key: ByteArray, last: Int) {
    private val aliases = LinkedHashMap<String, Int>(1025, 0.75f, true)
    private var next = if (last == Int.MAX_VALUE) Int.MAX_VALUE else last + 1

    fun alias(value: String): Int? {
        if (value.isEmpty() || value.length > 128) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        aliases[digest]?.let { return it }
        if (next == Int.MAX_VALUE) return null
        if (aliases.size >= 1024) aliases.remove(aliases.keys.first())
        return next++.also { aliases[digest] = it }
    }
}

private val safeEvents = mapOf(
    "sync.transfer.phase" to S231Event.SYNC_TRANSFER_PHASE,
    "sync.transfer.finished" to S231Event.SYNC_TRANSFER_FINISHED,
    "sync.transfer.unexpected" to S231Event.SYNC_TRANSFER_UNEXPECTED,
    "sync.publication" to S231Event.SYNC_PUBLICATION,
    "sync.apply.owner" to S231Event.SYNC_APPLY_OWNER,
    "sync.apply.consume" to S231Event.SYNC_APPLY_CONSUME,
    "sync.configuration.started" to S231Event.SYNC_CONFIGURATION_STARTED,
    "sync.configuration.finished" to S231Event.SYNC_CONFIGURATION_FINISHED,
    "sync.configuration.cancelled" to S231Event.SYNC_CONFIGURATION_CANCELLED,
    "runtime.recovery" to S231Event.RUNTIME_RECOVERY,
    "runtime.finished" to S231Event.RUNTIME_FINISHED,
    "notes.operation" to S231Event.NOTES_OPERATION,
    "resources.operation" to S231Event.RESOURCES_OPERATION,
    "backup.restore" to S231Event.BACKUP_RESTORE,
    "security.crypto" to S231Event.SECURITY_CRYPTO,
    "database.migration" to S231Event.DATABASE_MIGRATION,
    "database.operation" to S231Event.DATABASE_OPERATION,
)

private val syncEvents = setOf(
    S231Event.SYNC_TRANSFER_PHASE,
    S231Event.SYNC_TRANSFER_FINISHED,
    S231Event.SYNC_TRANSFER_UNEXPECTED,
    S231Event.SYNC_CONFIGURATION_STARTED,
    S231Event.SYNC_CONFIGURATION_FINISHED,
    S231Event.SYNC_CONFIGURATION_CANCELLED,
)
private val syncOwners = setOf(S231Owner.SYNC, S231Owner.SYNC_CONFIGURATION)
private fun safeEvent(value: String): S231Event? = safeEvents[value]

private fun location(fields: JsonValue.Object, owner: S231Owner?) = S231Location(
    owner = owner,
    database = database(fields.text("database")),
    stage = stage(fields.text("stage")),
    phase = stage(fields.text("phase")),
)

private fun progress(fields: JsonValue.Object) = S231Progress(
    status = status(fields.text("status")),
    state = state(fields.text("state")),
    outcome = outcome(fields.text("outcome")),
    durationMillis = duration(fields),
)

private fun syncError(fields: JsonValue.Object, owner: S231Owner?, event: S231Event?): S231ErrorCode? {
    if (owner !in syncOwners || event !in syncEvents) return null
    val error = fields.text("error")
    if (error != null && error != "none") return S231ErrorCode.known(error) ?: S231ErrorCode.OTHER
    return syncStatusError(fields.text("status"))
}

private fun syncStatusError(value: String?): S231ErrorCode? {
    if (value == null || value == "none") return null
    S231ErrorCode.known(value)?.let { return it }
    return if (status(value) != null || state(value) != null) null else S231ErrorCode.OTHER
}

// Do not normalise, truncate or include unknown strings: a prefix can still contain a secret.
private fun JsonValue.Object.text(key: String): String? {
    val value = (this[key] as? JsonValue.Text)?.value ?: return null
    return value.takeIf { it.length <= 32 }
}

private fun JsonValue.Object.identifier(key: String): String? {
    val value = (this[key] as? JsonValue.Text)?.value ?: return null
    return value.takeIf { it.isNotEmpty() && it.length <= 128 }
}

private fun JsonValue.Object.correlationId(): String? = identifier("correlationId")

private fun database(value: String?): S231Database? = when (value) {
    "vfs" -> S231Database.VFS
    "chat_v2" -> S231Database.CHAT_V2
    "mistakes" -> S231Database.MISTAKES
    "llm_usage" -> S231Database.LLM_USAGE
    else -> null
}

private val owners = mapOf(
    "database" to S231Owner.DATABASE,
    "platform/database" to S231Owner.DATABASE,
    "runtime" to S231Owner.RUNTIME,
    "runtime.recovery" to S231Owner.RUNTIME_RECOVERY,
    "sync" to S231Owner.SYNC,
    "sync/apply" to S231Owner.SYNC_APPLY,
    "sync.configuration" to S231Owner.SYNC_CONFIGURATION,
    "sync.continuity_claim" to S231Owner.SYNC_CLAIM,
    "backup" to S231Owner.BACKUP,
    "services.backup.restore" to S231Owner.BACKUP,
    "features/notes" to S231Owner.NOTES,
    "services/resources" to S231Owner.RESOURCES,
    "platform/security" to S231Owner.SECURITY,
    "platform.security" to S231Owner.SECURITY,
)

private fun owner(value: String?): S231Owner? = owners[value]

private fun stage(value: String?): S231Stage? = when (value) {
    "create" -> S231Stage.CREATE
    "get" -> S231Stage.GET
    "edit" -> S231Stage.EDIT
    "delete" -> S231Stage.DELETE
    else -> S231Stage.entries.firstOrNull { it.name == value }
}

private val statuses = mapOf(
    "STARTED" to S231Status.STARTED, "started" to S231Status.STARTED,
    "RUNNING" to S231Status.RUNNING, "running" to S231Status.RUNNING,
    "COMMITTED" to S231Status.COMMITTED, "REPLAYED" to S231Status.REPLAYED,
    "SUCCESS" to S231Status.SUCCESS, "PASSED" to S231Status.SUCCESS, "success" to S231Status.SUCCESS, "ok" to S231Status.SUCCESS,
    "FAILED" to S231Status.FAILED, "failed" to S231Status.FAILED,
    "CANCELLED" to S231Status.CANCELLED, "cancelled" to S231Status.CANCELLED,
    "TIMED_OUT" to S231Status.TIMED_OUT, "timed_out" to S231Status.TIMED_OUT,
)
private val states = mapOf(
    "created" to S231State.CREATED, "started" to S231State.STARTED, "running" to S231State.RUNNING,
    "waiting_approval" to S231State.WAITING_APPROVAL, "paused" to S231State.PAUSED,
    "completed" to S231State.COMPLETED, "succeeded" to S231State.SUCCEEDED, "failed" to S231State.FAILED,
    "cancelled" to S231State.CANCELLED, "interrupted" to S231State.INTERRUPTED,
)
private fun status(value: String?): S231Status? = statuses[value]
private fun state(value: String?): S231State? = states[value]
private fun outcome(value: String?): S231Outcome? = S231Outcome.entries.firstOrNull { it.name == value }

private fun duration(fields: JsonValue.Object): Long? {
    val value = fields["durationMillis"] ?: fields["duration_ms"] ?: fields["elapsedMs"]
    val literal = (value as? JsonValue.Number)?.literal ?: return null
    if (literal.length > 8) return null
    return literal.toLongOrNull()?.takeIf { it in 0..86_400_000 }
}
