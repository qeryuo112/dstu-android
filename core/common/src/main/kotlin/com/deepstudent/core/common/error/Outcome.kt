package com.deepstudent.core.common.error

import kotlin.coroutines.cancellation.CancellationException

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: DstuError) : Outcome<Nothing>

    data class Cancelled(val reason: String? = null) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
    is Outcome.Cancelled -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
    is Outcome.Cancelled -> this
}

fun <T> Outcome<T>.getOrThrow(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw DstuException(error)
    is Outcome.Cancelled -> throw CancellationException(reason ?: "operation cancelled")
}

fun <T> Outcome<T>.errorOrNull(): DstuError? =
    (this as? Outcome.Failure)?.error

/**
 * Converts ordinary failures into [Outcome.Failure]. Cancellation is deliberately
 * rethrown so cooperative cancellation never becomes an ordinary failure.
 */
inline fun <T> outcomeCatching(block: () -> T): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: DstuException) {
        Outcome.Failure(failure.error)
    } catch (failure: Exception) {
        Outcome.Failure(errorFromThrowable(failure))
    }

suspend fun <T> suspendOutcomeCatching(block: suspend () -> T): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: DstuException) {
        Outcome.Failure(failure.error)
    } catch (failure: Exception) {
        Outcome.Failure(errorFromThrowable(failure))
    }
