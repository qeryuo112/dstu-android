package com.deepstudent.app.runtime

import com.deepstudent.app.runtime.script.MainScriptResources
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.chat.backend.application.ChatStreamHandler
import com.deepstudent.features.chat.backend.application.RuntimeChatStreams
import com.deepstudent.features.chat.data.NetworkChatStream
import com.deepstudent.features.notes.backend.application.NoteStreamImport
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.app.storage.AndroidDatabaseLog
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.RuntimeController
import com.deepstudent.services.runtime.backend.TaskIdSource
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.data.SqlTaskLedger
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/** Application-owned execution. No Activity or observer owns the task jobs. */
internal class MainRuntimeResources private constructor(
    private val database: SqlDatabase,
    val script: MainScriptResources,
    private val composition: MainRuntimeComposition,
    private val processLock: RuntimeProcessLock,
) : AutoCloseable {
    val scope: CoroutineScope get() = composition.scope
    val ledger: SqlTaskLedger get() = composition.ledger
    val supervisor: TaskSupervisor get() = composition.supervisor
    val controller: RuntimeController get() = composition.controller
    val completionSource: NotesCompletionSource get() = composition.completionSource
    val ownerId: String get() = processLock.ownerId
    val startup = RuntimeStartupRecovery(this)

    override fun close() {
        var failure: Throwable? = null
        try {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        } catch (thrown: Throwable) {
            failure = thrown
        }
        failure = closeResource(script, failure)
        failure = closeResource(database, failure)
        failure = closeResource(processLock, failure)
        failure?.let { throw it }
    }

    companion object {
        val NOTE_CREATE = TaskKind.parse("notes.create")
        val NOTE_IMPORT = TaskKind.parse("notes.import_stream")
        val LEASE_DURATION: Duration = Duration.ofSeconds(30)

        fun open(activeRoot: File, storage: MainStorageResources): MainRuntimeResources {
            val parent = File(activeRoot, "databases")
            check(parent.isDirectory || parent.mkdirs()) { "private runtime directory unavailable" }
            val processLock = RuntimeProcessLock.acquire(parent)
            val database = try { DatabaseRegistry.openLocal(File(parent, "runtime.db"), log = AndroidDatabaseLog) } catch (failure: Exception) {
                closeResource(processLock, failure)
                throw failure
            }
            var script: MainScriptResources? = null
            try {
                val openedScript = MainScriptResources.open(parent)
                script = openedScript
                val composition = MainRuntimeComposition.create(
                    database, processLock.ownerId, storage, openedScript,
                )
                return MainRuntimeResources(database, openedScript, composition, processLock)
            } catch (failure: Exception) {
                var cleanupFailure: Throwable? = failure
                cleanupFailure = closeResource(script, cleanupFailure)
                cleanupFailure = closeResource(database, cleanupFailure)
                closeResource(processLock, cleanupFailure)
                throw failure
            }
        }
    }
}

private class MainRuntimeComposition(
    val scope: CoroutineScope,
    val ledger: SqlTaskLedger,
    val supervisor: TaskSupervisor,
    val controller: RuntimeController,
    val completionSource: NotesCompletionSource,
) {
    companion object {
        fun create(
            database: SqlDatabase,
            ownerId: String,
            storage: MainStorageResources,
            script: MainScriptResources,
        ): MainRuntimeComposition {
            val ledger = SqlTaskLedger(database)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val source = NotesCompletionSource(storage.noteTasks, storage.noteCancellations)
                val registry = HandlerRegistry(
                    mapOf(
                        MainRuntimeResources.NOTE_CREATE to NoteCreationHandler(storage.noteTasks),
                        MainRuntimeResources.NOTE_IMPORT to NoteStreamImport(
                            storage.noteStreamReader(), NoteImportStorageAdapter(storage),
                        ),
                        RuntimeChatStreams.KIND to ChatStreamHandler(NetworkChatStream(storage.network)),
                        MainScriptResources.KIND to script.handler,
                    ),
                    mapOf(
                        MainRuntimeResources.NOTE_CREATE to source,
                        MainRuntimeResources.NOTE_IMPORT to source,
                        MainScriptResources.KIND to script.completionSource,
                    ),
                )
                val leases = LeaseManager(
                    ledger, SystemWallClock, ownerId, MainRuntimeResources.LEASE_DURATION,
                )
                val supervisor = TaskSupervisor(
                    scope, ledger, leases, registry, SystemWallClock, AndroidOperationLogger,
                )
                val controller = RuntimeController(
                    ledger,
                    supervisor,
                    TaskIdSource { TaskId.parse("task_${UUID.randomUUID()}") },
                    SystemWallClock,
                )
                return MainRuntimeComposition(scope, ledger, supervisor, controller, source)
            } catch (failure: Exception) {
                scope.coroutineContext[Job]?.cancel()
                throw failure
            }
        }
    }
}

private fun closeResource(resource: AutoCloseable?, failure: Throwable?): Throwable? =
    try {
        resource?.close()
        failure
    } catch (closeFailure: Throwable) {
        if (failure == null) {
            closeFailure
        } else {
            failure.addSuppressed(closeFailure)
            failure
        }
    }
