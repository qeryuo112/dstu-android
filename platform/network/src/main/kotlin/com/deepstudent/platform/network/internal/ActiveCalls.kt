package com.deepstudent.platform.network.internal

import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import okhttp3.Call

internal class ActiveCalls {
    private val active = mutableSetOf<Call>()
    private var closed = false

    @Synchronized
    fun add(call: Call) {
        if (closed) throw NetworkException(NetworkError.CLOSED)
        active.add(call)
    }

    @Synchronized
    fun remove(call: Call) {
        active.remove(call)
    }

    fun close(): Boolean {
        val pending = synchronized(this) {
            if (closed) return false
            closed = true
            active.toList()
        }
        pending.forEach(Call::cancel)
        return true
    }
}
