package com.deepstudent.app.process

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.graph.AndroidGraphLogger
import com.deepstudent.app.graph.AppGraph
import com.deepstudent.app.graph.ProcessRole

/**
 * Shared implementation for the role-report-only bound services. The handler
 * accepts only MSG_REPORT from the host application UID.
 */
abstract class ProbeServiceBase : Service() {
    protected abstract val serviceLabel: String
    protected abstract val expectedRole: ProcessRole

    protected abstract fun graphOf(application: DstuApplication): AppGraph

    private lateinit var graph: AppGraph
    private lateinit var messenger: Messenger

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            onMessage(message)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val application = DstuApplication.from(this)
        graph = graphOf(application)
        check(graph.identity.role == expectedRole) {
            "$serviceLabel requires $expectedRole but application graph is " +
                "${graph.identity.role} in '${graph.identity.processName}'"
        }
        messenger = Messenger(handler)
        AndroidGraphLogger.info(
            "probe.service.created",
            fields(
                "service" to serviceLabel,
                "graphId" to graph.graphId,
                "role" to graph.identity.role,
                "processName" to graph.identity.processName,
                "pid" to Process.myPid(),
                "uid" to Process.myUid(),
            ),
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        AndroidGraphLogger.info(
            "probe.service.bound",
            fields("service" to serviceLabel, "graphId" to graph.graphId),
        )
        return messenger.binder
    }

    private fun onMessage(message: Message) {
        if (message.what != ProcessProbeProtocol.MSG_REPORT) {
            AndroidGraphLogger.warn(
                "probe.service.rejected",
                fields = fields("service" to serviceLabel, "what" to message.what),
            )
            return
        }
        if (!isHostCaller(message.sendingUid)) {
            AndroidGraphLogger.warn(
                "probe.service.untrusted_caller",
                fields = fields("service" to serviceLabel, "sendingUid" to message.sendingUid),
            )
            return
        }
        val replyTo = message.replyTo ?: return
        val report = ProcessProbeProtocol.reportFromGraph(
            graph = graph.report(),
            pid = Process.myPid(),
            uid = Process.myUid(),
            service = serviceLabel,
            isolatedProcess = currentProcessIsIsolated(),
            syncInitialized = DstuApplication.from(this).syncResourcesInitialized,
            securityInitialized = DstuApplication.from(this).securityResourcesInitialized,
        )
        try {
            replyTo.send(ProcessProbeProtocol.newReportReply(report))
            AndroidGraphLogger.info(
                "probe.service.reported",
                fields(
                    "service" to serviceLabel,
                    "graphId" to report.graphId,
                    "role" to report.role,
                    "pid" to report.pid,
                    "uid" to report.uid,
                ),
            )
        } catch (error: RemoteException) {
            AndroidGraphLogger.warn(
                "probe.service.reply_failed",
                error,
                fields("service" to serviceLabel),
            )
        }
    }

    private fun isHostCaller(uid: Int): Boolean = uid == applicationInfo.uid

    private fun currentProcessIsIsolated(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Process.isIsolated()

    private fun fields(vararg pairs: Pair<String, Any?>): Map<String, Any?> = pairs.toMap()
}
