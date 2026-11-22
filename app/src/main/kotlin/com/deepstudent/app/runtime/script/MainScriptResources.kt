package com.deepstudent.app.runtime.script

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.engines.quickjs.QuickJsScriptEngine
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.app.storage.AndroidDatabaseLog
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.script.backend.PtcScriptHandler
import com.deepstudent.services.script.data.SqlScriptReceiptStore
import java.io.File

internal class MainScriptResources private constructor(private val database: SqlDatabase) : AutoCloseable {
    val receipts = SqlScriptReceiptStore(database)
    val completionSource = ScriptCompletionSource(receipts)
    val handler = ScriptRuntimeHandler(PtcScriptHandler(QuickJsScriptEngine(), MinimalScriptTools.policy,
        MinimalScriptTools, receipts, AndroidOperationLogger))

    override fun close() { database.close() }

    companion object {
        val KIND = TaskKind.parse("script.ptc_minimal")
        fun open(parent: File): MainScriptResources {
            val database = DatabaseRegistry.openLocal(File(parent, "script.db"), log = AndroidDatabaseLog)
            return try { MainScriptResources(database) }
            catch (failure: Exception) { database.close(); throw failure }
        }
    }
}
