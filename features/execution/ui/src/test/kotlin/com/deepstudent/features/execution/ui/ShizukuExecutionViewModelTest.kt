package com.deepstudent.features.execution.ui

import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionReceipt
import com.deepstudent.services.execution.api.ExecutionAudit
import com.deepstudent.services.execution.api.ExecutionIdentity
import com.deepstudent.services.execution.api.ExecutionOutput
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuExecutionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun typedCommandsFlowThroughSystemAndLeaveNoRunningState() = runTest(dispatcher) {
        val fake = FakeSystem()
        val vm = ShizukuExecutionViewModel(fake) { "fixed-task" }
        vm.verifyIdentity()
        advanceUntilIdle()
        assertEquals("fixed-task", fake.lastRequest?.taskId)
        assertEquals(ExecutionCommand.Identity, fake.lastRequest?.command)
        assertNull(vm.uiState.value.runningCommand)
        assertEquals(1, fake.executeCalls)
    }

    @Test
    fun cancellationEndsTheOwnedJobAndPublishesCancelledResult() = runTest(dispatcher) {
        val fake = FakeSystem().apply { hold = true }
        val vm = ShizukuExecutionViewModel(fake)
        vm.verifyIdentity()
        advanceUntilIdle()
        assertEquals(ExecutionCommand.Identity, vm.uiState.value.runningCommand)
        vm.cancelExecution()
        advanceUntilIdle()
        assertNull(vm.uiState.value.runningCommand)
        assertEquals(
            ExecutionFailureCode.CANCELLED,
            (vm.uiState.value.result as ExecutionResult.Failure).failure.code,
        )
    }

    @Test
    fun statusFlowIsObservedWithoutClearingTheSharedSystem() = runTest(dispatcher) {
        val fake = FakeSystem(
            initial = ShizukuStatus(state = ShizukuConnectionState.NOT_RUNNING),
        )
        val vm = ShizukuExecutionViewModel(fake)
        advanceUntilIdle()
        assertEquals(ShizukuConnectionState.NOT_RUNNING, vm.uiState.value.status.state)
        fake.publish(
            ShizukuStatus(
                state = ShizukuConnectionState.BINDER_DEAD,
                diagnostic = ExecutionFailureCode.BINDER_DEAD,
            ),
        )
        advanceUntilIdle()
        assertEquals(ShizukuConnectionState.BINDER_DEAD, vm.uiState.value.status.state)
        assertEquals(0, fake.stopCalls)
    }

    @Test
    fun permissionRequestIsExplicitAndClearsInFlightState() = runTest(dispatcher) {
        val fake = FakeSystem(
            initial = ShizukuStatus(
                state = ShizukuConnectionState.PERMISSION_REQUIRED,
                backendUid = 2000,
            ),
        )
        val vm = ShizukuExecutionViewModel(fake)
        advanceUntilIdle()
        assertEquals(0, fake.permissionCalls)
        vm.requestPermission()
        advanceUntilIdle()
        assertEquals(1, fake.permissionCalls)
        assertEquals(false, vm.uiState.value.permissionRequestInFlight)
    }

    @Test
    fun cancelBeforeCoroutineStartsClearsBusyAndAllowsTheNextCommand() = runTest(dispatcher) {
        val fake = FakeSystem().apply { hold = true }
        val vm = ShizukuExecutionViewModel(fake)
        vm.verifyIdentity()
        vm.cancelExecution()
        advanceUntilIdle()
        assertNull(vm.uiState.value.runningCommand)
        assertEquals(0, fake.executeCalls)
        fake.hold = false
        vm.listPackages()
        advanceUntilIdle()
        assertEquals(ExecutionCommand.PackageList, fake.lastRequest?.command)
    }

    @Test
    fun repeatedActionCannotStartASecondOwnerAndDeniedStateCannotExecute() = runTest(dispatcher) {
        val fake = FakeSystem().apply { hold = true }
        val vm = ShizukuExecutionViewModel(fake)
        vm.verifyIdentity()
        vm.listPackages()
        runCurrent()
        assertEquals(1, fake.executeCalls)
        assertEquals(ExecutionCommand.Identity, fake.lastRequest?.command)
        vm.cancelExecution()
        advanceUntilIdle()
        fake.publish(ShizukuStatus(state = ShizukuConnectionState.REVOKED))
        vm.listPackages()
        advanceUntilIdle()
        assertEquals(1, fake.executeCalls)
        assertNull(vm.uiState.value.runningCommand)
    }

    @Test
    fun deniedPermissionCallbackClearsSpinnerAndARepeatRequestIsExplicit() = runTest(dispatcher) {
        val fake = FakeSystem(initial = ShizukuStatus(state = ShizukuConnectionState.PERMISSION_REQUIRED))
        val vm = ShizukuExecutionViewModel(fake)
        fake.permissionResult = false
        vm.requestPermission()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.permissionRequestInFlight)
        assertEquals(1, fake.permissionCalls)
        vm.requestPermission()
        advanceUntilIdle()
        assertEquals(2, fake.permissionCalls)
        assertEquals(0, fake.executeCalls)
    }

    private class FakeSystem(
        initial: ShizukuStatus = ShizukuStatus(
            state = ShizukuConnectionState.READY,
            backendUid = 2000,
            observedUid = 2000,
        ),
    ) : ExecutionSystem {
        private val state = MutableStateFlow(initial)
        override val status: StateFlow<ShizukuStatus> = state
        var lastRequest: ExecutionRequest? = null
        var executeCalls = 0
        var permissionCalls = 0
        var permissionResult = true
        var stopCalls = 0
        var hold = false

        fun publish(value: ShizukuStatus) {
            state.value = value
        }

        override fun start() = Unit

        override fun stop() {
            stopCalls++
        }

        override suspend fun refresh() = Unit

        override fun requestPermission(onResult: (Boolean) -> Unit) {
            permissionCalls++
            onResult(permissionResult)
        }

        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            executeCalls++
            lastRequest = request
            if (hold) awaitCancellation()
            delay(1)
            return ExecutionResult.Success(
                ExecutionReceipt(
                    audit = ExecutionAudit(
                        request.taskId,
                        request.callerId,
                        request.command.commandId,
                        request.command.argv,
                    ),
                    identity = ExecutionIdentity(2000, 2000),
                    output = ExecutionOutput("uid=2000(shell)\n", "", false, false),
                    exitCode = 0,
                    durationMillis = 2,
                ),
            )
        }

        override fun close() = Unit
    }
}
