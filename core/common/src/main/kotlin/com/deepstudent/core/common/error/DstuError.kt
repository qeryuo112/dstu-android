package com.deepstudent.core.common.error

import kotlin.coroutines.cancellation.CancellationException

enum class DstuErrorKind {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    UNAUTHORIZED,
    UNAVAILABLE,
    INTERNAL,
    CANCELLED,
}

data class DstuError(
    val code: String,
    val kind: DstuErrorKind,
    val message: String,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "error code must not be blank" }
        require(message.isNotBlank()) { "error message must not be blank" }
    }

    companion object {
        fun validation(code: String, message: String): DstuError =
            DstuError(code, DstuErrorKind.VALIDATION, message)

        fun notFound(code: String, message: String): DstuError =
            DstuError(code, DstuErrorKind.NOT_FOUND, message)

        fun conflict(code: String, message: String): DstuError =
            DstuError(code, DstuErrorKind.CONFLICT, message)

        fun unauthorized(code: String, message: String): DstuError =
            DstuError(code, DstuErrorKind.UNAUTHORIZED, message)

        fun unavailable(code: String, message: String): DstuError =
            DstuError(code, DstuErrorKind.UNAVAILABLE, message)

        fun internal(
            code: String,
            message: String,
            details: Map<String, String> = emptyMap(),
        ): DstuError = DstuError(code, DstuErrorKind.INTERNAL, message, details)
    }
}

class DstuException(
    val error: DstuError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)

fun dstuException(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))

private const val PUBLIC_UNEXPECTED_MESSAGE = "operation failed"
private const val PUBLIC_CANCELLED_MESSAGE = "operation cancelled"

/**
 * Only well-known non-sensitive JDK exception classes may appear in details.
 * Original exception messages, paths, SQL, credentials, and payloads never
 * cross into a recordable [DstuError].
 */
private val SAFE_CAUSE_CLASSES = setOf(
    "java.lang.ArithmeticException",
    "java.lang.IllegalArgumentException",
    "java.lang.IllegalStateException",
    "java.lang.UnsupportedOperationException",
    "java.time.DateTimeException",
    "java.io.IOException",
)

fun errorFromThrowable(throwable: Throwable): DstuError {
    if (throwable is DstuException) return throwable.error
    return DstuError.internal(
        code = "UNEXPECTED",
        message = PUBLIC_UNEXPECTED_MESSAGE,
        details = safeCauseDetails(throwable),
    )
}

fun CancellationException.asCancellationError(): DstuError =
    DstuError(
        code = "CANCELLED",
        kind = DstuErrorKind.CANCELLED,
        message = PUBLIC_CANCELLED_MESSAGE,
        details = safeCauseDetails(this),
    )

private fun safeCauseDetails(throwable: Throwable): Map<String, String> {
    val className = throwable::class.java.name
    return if (className in SAFE_CAUSE_CLASSES) mapOf("causeClass" to className) else emptyMap()
}
