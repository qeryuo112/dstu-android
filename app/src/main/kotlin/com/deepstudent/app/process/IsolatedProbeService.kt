package com.deepstudent.app.process

import com.deepstudent.app.DstuApplication
import com.deepstudent.app.graph.AppGraph
import com.deepstudent.app.graph.ProcessRole

/**
 * Runs in the isolatedProcess probe declared by the app manifest. Reports only
 * the process-local graph role; it never exposes file, SQL or command access.
 */
class IsolatedProbeService : ProbeServiceBase() {
    override val serviceLabel: String = "isolated"
    override val expectedRole: ProcessRole = ProcessRole.ISOLATED

    override fun graphOf(application: DstuApplication): AppGraph = application.isolatedGraph
}
