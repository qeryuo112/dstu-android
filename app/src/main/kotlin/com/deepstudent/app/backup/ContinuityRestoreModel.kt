package com.deepstudent.app.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepstudent.services.backup.backend.ContinuityGrantException
import com.deepstudent.services.backup.backend.ContinuityGrantFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ContinuityScreenState(
    val target: ContinuityTarget? = null,
    val selected: Set<ContinuityDocument> = emptySet(),
    val fingerprint: String = "",
    val review: ContinuityReview? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

internal class ContinuityRestoreModel(private val actions: ContinuityEntryActions) : ViewModel() {
    private val mutable = MutableStateFlow(ContinuityScreenState())
    val state = mutable.asStateFlow()
    private val docs = mutableMapOf<ContinuityDocument, Uri>()
    private var task: Job? = null

    fun issue() = run {
        val target = actions.issueTarget()
        mutable.update { it.copy(target = target, review = null) }
    }

    fun renew() = run {
        val target = actions.renewTarget()
        docs.clear()
        mutable.value = ContinuityScreenState(target = target, busy = true)
    }

    fun select(kind: ContinuityDocument, uri: Uri) {
        if (state.value.busy) return
        docs[kind] = uri
        mutable.update { it.copy(selected = docs.keys.toSet(), review = null, message = null) }
    }

    fun setPin(value: String) {
        if (state.value.busy) return
        mutable.update { it.copy(fingerprint = value.take(100), review = null, message = null) }
    }

    fun review() = run {
        val fingerprint = normalizedPin()
        if (!fingerprint.matches(Regex("[a-f0-9]{64}"))) failContinuity(ContinuityRestoreError.AUTHORITY_PIN_MISMATCH)
        val report = actions.review(docs.toMap(), fingerprint)
        mutable.update { it.copy(review = report) }
    }

    fun schedule() {
        val review = state.value.review ?: return
        run {
            val queued = actions.schedule(docs.toMap(), normalizedPin(), review)
            if (queued) docs.clear()
            mutable.update { current -> current.copy(selected = docs.keys.toSet(), review = null,
                fingerprint = if (queued) "" else current.fingerprint,
                message = if (queued) "连续迁移已安排；下次冷启动才校验仓库、接管并切换。" else "未安排连续迁移，请重新核对。") }
        }
    }

    fun cancel() { task?.cancel() }

    private fun normalizedPin() = state.value.fingerprint.trim().lowercase(java.util.Locale.ROOT)

    private fun run(work: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        task = viewModelScope.launch {
            try { work() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(review = null, message = userMessage(error)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

private fun userMessage(error: Exception): String = when (error) {
    is ContinuityRestoreException -> error.error.userMessage
    is ContinuityGrantException -> when (error.reason) {
        ContinuityGrantFailure.EXPIRED, ContinuityGrantFailure.NOT_YET_VALID -> "授权不在有效期内。请向仓库管理员取得有效授权。"
        ContinuityGrantFailure.INVALID_SIGNATURE, ContinuityGrantFailure.INVALID_KEY -> "授权签名无法用管理员公钥验证。"
        ContinuityGrantFailure.BINDING_MISMATCH -> "授权与本机申请、仓库或备份不一致。"
        else -> "授权无法校验。请检查所选文件。"
    }
    else -> "无法核对接管材料。请检查配对、文件和剩余空间。"
}
