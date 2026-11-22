package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.TaskCursor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DutyReconcilerTest {
    @Test
    fun catalogDoesNotGrantResponsibilities() = runBlocking {
        val registry = DutyRegistry()
        val active = snapshot(duty("task"))
        val catalog = catalog(listOf(active))
        assertTrue(DutyReconciler(registry, query(active), catalog).reconcile())
        assertTrue(registry.duties.value.isEmpty())
    }

    @Test
    fun existingReferencesRequireCurrentNonterminalGeneration() = runBlocking {
        val registry = DutyRegistry()
        val one = duty("one")
        val two = duty("two")
        registry.acquire(one)
        registry.acquire(two)
        val query = object : RuntimeTaskQuery {
            override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? =
                if (taskId == one.taskId) snapshot(one) else snapshot(two.copy(generation = 2))
            override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = emptyList()
        }
        assertFalse(DutyReconciler(registry, query, catalog(emptyList())).reconcile())
        assertEquals(listOf(one), registry.duties.value)
    }

    private fun catalog(items: List<RuntimeSnapshot>): RuntimeTaskCatalog = object : RuntimeTaskCatalog {
        override suspend fun activeSnapshots(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> {
            assertEquals(null, afterTaskId)
            assertEquals(1, limit)
            return items.take(limit)
        }
    }
}
