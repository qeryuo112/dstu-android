package com.deepstudent.platform.background

import android.app.Service
import android.content.Intent
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

/** Android lifecycle adapter; execution remains owned by the application runtime. */
class RuntimeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watches = mutableMapOf<ForegroundDuty, Job>()
    private lateinit var notifications: TaskNotificationFactory
    private var host: RuntimeForegroundHost? = null
    private var timedOut = false
    private var observing = false
    private var latestStartId = 0
    private var reconciling = false

    override fun onCreate() {
        super.onCreate()
        notifications = TaskNotificationFactory(this)
        notifications.createChannel()
        host = application as? RuntimeForegroundHost
        if (!promote(emptyList())) {
            timedOut = true
            host?.let { TimeoutTasks.request(it, it.foregroundDuties.duties.value) }
            stopSelf()
            return
        }
        val currentHost = host
        if (currentHost == null) {
            stopIdle()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val currentHost = host
        if (currentHost == null || timedOut) {
            stopIdle()
        } else if (intent == null) {
            reconcile(currentHost)
        } else if (intent.action == ACTION_START) {
            if (!reconciling) observe(currentHost)
        } else {
            stopIdle()
        }
        return START_NOT_STICKY
    }

    private fun observe(host: RuntimeForegroundHost) {
        if (observing) {
            update(host, host.foregroundDuties.duties.value)
            return
        }
        observing = true
        scope.launch { host.foregroundDuties.duties.collect { update(host, it) } }
    }

    private fun reconcile(host: RuntimeForegroundHost) {
        if (reconciling) return
        reconciling = true
        scope.launch {
            try {
                val active = withContext(Dispatchers.IO) {
                    DutyReconciler(host.foregroundDuties, host.foregroundTasks.query, host.foregroundTasks.catalog).reconcile()
                }
                ForegroundLog(host.foregroundLogger).event("background.reconcile", state = if (active) "active" else "empty")
                reconciling = false
                observe(host)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reconciling = false
                ForegroundLog(host.foregroundLogger).event("background.reconcile", state = "failed")
                TimeoutTasks.request(host, host.foregroundDuties.duties.value)
                stopIdle()
            }
        }
    }

    private fun update(host: RuntimeForegroundHost, duties: List<ForegroundDuty>) {
        if (timedOut) return
        val removed = watches.keys.filter { it !in duties }
        removed.forEach { duty ->
            watches.remove(duty)?.cancel()
            notifications.remove(duty)
        }
        if (duties.isEmpty()) {
            stopIdle()
            return
        }
        if (!notifications.available() || !promote(duties)) {
            ForegroundLog(host.foregroundLogger).event("background.foreground", state = "unavailable")
            TimeoutTasks.request(host, duties)
            stopIdle()
            return
        }
        val observer = DutyObserver(host.foregroundDuties, host.foregroundTasks.query, host.foregroundTasks.observer, ForegroundLog(host.foregroundLogger))
        duties.filterNot(watches::containsKey).forEach { duty ->
            watches[duty] = scope.launch(Dispatchers.IO) {
                observer.watch(duty) { state ->
                    withContext(Dispatchers.Main.immediate) {
                        if (!timedOut && duty in host.foregroundDuties.duties.value) notifications.show(duty, state)
                    }
                }
            }
            ForegroundLog(host.foregroundLogger).event("background.duty", duty, "active")
        }
    }

    private fun promote(duties: List<ForegroundDuty>): Boolean = try {
        val notification = notifications.summary(duties)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(TaskNotificationFactory.SUMMARY_ID, notification, ForegroundEligibility.type(ForegroundPurpose.DATA_SYNC, Build.VERSION.SDK_INT))
        } else {
            startForeground(TaskNotificationFactory.SUMMARY_ID, notification)
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        if (timedOut) return
        timedOut = true
        val currentHost = host
        val duties = currentHost?.foregroundDuties?.duties?.value.orEmpty()
        if (currentHost != null) {
            TimeoutTasks.request(currentHost, duties)
        }
        duties.forEach { notifications.remove(it) }
        stopIdle()
    }

    private fun stopIdle() {
        if (latestStartId == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (stopSelfResult(latestStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        watches.keys.forEach { notifications.remove(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_START = "com.deepstudent.platform.background.START"
    }
}
