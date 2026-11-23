package com.deepstudent.app.runtime

import android.content.Context
import com.deepstudent.app.BuildConfig
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

/** Only the isolated debug package can pause at the durable pre-write boundary for device tests. */
internal object ScheduledNoteExecution {
    @Volatile private var testGate: File? = null

    fun configureFixture(context: Context) {
        if (BuildConfig.DEBUG && context.packageName == "com.deepstudent.app.s2device") {
            testGate = File(context.cacheDir, "s225-hold-scheduled-write")
        }
    }

    suspend fun beforeWrite(context: RuntimeTaskContext) {
        val gate = testGate ?: return
        while (gate.isFile) delay(25)
        currentCoroutineContext().ensureActive()
        check(context.generation > 0)
    }
}
