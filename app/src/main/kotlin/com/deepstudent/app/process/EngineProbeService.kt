package com.deepstudent.app.process

import com.deepstudent.app.DstuApplication
import com.deepstudent.app.graph.AppGraph
import com.deepstudent.app.graph.ProcessRole

/**
 * Runs in the real ":engine" process. Reports only the process-local graph
 * role and whether business resources were initialized.
 */
class EngineProbeService : ProbeServiceBase() {
    override val serviceLabel: String = "engine"
    override val expectedRole: ProcessRole = ProcessRole.ENGINE

    override fun graphOf(application: DstuApplication): AppGraph = application.engineGraph
}
