package com.deepstudent.features.notes.data

import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The completion row is committed in exactly the same VFS transaction as the note. */
class SqlNoteTaskStore(private val database: SqlDatabase, private val notes: SqlNoteRepository) {
    init { NoteTaskSchema.initialize(database) }

    suspend fun create(command: CreateNote, identity: NoteTaskIdentity): NoteTaskFact {
        val context = currentCoroutineContext()
        val snapshot = command.copy(tags = command.tags.toList())
        val hash = requestHash(snapshot)
        return database.transaction {
            context.ensureActive()
            val previous = taskFact(identity)
            if (previous != null) {
                if (previous.second != hash) noteConflict()
                return@transaction previous.first
            }
            val note = notes.createInTransaction(this, snapshot)
            val fact = NoteTaskFact("notes:${UUID.randomUUID()}", identity, note.id, Instant.parse(note.updatedAt))
            execute("INSERT INTO __android_notes_task_facts VALUES(?,?,?,?,?,?)", listOf(
                text(fact.factId), text(identity.taskId), SqlValue.Integer(identity.generation), text(hash),
                text(note.id), text(fact.committedAt.toString()),
            ))
            context.ensureActive()
            fact
        }
    }

    fun find(identity: NoteTaskIdentity): NoteTaskFact? = database.taskFact(identity)?.first

    fun pending(consumer: String, limit: Int): List<NoteTaskFact> {
        require(consumer.isNotBlank() && limit in 1..1000) { "invalid fact query" }
        return database.query("""SELECT fact_id,task_id,generation,note_id,committed_at FROM __android_notes_task_facts f
            WHERE NOT EXISTS(SELECT 1 FROM __android_notes_task_ack a WHERE a.fact_id=f.fact_id AND a.consumer_id=?)
            ORDER BY fact_id LIMIT ?""".trimIndent(), listOf(text(consumer), SqlValue.Integer(limit.toLong()))) {
            NoteTaskFact(it.text(0), NoteTaskIdentity(it.text(1), it.long(2)), it.text(3), Instant.parse(it.text(4)))
        }
    }

    fun acknowledge(consumer: String, factId: String) {
        require(consumer.isNotBlank()) { "invalid fact consumer" }
        database.execute("INSERT OR IGNORE INTO __android_notes_task_ack VALUES(?,?)", listOf(text(consumer), text(factId)))
    }
}
