package com.deepstudent.features.notes.data

import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import java.time.Instant

data class NoteTaskCancellation(val identity: NoteTaskIdentity, val committedAt: Instant) {
    val factId: String get() = "notes:cancel:${identity.taskId}:${identity.generation}"
}

/** Local cancellation receipt; it never masquerades as a note id or a created note. */
class NoteTaskCancellations(private val database: SqlDatabase) {
    init {
        database.execute("""CREATE TABLE IF NOT EXISTS __android_notes_task_cancel(
            task_id TEXT NOT NULL,generation INTEGER NOT NULL,committed_at TEXT NOT NULL,
            PRIMARY KEY(task_id,generation))""".trimIndent())
        database.execute("""CREATE TABLE IF NOT EXISTS __android_notes_task_cancel_ack(
            consumer_id TEXT NOT NULL,fact_id TEXT NOT NULL,PRIMARY KEY(consumer_id,fact_id))""".trimIndent())
    }

    fun commit(identity: NoteTaskIdentity) {
        database.execute("INSERT OR IGNORE INTO __android_notes_task_cancel VALUES(?,?,?)", listOf(
            text(identity.taskId), SqlValue.Integer(identity.generation), text(Instant.now().toString()),
        ))
    }

    fun find(identity: NoteTaskIdentity): NoteTaskCancellation? = database.query(
        "SELECT committed_at FROM __android_notes_task_cancel WHERE task_id=? AND generation=?",
        listOf(text(identity.taskId), SqlValue.Integer(identity.generation)),
    ) { NoteTaskCancellation(identity, Instant.parse(it.text(0))) }.singleOrNull()

    fun pending(consumer: String, limit: Int): List<NoteTaskCancellation> {
        require(consumer.isNotBlank() && limit in 1..1000) { "invalid cancellation query" }
        return database.query("""SELECT task_id,generation,committed_at FROM __android_notes_task_cancel c
            WHERE NOT EXISTS(SELECT 1 FROM __android_notes_task_cancel_ack a
                WHERE a.consumer_id=? AND a.fact_id='notes:cancel:'||c.task_id||':'||c.generation)
              AND NOT EXISTS(SELECT 1 FROM __android_notes_task_facts f
                WHERE f.task_id=c.task_id AND f.generation=c.generation)
            ORDER BY task_id,generation LIMIT ?""".trimIndent(), listOf(text(consumer), SqlValue.Integer(limit.toLong()))) {
            NoteTaskCancellation(NoteTaskIdentity(it.text(0), it.long(1)), Instant.parse(it.text(2)))
        }
    }

    fun acknowledge(consumer: String, factId: String) {
        require(consumer.isNotBlank()) { "invalid cancellation consumer" }
        database.execute("INSERT OR IGNORE INTO __android_notes_task_cancel_ack VALUES(?,?)", listOf(text(consumer), text(factId)))
    }
}
