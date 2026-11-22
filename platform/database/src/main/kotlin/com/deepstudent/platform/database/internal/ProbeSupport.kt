package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseError

internal fun expectEquals(actual: Any?, expected: Any?, label: String) {
    if (actual != expected) {
        throw ProbeAssertionFailure(label)
    }
}

internal fun expectTrue(value: Boolean, label: String) {
    if (!value) {
        throw ProbeAssertionFailure(label)
    }
}

internal fun sqliteVersionAtLeast(version: String, minimum: String): Boolean {
    val actual = version.split('.').mapNotNull(String::toIntOrNull)
    val required = minimum.split('.').mapNotNull(String::toIntOrNull)
    for (index in 0 until maxOf(actual.size, required.size)) {
        val left = actual.getOrElse(index) { 0 }
        val right = required.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return true
}

internal fun captureDatabaseError(block: () -> Unit): DatabaseError? =
    try {
        block()
        null
    } catch (failure: DatabaseFailure) {
        failure.error
    }
