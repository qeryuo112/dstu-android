package com.deepstudent.platform.background

import android.content.Context
import android.os.PowerManager
import com.deepstudent.core.common.logging.Logger

/** Builds a [WakeLockController] backed by real Android partial wake locks. */
fun androidWakeLockController(
    context: Context,
    logger: Logger,
    limits: (WakePhase) -> WakeLockLimits = { WakeLockLimits() },
): WakeLockController = WakeLockController(androidWakeLockFactory(context), logger, limits)

/**
 * Partial wake locks with a system timeout and no reference counting: a repeated acquire of the
 * same handle cannot stack, and Android drops the lock by itself when the segment times out.
 */
fun androidWakeLockFactory(context: Context): WakeLockFactory {
    val power = context.applicationContext.getSystemService(PowerManager::class.java)
    requireNotNull(power) { "PowerManager unavailable" }
    return WakeLockFactory { _, phase -> PowerManagerWakeLock(power, TAG_PREFIX + phase.name.lowercase()) }
}

private class PowerManagerWakeLock(private val power: PowerManager, private val tag: String) : WakeLockHandle {
    private val delegate: PowerManager.WakeLock =
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply { setReferenceCounted(false) }

    override val isHeld: Boolean get() = delegate.isHeld

    override fun acquire(timeoutMillis: Long): Boolean = try {
        delegate.acquire(timeoutMillis)
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun release() {
        try {
            if (delegate.isHeld) delegate.release()
        } catch (_: RuntimeException) {
            // A system-timeout release already dropped the lock; releasing twice is not an error.
        }
    }
}

/** Stable dumpsys tag per phase; it never carries an owner or user data. */
private const val TAG_PREFIX = "DeepStudent:background."
