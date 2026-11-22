package com.deepstudent.platform.execution

/** Fixed stage names only; no exception messages, throwable formatting or output. */
internal enum class ExecutionStage {
    SDK_UID,
    SDK_VERSION,
    BINDING_LOOKUP,
    PERMISSION_CHECK,
    PERMISSION_REQUEST,
    UID_PROBE,
    PROCESS_START,
    PROCESS_WAIT,
    PROCESS_STDIN,
    PROCESS_STDOUT,
    PROCESS_STDERR,
    PROCESS_DESTROY,
    PROCESS_CLOSE,
}

internal data class ExecutionFailureLogEvent(
    val stage: ExecutionStage,
    val exceptionClass: String,
) : ExecutionLogRecord

internal fun ExecutionLogSink.recordFailure(stage: ExecutionStage, failure: Throwable) {
    emit(ExecutionFailureLogEvent(stage, failure.javaClass.name))
}

internal inline fun <T> ExecutionLogSink.atStage(stage: ExecutionStage, operation: () -> T): T = try {
    operation()
} catch (failure: Exception) {
    recordFailure(stage, failure)
    throw failure
}
