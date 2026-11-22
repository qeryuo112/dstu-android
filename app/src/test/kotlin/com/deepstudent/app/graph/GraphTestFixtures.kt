package com.deepstudent.app.graph

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

internal const val TEST_APP_ID = "com.deepstudent.app"

internal class RecordingGraphLogger : GraphLogger {
    val infos = mutableListOf<Pair<String, Map<String, Any?>>>()
    val warnings = mutableListOf<Triple<String, Throwable?, Map<String, Any?>>>()

    override fun info(event: String, fields: Map<String, Any?>) {
        infos += event to fields
    }

    override fun warn(event: String, throwable: Throwable?, fields: Map<String, Any?>) {
        warnings += Triple(event, throwable, fields)
    }
}

internal class NamedDispatcher(private val label: String) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }

    override fun toString(): String = label
}

internal class TestMainResource(
    override val description: String,
    private val closeFailure: Throwable? = null,
) : MainProcessResources {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        closeFailure?.let { throw it }
    }
}

internal class CountingMainResourceFactory : MainResourceFactory {
    var openCount: Int = 0
        private set

    val resources = mutableListOf<TestMainResource>()

    override fun open(): MainProcessResources {
        openCount++
        return TestMainResource("test-resource-$openCount").also { resources += it }
    }
}

internal fun testIdentity(
    role: ProcessRole,
    applicationId: String = TEST_APP_ID,
): ProcessIdentity = ProcessIdentity(
    processName = when (role) {
        ProcessRole.MAIN -> applicationId
        ProcessRole.ENGINE -> applicationId + ProcessClassifier.ENGINE_SUFFIX
        ProcessRole.ISOLATED -> applicationId + ProcessClassifier.ISOLATED_SUFFIX
        ProcessRole.AUXILIARY -> applicationId + ":worker"
    },
    role = role,
    source = ProcessNameSource.APPLICATION_API,
)
