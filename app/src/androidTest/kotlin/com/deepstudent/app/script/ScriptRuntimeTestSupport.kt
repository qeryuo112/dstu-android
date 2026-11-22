package com.deepstudent.app.script

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.runtime.MainRuntimeResources
import com.deepstudent.app.runtime.script.MainScriptResources
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskSubmission
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal class ScriptRuntimeFixture(
    val app: DstuApplication = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as DstuApplication,
) : AutoCloseable {
    val root: File = File(app.cacheDir, "s1-19-script-runtime-${UUID.randomUUID()}")
        .also { check(it.mkdirs()) { "cannot create isolated script runtime directory" } }
    private var resources: MainRuntimeResources? = null

    suspend fun open(): MainRuntimeResources {
        check(resources == null) { "isolated runtime is already open" }
        return MainRuntimeResources.open(root.resolve("active"), app.storage()).also { resources = it }
    }

    fun closeRuntime() {
        val current = resources
        resources = null
        current?.close()
    }

    override fun close() {
        try {
            closeRuntime()
        } finally {
            root.deleteRecursively()
        }
    }
}

internal fun scriptSubmission(
    source: String,
    maxCalls: Int = 50,
    dedupeKey: String? = null,
): TaskSubmission = TaskSubmission(
    MainScriptResources.KIND,
    JsonValue.obj(
        "script" to JsonValue.text(source),
        "maxCalls" to JsonValue.integer(maxCalls.toLong()),
    ),
    dedupeKey = dedupeKey,
)

internal suspend fun awaitTerminal(
    runtime: MainRuntimeResources,
    taskId: TaskId,
): RuntimeSnapshot = withTimeout(TEST_TIMEOUT_MILLIS) {
    var snapshot = checkNotNull(runtime.controller.snapshot(taskId))
    while (!snapshot.state.terminal) {
        delay(POLL_MILLIS)
        snapshot = checkNotNull(runtime.controller.snapshot(taskId))
    }
    snapshot
}

internal suspend fun awaitRunningCheckpoint(
    runtime: MainRuntimeResources,
    taskId: TaskId,
): RuntimeSnapshot = withTimeout(TEST_TIMEOUT_MILLIS) {
    var snapshot = checkNotNull(runtime.controller.snapshot(taskId))
    while (snapshot.state != RuntimeState.RUNNING || snapshot.checkpointStage() != "running") {
        delay(POLL_MILLIS)
        snapshot = checkNotNull(runtime.controller.snapshot(taskId))
    }
    snapshot
}

internal suspend fun terminalEventCount(runtime: MainRuntimeResources, taskId: TaskId): Int =
    runtime.controller.events(taskId).count { it.state.terminal }

private fun RuntimeSnapshot.checkpointStage(): String? =
    ((execution.checkpoint as? JsonValue.Object)?.get("stage") as? JsonValue.Text)?.value

private const val TEST_TIMEOUT_MILLIS = 20_000L
private const val POLL_MILLIS = 10L
