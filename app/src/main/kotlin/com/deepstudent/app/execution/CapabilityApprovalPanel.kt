package com.deepstudent.app.execution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.deepstudent.services.runtime.api.CapabilityApprovals
import com.deepstudent.services.runtime.api.CapabilityTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
internal fun CapabilityApprovalPanel(approvals: CapabilityApprovals) {
    val scope = rememberCoroutineScope()
    var feedback by remember(approvals) { mutableStateOf<String?>(null) }
    val source = remember(approvals) { approvals.pending().catch { feedback = "无法读取审批账本" } }
    val pending by source.collectAsState(initial = emptyList())
    LazyColumn(Modifier.testTag("capability-approvals").padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(feedback ?: if (pending.isEmpty()) "暂无待审批请求" else "仅允许此次调用") }
        items(pending, key = { it.address.id.value }) { ticket ->
            CapabilityApprovalCard(ticket) { allow ->
                scope.launch {
                    feedback = try {
                        if (approvals.decide(ticket.address, allow)) null else "请求已失效或已处理"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        "审批未提交"
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun CapabilityApprovalCard(ticket: CapabilityTicket, onDecision: (Boolean) -> Unit) {
    val binding = ticket.binding
    Card(Modifier.fillMaxWidth().testTag("capability-ticket")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ticket.operation.value, style = MaterialTheme.typography.titleSmall)
            Text("调用方：${binding.caller.value} · ${binding.target.value} · UID ${binding.uid}")
            Text("资源：${binding.resource.value}")
            Text("预算：${binding.budget.durationMillis} ms · ${binding.budget.outputBytes} 字节输出")
            Text("请求指纹：${binding.fingerprint.take(16)}")
            ticket.reviewText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row {
                TextButton({ onDecision(false) }, Modifier.testTag("capability-deny")) { Text("拒绝") }
                TextButton({ onDecision(true) }, Modifier.testTag("capability-allow")) { Text("仅允许一次") }
            }
        }
    }
}
