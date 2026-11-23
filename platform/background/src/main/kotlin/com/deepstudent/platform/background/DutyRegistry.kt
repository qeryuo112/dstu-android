package com.deepstudent.platform.background

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local foreground responsibilities, never an execution or recovery ledger.
 *
 * Two roles share one registry and one foreground service:
 * - [ForegroundDuty] is a real runtime task generation and owns a persistent `fgs.<uuid>` projection
 *   key through [ForegroundDutyProjector];
 * - [ContinuousDuty] is an opaque, process-local owner such as a user terminal session. It never
 *   invents a [TaskId], is never projected, and is never cold-recoverable.
 */
class DutyRegistry(private val projector: ForegroundDutyProjector = ForegroundDutyProjector.None) {
    private val lock = Any()
    private val taskReferences = mutableMapOf<Long, DutyReference>()
    private val continuousReferences = mutableMapOf<Long, ContinuousReference>()
    private var nextReference = 0L

    private val taskState = MutableStateFlow<List<ForegroundDuty>>(emptyList())
    private val continuousState = MutableStateFlow<List<ContinuousDuty>>(emptyList())
    private val slotState = MutableStateFlow<List<ForegroundSlot>>(emptyList())

    /** Task duties only; kept for existing consumers and tests. */
    val duties: StateFlow<List<ForegroundDuty>> = taskState.asStateFlow()

    /** Process-local continuous responsibilities such as terminal sessions. */
    val continuousDuties: StateFlow<List<ContinuousDuty>> = continuousState.asStateFlow()

    /** Deduplicated union published once per distinct duty. The foreground service observes this. */
    val slots: StateFlow<List<ForegroundSlot>> = slotState.asStateFlow()

    /**
     * Adds one independent task reference. A second reference for the same duty is allowed but the
     * duty is published once; the last close removes it. Every reference owns a unique
     * `fgs.<uuid>` projection key.
     */
    internal fun acquire(duty: ForegroundDuty): DutyReference {
        val reference = synchronized(lock) {
            val existing = taskReferences.values.firstOrNull { it.duty.taskId == duty.taskId }
            require(existing == null || existing.duty == duty) { "conflicting task duty" }
            check(nextReference < Long.MAX_VALUE) { "duty reference limit reached" }
            val id = ++nextReference
            val created = DutyReference(id, duty, RuntimeRecordKey.parse("$RECORD_KEY_PREFIX${UUID.randomUUID()}")) { released ->
                releaseTask(released)
            }
            taskReferences[id] = created
            // Project before publishing so every observer that can see the duty also has a key.
            runCatching { projector.acquired(created.duty, created.recordKey) }
            publish()
            created
        }
        return reference
    }

    /**
     * Adds one independent reference to a process-local continuous owner. Repeating the same
     * owner is an additional reference, not a conflicting task generation.
     */
    internal fun acquireContinuous(duty: ContinuousDuty): ContinuousReference {
        val reference = synchronized(lock) {
            check(nextReference < Long.MAX_VALUE) { "duty reference limit reached" }
            val id = ++nextReference
            val created = ContinuousReference(id, duty) { released -> releaseContinuous(released) }
            continuousReferences[id] = created
            publish()
            created
        }
        return reference
    }

    /** Terminal runtime facts retire all references for this task generation. */
    internal fun retire(taskId: TaskId, generation: Long) {
        val matches = synchronized(lock) {
            taskReferences.values.filter { it.duty.taskId == taskId && it.duty.generation == generation }
        }
        matches.forEach { it.close() }
    }

    /** Ends every reference of one continuous owner, e.g. a terminal session being closed. */
    internal fun retireContinuous(ownerId: String) {
        val matches = synchronized(lock) { continuousReferences.values.filter { it.duty.ownerId == ownerId } }
        matches.forEach { it.close() }
    }

    /** Ends every continuous purpose; task duties are untouched. */
    internal fun retireAllContinuous() {
        val matches = synchronized(lock) { continuousReferences.values.toList() }
        matches.forEach { it.close() }
    }

    private fun releaseTask(reference: DutyReference) {
        val removed = synchronized(lock) {
            (taskReferences.remove(reference.id) != null).also { if (it) publish() }
        }
        if (removed) runCatching { projector.released(reference.duty, reference.recordKey) }
    }

    private fun releaseContinuous(reference: ContinuousReference) {
        synchronized(lock) {
            if (continuousReferences.remove(reference.id) != null) publish()
        }
    }

    private fun publish() {
        taskState.value = taskReferences.values.map { it.duty }.distinct()
        continuousState.value = continuousReferences.values.map { it.duty }.distinct()
        slotState.value = buildList {
            addAll(taskState.value.map(ForegroundSlot::Task))
            addAll(continuousState.value.map(ForegroundSlot::Continuous))
        }
    }

    private companion object {
        const val RECORD_KEY_PREFIX = "fgs."
    }
}

/** One registry slot the foreground service promotes and notifies. */
sealed interface ForegroundSlot {
    val purpose: ForegroundPurpose

    data class Task(val duty: ForegroundDuty) : ForegroundSlot {
        override val purpose: ForegroundPurpose get() = duty.purpose
    }

    data class Continuous(val duty: ContinuousDuty) : ForegroundSlot {
        override val purpose: ForegroundPurpose get() = duty.purpose
    }
}

/**
 * One registry reference to a real task generation. Closing it is idempotent and releases exactly
 * its own projection key, not the keys of other references to the same task.
 */
class DutyReference internal constructor(
    internal val id: Long,
    val duty: ForegroundDuty,
    val recordKey: RuntimeRecordKey,
    private val onClose: (DutyReference) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose(this)
    }
}

/** One registry reference to a process-local continuous owner; not projected and not sticky. */
class ContinuousReference internal constructor(
    internal val id: Long,
    val duty: ContinuousDuty,
    private val onClose: (ContinuousReference) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose(this)
    }
}

data class ForegroundDuty(val taskId: TaskId, val generation: Long, val correlationId: CorrelationId?, val purpose: ForegroundPurpose) {
    init {
        require(generation > 0) { "invalid task generation" }
    }
}

/**
 * A continuous owner that has no runtime task. [ownerId] is opaque to this module (a real terminal
 * session id in S2.24) and must never be parsed as a task identity.
 */
data class ContinuousDuty(val ownerId: String, val purpose: ForegroundPurpose) {
    init {
        require(ownerId.matches(OWNER_PATTERN)) { "invalid continuous owner" }
        require(purpose == ForegroundPurpose.TERMINAL) { "unsupported continuous purpose" }
    }

    private companion object {
        val OWNER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    }
}

/** Each purpose maps to exactly one foreground service type and notification copy. */
enum class ForegroundPurpose {
    /** User-initiated transfer/synchronization with an Android dataSync budget. */
    DATA_SYNC,

    /** A real user terminal session; Android 14+ uses specialUse. Never cold-recoverable. */
    TERMINAL,
}

/** Bridges registry reference lifetimes to the host's persistent duty projection. */
interface ForegroundDutyProjector {
    fun acquired(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {}

    fun released(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {}

    companion object {
        val None: ForegroundDutyProjector = object : ForegroundDutyProjector {}
    }
}
