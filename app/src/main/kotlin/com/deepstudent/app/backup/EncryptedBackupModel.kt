package com.deepstudent.app.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepstudent.services.backup.api.RestorePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface EncryptedBackupState {
    data object Idle : EncryptedBackupState
    data class Password(val intent: EncryptedBackupIntent) : EncryptedBackupState
    data object Working : EncryptedBackupState
    data class Report(val plan: RestorePlan) : EncryptedBackupState
    data class ReadyToSave(val name: String, val mime: String) : EncryptedBackupState
    data class Done(val message: String) : EncryptedBackupState
    data class Failed(val message: String, val canRetry: Boolean = false) : EncryptedBackupState
}

/** SAF grants one source / newly created destination. Only one transient operation owns its scratch. */
internal class EncryptedBackupModel(actions: EncryptedBackupActions) : ViewModel() {
    private val workflow = EncryptedBackupWorkflow(object : EncryptedBackupWorkflowPort<Uri, EncryptedBackupPrepared> {
        override suspend fun prepare(source: Uri, intent: EncryptedBackupIntent, password: CharArray) =
            actions.prepare(source, intent, password)
        override fun plan(item: EncryptedBackupPrepared) = item.plan
        override fun saveState(item: EncryptedBackupPrepared) = encryptedSaveState(item)
        override suspend fun schedule(item: EncryptedBackupPrepared) = actions.schedule(item)
        override suspend fun export(item: EncryptedBackupPrepared, destination: Uri) = actions.export(item, destination)
        override fun release(item: EncryptedBackupPrepared) = item.close()
    }, viewModelScope)

    val state = workflow.state
    fun select(source: Uri, intent: EncryptedBackupIntent) = workflow.select(source, intent)
    fun authenticate(password: CharArray) = workflow.authenticate(password)
    fun retry() = workflow.retry()
    fun confirmRestore() = workflow.confirmRestore()
    fun save(destination: Uri) = workflow.save(destination)
    fun cancel() = workflow.cancel()

    override fun onCleared() {
        workflow.close()
        super.onCleared()
    }
}

/** Typed workflow port keeps platform URI/file operations out of the UI and permits plain JVM fakes. */
internal interface EncryptedBackupWorkflowPort<Source : Any, Prepared : Any> {
    suspend fun prepare(source: Source, intent: EncryptedBackupIntent, password: CharArray): Prepared
    fun plan(item: Prepared): RestorePlan?
    fun saveState(item: Prepared): EncryptedBackupState.ReadyToSave
    suspend fun schedule(item: Prepared): Boolean
    suspend fun export(item: Prepared, destination: Source)
    fun release(item: Prepared)
}

/** All transitions run on the owning UI scope; the selected source, never the password, may be retried. */
internal class EncryptedBackupWorkflow<Source : Any, Prepared : Any>(
    private val actions: EncryptedBackupWorkflowPort<Source, Prepared>,
    private val scope: CoroutineScope,
) {
    private data class Selection<Source>(val source: Source, val intent: EncryptedBackupIntent)

    private val mutable = MutableStateFlow<EncryptedBackupState>(EncryptedBackupState.Idle)
    val state = mutable.asStateFlow()
    private var selected: Selection<Source>? = null
    private var prepared: Prepared? = null
    private var task: Job? = null

    fun select(source: Source, intent: EncryptedBackupIntent) {
        if (busy) return
        releasePrepared()
        selected = Selection(source, intent)
        mutable.value = EncryptedBackupState.Password(intent)
    }

    fun authenticate(password: CharArray) {
        val selection = selected
        if (busy || selection == null || mutable.value != EncryptedBackupState.Password(selection.intent) || password.isEmpty()) {
            password.fill('\u0000')
            return
        }
        runOperation {
            try {
                val result = actions.prepare(selection.source, selection.intent, password)
                prepared = result
                currentCoroutineContext().ensureActive()
                val next = actions.plan(result)?.let { EncryptedBackupState.Report(it) } ?: actions.saveState(result)
                selected = null
                next
            } finally { password.fill('\u0000') }
        }.invokeOnCompletion { password.fill('\u0000') }
    }

    fun retry() {
        if (!busy && (mutable.value as? EncryptedBackupState.Failed)?.canRetry == true) {
            selected?.let { mutable.value = EncryptedBackupState.Password(it.intent) }
        }
    }

    fun confirmRestore() {
        val report = mutable.value as? EncryptedBackupState.Report ?: return
        val item = prepared ?: return
        if (busy || !report.plan.canStage) return
        runOperation {
            if (!actions.schedule(item)) report
            else {
                releasePrepared()
                EncryptedBackupState.Done("恢复已安排；下次冷启动校验并切换。")
            }
        }
    }

    fun save(destination: Source) {
        val item = prepared ?: return
        if (busy || mutable.value !is EncryptedBackupState.ReadyToSave) return
        runOperation {
            actions.export(item, destination)
            releasePrepared()
            EncryptedBackupState.Done("已保存加密备份。原备份未修改。")
        }
    }

    fun cancel() {
        if (busy) task?.cancel()
        else {
            selected = null
            releasePrepared()
            mutable.value = EncryptedBackupState.Idle
        }
    }

    fun close() {
        selected = null
        task?.cancel()
        releasePrepared()
    }

    private val busy get() = task?.isCompleted == false

    private fun runOperation(block: suspend () -> EncryptedBackupState): Job {
        mutable.value = EncryptedBackupState.Working
        // Register ownership before starting, including cancellation before the coroutine first runs.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                mutable.value = block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                releasePrepared()
                mutable.value = EncryptedBackupState.Failed(encryptedBackupMessage(error), canRetry = selected != null)
            }
        }
        task = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && task === job) {
                selected = null
                releasePrepared()
                mutable.value = EncryptedBackupState.Idle
            }
        }
        job.start()
        return job
    }

    private fun releasePrepared() {
        val item = prepared ?: return
        prepared = null
        actions.release(item)
    }
}

private fun encryptedSaveState(item: EncryptedBackupPrepared): EncryptedBackupState.ReadyToSave = if (item.artifact.extension == "zip") {
    EncryptedBackupState.ReadyToSave("deepstudent-encrypted.zip", "application/zip")
} else {
    EncryptedBackupState.ReadyToSave("deepstudent-encrypted.dsbk", "application/octet-stream")
}

private fun encryptedBackupMessage(error: Exception): String = when (uiFailure(error)) {
    EncryptedBackupFileError.AUTHENTICATION_FAILED -> "密码不正确，或加密内容已损坏。没有安排恢复。"
    EncryptedBackupFileError.V1_CONVERSION_REQUIRED -> "此旧版加密备份超出直接打开的大小限制。请先用桌面 v1 转 v2 工具转换，再选转换后的文件。"
    EncryptedBackupFileError.ZIP_INVALID, EncryptedBackupFileError.ZIP_CHANGED -> "备份内容或清单校验不通过。没有安排恢复。"
    EncryptedBackupFileError.CRYPTO_REJECTED -> "此备份的加密版本、参数或大小超出支持范围。"
    else -> "无法处理该备份。请检查文件、系统授权和剩余空间。"
}

private fun uiFailure(error: Exception): EncryptedBackupFileError? = when (error) {
    is EncryptedBackupFileException -> error.error
    is PortableFileException -> portableUiFailure(error.error)
    else -> null
}

private fun portableUiFailure(error: PortableFileError): EncryptedBackupFileError? = when {
    error == PortableFileError.AUTHENTICATION_FAILED -> EncryptedBackupFileError.AUTHENTICATION_FAILED
    error == PortableFileError.V1_CONVERSION_REQUIRED -> EncryptedBackupFileError.V1_CONVERSION_REQUIRED
    error in setOf(PortableFileError.LIMIT_EXCEEDED, PortableFileError.CRYPTO_REJECTED) -> EncryptedBackupFileError.CRYPTO_REJECTED
    error == PortableFileError.SOURCE_CHANGED -> EncryptedBackupFileError.ZIP_CHANGED
    error in rejectedContent -> EncryptedBackupFileError.ZIP_INVALID
    else -> null
}

private val rejectedContent = setOf(PortableFileError.VERIFICATION_FAILED, PortableFileError.PAYLOAD_MISMATCH,
    PortableFileError.ZIP_INVALID, PortableFileError.ENTRY_CONFLICT, PortableFileError.ORIGINAL_MANIFEST_INVALID,
    PortableFileError.OUTER_MANIFEST_INVALID, PortableFileError.LAYER_VIOLATION)
