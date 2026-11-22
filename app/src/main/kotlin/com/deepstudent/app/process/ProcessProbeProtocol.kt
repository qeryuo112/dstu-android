package com.deepstudent.app.process

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import com.deepstudent.app.graph.GraphReport
import com.deepstudent.app.graph.ProcessNameSource
import com.deepstudent.app.graph.ProcessRole

/**
 * Narrow, report-only Messenger protocol for process-role probes.
 *
 * There is exactly one request: MSG_REPORT. No command strings, payloads,
 * Binder proxies or general-purpose remote operations are exposed.
 */
object ProcessProbeProtocol {
    const val MSG_REPORT = 0x5301

    private const val KEY_ROLE = "role"
    private const val KEY_PROCESS_NAME = "processName"
    private const val KEY_PROCESS_NAME_SOURCE = "processNameSource"
    private const val KEY_GRAPH_ID = "graphId"
    private const val KEY_BUSINESS_INITIALIZED = "businessResourcesInitialized"
    private const val KEY_ISOLATED_PROCESS = "isolatedProcess"
    private const val KEY_PID = "pid"
    private const val KEY_UID = "uid"
    private const val KEY_SERVICE = "service"
    private const val KEY_SYNC_INITIALIZED = "syncInitialized"
    private const val KEY_SECURITY_INITIALIZED = "securityInitialized"

    fun newReportRequest(replyTo: Messenger): Message =
        Message.obtain(null, MSG_REPORT).apply {
            this.replyTo = replyTo
        }

    fun newReportReply(report: ProcessProbeReport): Message =
        Message.obtain(null, MSG_REPORT).apply {
            data = report.toBundle()
        }

    internal fun ProcessProbeReport.toBundle(): Bundle = Bundle().apply {
        putString(KEY_ROLE, graph.role.name)
        putString(KEY_PROCESS_NAME, graph.processName)
        putString(KEY_PROCESS_NAME_SOURCE, graph.processNameSource.name)
        putString(KEY_GRAPH_ID, graph.graphId)
        putBoolean(KEY_BUSINESS_INITIALIZED, graph.businessResourcesInitialized)
        putBoolean(KEY_ISOLATED_PROCESS, isolatedProcess)
        putInt(KEY_PID, pid)
        putInt(KEY_UID, uid)
        putString(KEY_SERVICE, service)
        putBoolean(KEY_SYNC_INITIALIZED, syncInitialized)
        putBoolean(KEY_SECURITY_INITIALIZED, securityInitialized)
    }

    fun fromBundle(bundle: Bundle?): ProcessProbeReport? {
        if (bundle == null) return null
        val roleName = bundle.getString(KEY_ROLE) ?: return null
        val role = ProcessRole.entries.firstOrNull { it.name == roleName } ?: return null
        val graphId = bundle.getString(KEY_GRAPH_ID)?.takeIf { it.isNotBlank() } ?: return null
        val service = bundle.getString(KEY_SERVICE)?.takeIf { it.isNotBlank() } ?: return null
        val sourceName = bundle.getString(KEY_PROCESS_NAME_SOURCE).orEmpty()
        val source = ProcessNameSource.entries.firstOrNull { it.name == sourceName }
            ?: ProcessNameSource.UNAVAILABLE
        return ProcessProbeReport(
            graph = GraphReport(
                role = role,
                processName = bundle.getString(KEY_PROCESS_NAME),
                processNameSource = source,
                graphId = graphId,
                businessResourcesInitialized = bundle.getBoolean(KEY_BUSINESS_INITIALIZED),
            ),
            isolatedProcess = bundle.getBoolean(KEY_ISOLATED_PROCESS),
            pid = bundle.getInt(KEY_PID),
            uid = bundle.getInt(KEY_UID),
            service = service,
            syncInitialized = bundle.getBoolean(KEY_SYNC_INITIALIZED),
            securityInitialized = bundle.getBoolean(KEY_SECURITY_INITIALIZED),
        )
    }

    fun reportFromGraph(
        graph: GraphReport,
        pid: Int,
        uid: Int,
        service: String,
        isolatedProcess: Boolean,
        syncInitialized: Boolean = false,
        securityInitialized: Boolean = false,
    ): ProcessProbeReport = ProcessProbeReport(
        graph = graph,
        isolatedProcess = isolatedProcess,
        pid = pid,
        uid = uid,
        service = service,
        syncInitialized = syncInitialized,
        securityInitialized = securityInitialized,
    )
}

data class ProcessProbeReport(
    val graph: GraphReport,
    val isolatedProcess: Boolean,
    val pid: Int,
    val uid: Int,
    val service: String,
    val syncInitialized: Boolean = false,
    val securityInitialized: Boolean = false,
) {
    val role: ProcessRole get() = graph.role
    val processName: String? get() = graph.processName
    val processNameSource: String get() = graph.processNameSource.name
    val graphId: String get() = graph.graphId
    val businessResourcesInitialized: Boolean get() = graph.businessResourcesInitialized
}
