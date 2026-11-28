package com.deepstudent.features.chat.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatTurnBusinessStates
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.runtime.api.BusinessCompletion
import java.time.Instant

/** One Android-local terminal fact; [status] is the business state, never a chat block status. */
internal data class ChatTurnFact(
    val factId: String,
    val taskId: TaskId,
    val generation: Long,
    val status: String,
    val sessionId: String,
    val receipt: String,
    val committedAt: Instant,
) {
    val completion: BusinessCompletion get() = BusinessCompletion(
        factId = factId,
        taskId = taskId,
        generation = generation,
        state = requireNotNull(ChatTurnBusinessStates.fromFactStatus(status)) { "invalid chat turn fact state" },
        receipt = receipt,
        committedAt = committedAt,
    )
}

internal fun SqlOperations.putFact(fact: ChatTurnFact) {
    val ids = com.deepstudent.features.chat.api.ChatTurnIds.of(fact.taskId, fact.generation)
    execute(
        """INSERT INTO __android_chat_turn_facts(
        fact_id,task_id,generation,state,session_id,assistant_id,block_id,receipt,committed_at
        ) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(task_id,generation) DO NOTHING
        """.trimIndent(),
        listOf(
            text(fact.factId), text(fact.taskId.value), SqlValue.Integer(fact.generation),
            text(fact.status), text(fact.sessionId), text(ids.assistantId), text(ids.blockId),
            text(fact.receipt), text(fact.committedAt.toString()),
        ),
    )
}

internal fun readChatTurnFact(scope: SqlReadOperations, taskId: TaskId, generation: Long): ChatTurnFact? = scope.query(
    """SELECT fact_id,state,session_id,receipt,committed_at FROM __android_chat_turn_facts
        WHERE task_id=? AND generation=?
    """.trimIndent(),
    listOf(text(taskId.value), SqlValue.Integer(generation)),
) { row ->
    ChatTurnFact(
        factId = row.text(0),
        taskId = taskId,
        generation = generation,
        status = row.text(1),
        sessionId = row.text(2),
        receipt = row.text(3),
        committedAt = Instant.parse(row.text(4)),
    )
}.singleOrNull()

internal fun readChatTurnPendingFacts(scope: SqlReadOperations, consumerId: String, limit: Int): List<ChatTurnFact> {
    require(consumerId.isNotBlank() && limit in 1..1000) { "invalid fact query" }
    return scope.query(
        """SELECT f.fact_id,f.task_id,f.generation,f.state,f.session_id,f.receipt,f.committed_at
        FROM __android_chat_turn_facts f WHERE NOT EXISTS(
          SELECT 1 FROM __android_chat_turn_acks a WHERE a.fact_id=f.fact_id AND a.consumer_id=?
        ) ORDER BY f.task_id,f.generation,f.fact_id LIMIT ?
        """.trimIndent(),
        listOf(text(consumerId), SqlValue.Integer(limit.toLong())),
    ) { row ->
        val task = TaskId.parse(row.text(1))
        ChatTurnFact(
            factId = row.text(0),
            taskId = task,
            generation = row.long(2),
            status = row.text(3),
            sessionId = row.text(4),
            receipt = row.text(5),
            committedAt = Instant.parse(row.text(6)),
        )
    }
}

internal fun SqlOperations.acknowledgeFact(consumerId: String, factId: String, at: Instant) {
    require(consumerId.isNotBlank() && factId.isNotBlank()) { "invalid fact acknowledgement" }
    execute(
        "INSERT OR IGNORE INTO __android_chat_turn_acks(consumer_id,fact_id,acknowledged_at) VALUES(?,?,?)",
        listOf(text(consumerId), text(factId), text(at.toString())),
    )
}
