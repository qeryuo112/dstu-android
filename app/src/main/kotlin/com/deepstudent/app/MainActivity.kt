package com.deepstudent.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deepstudent.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graphId = DstuApplication.from(this).mainGraph.graphId
        Log.i("DstuBootstrap", "{\"owner\":\"app\",\"event\":\"activity.created\",\"graphId\":\"$graphId\"}")
        val application = DstuApplication.from(this)
        setContent {
            MainScreen(
                loadWorkspace = { application.workspace() },
                retryState = application.migrationRetryState,
                retryMigration = { application.retryMigration() },
                restoreStatus = application.backupRestore.status,
            )
        }
    }
}
