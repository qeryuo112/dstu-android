package com.deepstudent.app.environments

import com.deepstudent.features.environments.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LazyEnvironmentOwnerTest {
    @Test fun factoryIsLazyAndConcurrentOpenIsSingleAndCloseIsOnce() = runTest {
        var opens = 0
        var closes = 0
        val gate = CompletableDeferred<Unit>()
        val manager = TestEnvironmentManager()
        val owner = LazyEnvironmentOwner { opens++; gate.await(); OpenedEnvironment(manager) { closes++ } }
        assertFalse(owner.isInitialized)
        val first = async { owner.get() }
        val second = async { owner.get() }
        runCurrent()
        assertEquals(1, opens)
        gate.complete(Unit)
        assertSame(manager, first.await())
        assertSame(manager, second.await())
        assertTrue(owner.isInitialized)
        owner.close()
        owner.close()
        assertEquals(1, closes)
        try { owner.get(); fail("closed owner must not reopen") } catch (_: IllegalStateException) { }
    }

    @Test fun failedFactoryCanRetryAndClosingUnusedOwnerDoesNotOpenPlatform() = runTest {
        var attempts = 0
        val manager = TestEnvironmentManager()
        val owner = LazyEnvironmentOwner {
            if (attempts++ == 0) error("unavailable")
            OpenedEnvironment(manager) {}
        }
        try { owner.get(); fail("first open fails") } catch (_: IllegalStateException) { }
        assertFalse(owner.isInitialized)
        assertSame(manager, owner.get())
        owner.close()
        val unused = LazyEnvironmentOwner { error("must remain lazy") }
        unused.close()
        assertFalse(unused.isInitialized)
    }
}

private class TestEnvironmentManager : EnvironmentManager {
    override val state = MutableStateFlow(EnvironmentSnapshot())
    override suspend fun installRootfs(manifest: RootfsManifest, source: RootfsArchiveSource): EnvironmentTaskId = error("no I/O in owner test")
    override suspend fun installPackage(recipeId: String): EnvironmentTaskId = error("no package mutation in owner test")
    override suspend fun retry(source: RootfsArchiveSource?): EnvironmentTaskId = error("no retry in owner test")
    override suspend fun cancel(taskId: EnvironmentTaskId) = Unit
}
