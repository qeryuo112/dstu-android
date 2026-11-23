package com.deepstudent.app.runtime

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.scheduling.LocalSchedule
import com.deepstudent.app.scheduling.SchedulePurpose
import com.deepstudent.app.scheduling.ScheduledDraft
import com.deepstudent.features.notes.data.NoteTaskIdentity
import com.deepstudent.platform.background.scheduling.ScheduleId
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import java.io.File
import java.time.Instant
import java.util.UUID

internal class ScheduledDeviceFixture {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = DstuApplication.from(instrumentation.targetContext)
    val gate = File(app.cacheDir, "s225-hold-scheduled-write")
    val marker = File(app.cacheDir, "s225-force-stop-marker")
    val schedules get() = app.schedules
    val title get() = "S225 scheduled note"

    init {
        check(app.packageName == "com.deepstudent.app.s2device")
    }

    suspend fun createNote(prefix: String): LocalSchedule = schedules.register(
        LocalSchedule(
            ScheduleId.parse("$prefix-${UUID.randomUUID()}"),
            ScheduledDraft(title, prefix, SchedulePurpose.NOTE),
            Instant.now().minusSeconds(1),
            requiresIdle = true,
        ),
    )

    suspend fun task(row: LocalSchedule): RuntimeSnapshot? = app.runtime().ledger.recoverable(null, 1000).firstOrNull {
        it.submission.dedupeKey == "schedule:${row.id.value}:${row.occurrence.value}"
    }

    suspend fun scheduledJob(row: LocalSchedule): JobInfo {
        var found: JobInfo? = null
        awaitRuntime {
            val works = schedules.work.snapshot(row.id).filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            if (works.size != 1) return@awaitRuntime false
            val manager = app.getSystemService(JobScheduler::class.java)
            val jobs = if (Build.VERSION.SDK_INT >= 34) manager.forNamespace(NAMESPACE).allPendingJobs else manager.allPendingJobs
            val workId = works.single().workId.toString()
            found = jobs.singleOrNull { it.extras.getString("EXTRA_WORK_SPEC_ID") == workId }
            found != null
        }
        return checkNotNull(found)
    }

    fun issue(action: String, job: JobInfo): String {
        check(action == "run -f" || action == "timeout")
        return shell("cmd jobscheduler $action -u 0 -n $NAMESPACE ${app.packageName} ${job.id}")
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    fun evidence(message: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
    }

    suspend fun hasFact(snapshot: RuntimeSnapshot): Boolean =
        app.storage().noteTasks.find(NoteTaskIdentity(snapshot.taskId.value, snapshot.ownership.generation)) != null

    companion object {
        const val NAMESPACE = "androidx.work.systemjobscheduler"
    }
}
