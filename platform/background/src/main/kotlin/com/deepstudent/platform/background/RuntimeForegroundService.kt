package com.deepstudent.platform.background

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android lifecycle adapter; execution remains owned by the application runtime. */
class RuntimeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watches = mutableMapOf<ForegroundSlot.Task, Job>()
    private val shown = mutableSetOf<ForegroundSlot>()
    private val timedOutTypes = mutableSetOf<Int>()
    private lateinit var notifications: TaskNotificationFactory
    private var host: RuntimeForegroundHost? = null
    private var observing = false
    private var latestStartId = 0
    private var reconciling = false
    private var closing = false
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        notifications = TaskNotificationFactory(this)
        notifications.createChannel()
        host = application as? RuntimeForegroundHost
        if (host == null) stopSelf()
        // Nothing is promoted here: onStartCommand promotes only after real slots exist.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        stopRequested = false
        val currentHost = host ?: run {
            stopIdle()
            return START_NOT_STICKY
        }
        if (intent == null || intent.action.isNullOrEmpty()) {
            // System rebuild. Never grant a new task or promote an unverified slot.
            reconcile(currentHost)
            return stickyDecision(currentHost)
        }
        if (intent.action == ACTION_START) {
            observe(currentHost)
            update(currentHost, currentHost.foregroundDuties.slots.value)
            return stickyDecision(currentHost)
        }
        if (intent.action == ACTION_CLOSE_BACKGROUND) {
            closeBackground(currentHost)
            return START_NOT_STICKY
        }
        stopIdle()
        return START_NOT_STICKY
    }

    private fun observe(host: RuntimeForegroundHost) {
        if (observing) return
        observing = true
        scope.launch { host.foregroundDuties.slots.collect { update(host, it) } }
    }

    /**
     * Null/empty rebuild: ask the host to re-establish real duties, then verify only live registry
     * entries. There is no ledger sweep and no eligibility grant from projection rows.
     */
    private fun reconcile(host: RuntimeForegroundHost) {
        if (reconciling) return
        reconciling = true
        scope.launch {
            try {
                val reconciled = withTimeoutOrNull(PROMOTION_DEADLINE_MS) {
                    withContext(Dispatchers.IO) { host.reconcileBackgroundResidency() }
                    withContext(Dispatchers.IO) {
                        DutyReconciler(host.foregroundDuties, host.foregroundTasks.query, host).reconcile()
                    }
                }
                if (reconciled == null) {
                    reconciling = false
                    ForegroundLog(host.foregroundLogger).event("background.reconcile", state = "deadline")
                    stopIdle()
                    return@launch
                }
                ForegroundLog(host.foregroundLogger).event(
                    "background.reconcile",
                    state = if (reconciled.isEmpty()) "empty" else "active",
                )
                reconciling = false
                if (stopRequested) return@launch
                if (reconciled.isEmpty()) {
                    stopIdle()
                    return@launch
                }
                observe(host)
                update(host, reconciled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reconciling = false
                ForegroundLog(host.foregroundLogger).event("background.reconcile", state = "failed")
                stopIdle()
            }
        }
    }

    private fun update(host: RuntimeForegroundHost, incoming: List<ForegroundSlot>) {
        if (stopRequested) return
        val slots = withoutTimedOut(host, incoming)
        removeStaleNotifications(slots)
        removeStaleWatches(slots)
        if (slots.isEmpty()) {
            stopIdle()
            return
        }
        if (!ensureForeground(host, slots)) return
        showSlots(host, slots)
    }

    private fun withoutTimedOut(host: RuntimeForegroundHost, incoming: List<ForegroundSlot>): List<ForegroundSlot> {
        val blocked = incoming.filter { ForegroundEligibility.type(it.purpose) in timedOutTypes }
        if (blocked.isEmpty()) return incoming
        blocked.forEach { retire(host, it) }
        return host.foregroundDuties.slots.value
    }

    private fun removeStaleNotifications(slots: List<ForegroundSlot>) {
        val live = slots.toSet()
        shown.filter { it !in live }.forEach { removeNotification(it) }
    }

    private fun removeStaleWatches(slots: List<ForegroundSlot>) {
        watches.keys.filter { it !in slots }.forEach { slot -> watches.remove(slot)?.cancel() }
    }

    private fun ensureForeground(host: RuntimeForegroundHost, slots: List<ForegroundSlot>): Boolean {
        if (notifications.available() && promote(slots)) return true
        ForegroundLog(host.foregroundLogger).event("background.foreground", state = "unavailable")
        TimeoutTasks.request(host, slots.filterIsInstance<ForegroundSlot.Task>().map { it.duty })
        slots.filterIsInstance<ForegroundSlot.Continuous>().forEach {
            host.foregroundDuties.retireContinuous(it.duty.ownerId)
        }
        stopIdle()
        return false
    }

    private fun showSlots(host: RuntimeForegroundHost, slots: List<ForegroundSlot>) {
        val observer = DutyObserver(
            host.foregroundDuties,
            host.foregroundTasks.query,
            host.foregroundTasks.observer,
            ForegroundLog(host.foregroundLogger),
        )
        slots.forEach { slot ->
            when (slot) {
                is ForegroundSlot.Continuous -> {
                    notifications.showContinuous(slot.duty)
                    shown += slot
                    ForegroundLog(host.foregroundLogger).event("background.continuous", state = "active")
                }

                is ForegroundSlot.Task -> watchTask(host, observer, slot)
            }
        }
    }

    private fun watchTask(host: RuntimeForegroundHost, observer: DutyObserver, slot: ForegroundSlot.Task) {
        shown += slot
        if (slot in watches) return
        watches[slot] = scope.launch(Dispatchers.IO) {
            observer.watch(slot.duty) { state ->
                withContext(Dispatchers.Main.immediate) {
                    if (slot in host.foregroundDuties.slots.value) notifications.show(slot.duty, state)
                }
            }
        }
        ForegroundLog(host.foregroundLogger).event("background.duty", slot.duty, "active")
    }

    private fun promote(slots: List<ForegroundSlot>): Boolean = try {
        val notification = notifications.summary(slots)
        val types = ForegroundEligibility.types(slots)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
            startForeground(TaskNotificationFactory.SUMMARY_ID, notification, types)
        } else {
            startForeground(TaskNotificationFactory.SUMMARY_ID, notification)
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout(fgsType)
    }

    override fun onTimeout(startId: Int) {
        // Legacy callback: only the dataSync budget existed there.
        handleTimeout(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Withdraws only the purpose whose system budget expired; other duties stay foreground. */
    private fun handleTimeout(fgsType: Int) {
        val currentHost = host ?: return
        val purposes = ForegroundEligibility.purposesForTimeoutType(fgsType)
        if (purposes.isEmpty()) {
            ForegroundLog(currentHost.foregroundLogger).event("background.timeout", state = "unknown_type")
            return
        }
        timedOutTypes += fgsType
        val slots = currentHost.foregroundDuties.slots.value
        val affected = slots.filter { it.purpose in purposes }
        val tasks = affected.filterIsInstance<ForegroundSlot.Task>().map { it.duty }
        TimeoutTasks.request(currentHost, tasks)
        affected.filterIsInstance<ForegroundSlot.Continuous>().forEach {
            currentHost.foregroundDuties.retireContinuous(it.duty.ownerId)
        }
        affected.forEach { removeNotification(it) }
        val remaining = currentHost.foregroundDuties.slots.value
        ForegroundLog(currentHost.foregroundLogger).event(
            "background.timeout",
            state = if (remaining.isEmpty()) "empty" else "remaining",
        )
        if (remaining.isEmpty()) {
            stopIdle()
        } else {
            // Explicitly drop the timed-out type before promoting only the surviving types.
            stopForeground(STOP_FOREGROUND_REMOVE)
            update(currentHost, remaining)
        }
    }

    private fun closeBackground(host: RuntimeForegroundHost) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                host.disableBackground()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ForegroundLog(host.foregroundLogger).event("background.close", state = "disable_failed")
            }
            host.foregroundDuties.retireAllContinuous()
            closing = false
            val remaining = host.foregroundDuties.slots.value
            if (remaining.isEmpty()) stopIdle() else update(host, remaining)
        }
    }

    private fun retire(host: RuntimeForegroundHost, slot: ForegroundSlot) {
        when (slot) {
            is ForegroundSlot.Task -> host.foregroundDuties.retire(slot.duty.taskId, slot.duty.generation)
            is ForegroundSlot.Continuous -> host.foregroundDuties.retireContinuous(slot.duty.ownerId)
        }
    }

    private fun removeNotification(slot: ForegroundSlot) {
        shown.remove(slot)
        when (slot) {
            is ForegroundSlot.Task -> notifications.remove(slot.duty)
            is ForegroundSlot.Continuous -> notifications.removeContinuous(slot.duty)
        }
    }

    private fun stickyDecision(host: RuntimeForegroundHost): Int =
        if (ForegroundSticky.shouldStick(host, host.foregroundDuties.continuousDuties.value)) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }

    private fun stopIdle() {
        stopRequested = true
        if (latestStartId == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (stopSelfResult(latestStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        shown.forEach { slot ->
            when (slot) {
                is ForegroundSlot.Task -> notifications.remove(slot.duty)
                is ForegroundSlot.Continuous -> notifications.removeContinuous(slot.duty)
            }
        }
        shown.clear()
        watches.clear()
        scope.cancel()
        host?.onForegroundServiceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_START = "com.deepstudent.platform.background.START"
        internal const val ACTION_CLOSE_BACKGROUND = CloseBackgroundAction.ACTION

        /** Bound reconciliation so a slow database does not leave an idle service running. */
        internal const val PROMOTION_DEADLINE_MS = 3_500L
    }
}
