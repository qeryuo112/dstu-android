package com.deepstudent.app.execution

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.PreparedShell
import com.deepstudent.services.execution.api.ShellAdapter
import com.deepstudent.services.execution.api.ShellBudget
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellTarget
import com.deepstudent.services.runtime.api.CapabilityBinding
import com.deepstudent.services.runtime.api.CapabilityBudget
import com.deepstudent.services.runtime.api.CapabilityOutcome
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.backend.CapabilityBroker
import kotlinx.coroutines.CancellationException

/** App composition joins four platform adapters to the runtime's one-invocation gate. */
internal class ShellRouter(adapters: Map<ShellTarget, ShellAdapter>) {
    private val routes = adapters.toMap()

    suspend fun prepare(command: ShellCommand): RoutedShell? {
        val adapter = routes[command.target] ?: return null
        val prepared = adapter.prepare(command) ?: return null
        check(prepared.scope.target == command.target) { "unexpected shell target" }
        return RoutedShell(command.text, prepared)
    }
}

internal class RoutedShell(private val command: String, private val prepared: PreparedShell) {
    val scope = prepared.scope

    suspend fun run(context: RuntimeTaskContext, broker: CapabilityBroker): ShellResult {
        val key = RuntimeRecordKey::parse
        val digest = Digests.sha256HexUtf8(
            JsonValue.Array(
                listOf(
                    scope.target.name,
                    scope.uid.toString(),
                    scope.resource,
                    scope.directory,
                    scope.ownerUid?.toString().orEmpty(),
                    command,
                ).map(JsonValue::text),
            ).canonical(),
        )
        val binding = CapabilityBinding(
            key("workspace.console"),
            key(scope.target.name),
            scope.uid,
            key(scope.resource),
            digest,
            CapabilityBudget(ShellBudget.DURATION_MILLIS, ShellBudget.TOTAL_BYTES),
        )
        val review = "起始目录：${scope.directory}\n命令拥有该执行身份的访问权限，起始目录不是沙箱。\n\n$command"
        return when (val outcome = broker.execute(context, key("shell.execute"), binding, review) { safelyRun(context) }) {
            is CapabilityOutcome.Blocked -> ShellResult.Blocked(outcome.reason.name)
            is CapabilityOutcome.Performed -> outcome.result
        }
    }

    private suspend fun safelyRun(context: RuntimeTaskContext): ShellResult = try {
        val result = prepared.run(context.taskId.value)
        if (result is ShellResult.Exited && result.receipt.observedUid != scope.uid) {
            ShellResult.Unavailable(ExecutionFailureCode.UID_MISMATCH)
        } else {
            result
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ShellResult.Unavailable(ExecutionFailureCode.PROCESS_START_FAILED)
    } catch (unavailable: LinkageError) {
        ShellResult.Unavailable(ExecutionFailureCode.PROCESS_START_FAILED)
    }
}
