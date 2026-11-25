package com.deepstudent.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.deepstudent.app.diagnostics.S231Host
import com.deepstudent.app.scheduling.refreshVisibleSchedules
import com.deepstudent.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    private var scheduleLaunch by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeIntent(intent)
        enableEdgeToEdge()
        val graphId = DstuApplication.from(this).mainGraph.graphId
        Log.i("DstuBootstrap", "{\"owner\":\"app\",\"event\":\"activity.created\",\"graphId\":\"$graphId\"}")
        val application = DstuApplication.from(this)
        setContent {
            S231Host {
                MainScreen(
                    loadWorkspace = { application.workspace() },
                    retryState = application.migrationRetryState,
                    retryMigration = { application.retryMigration() },
                    restoreStatus = application.backupRestore.status,
                    openSchedules = scheduleLaunch,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        if (intent?.action == OPEN_SCHEDULES) scheduleLaunch += 1
    }

    override fun onResume() {
        super.onResume()
        val app = DstuApplication.from(this)
        app.schedulingScope.refreshVisibleSchedules { app.schedules }
    }

    companion object {
        internal const val OPEN_SCHEDULES = "com.deepstudent.app.OPEN_SCHEDULES"
    }
}
