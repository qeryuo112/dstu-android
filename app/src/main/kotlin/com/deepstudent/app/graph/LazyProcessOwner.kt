package com.deepstudent.app.graph

/**
 * Lazy, close-once owner used to prove that process graphs which never touch
 * main process resources cannot initialize them as a construction side effect.
 */
internal class LazyProcessOwner<T : AutoCloseable>(
    private val name: String,
    private val logger: GraphLogger,
    private val opener: () -> T,
) : AutoCloseable {
    private val lock = Any()
    private var value: T? = null
    private var closed = false

    val isInitialized: Boolean
        get() = synchronized(lock) { value != null }

    fun get(): T = synchronized(lock) {
        check(!closed) { "Owner '$name' is already closed" }
        value ?: opener().also { opened ->
            value = opened
            logger.info(
                "owner.opened",
                mapOf("owner" to name, "resource" to opened.describe()),
            )
        }
    }

    override fun close() {
        var opened: T? = null
        var alreadyClosed = false
        synchronized(lock) {
            if (closed) {
                alreadyClosed = true
            } else {
                closed = true
                opened = value
                value = null
            }
        }
        if (alreadyClosed) return
        opened?.let {
            runCatching { it.close() }
                .onFailure { error ->
                    logger.warn("owner.close.failed", error, mapOf("owner" to name))
                }
        }
        logger.info("owner.closed", mapOf("owner" to name, "wasOpen" to (opened != null)))
    }
}

private fun AutoCloseable.describe(): String =
    if (this is MainProcessResources) description else javaClass.name
