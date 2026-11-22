package com.deepstudent.buildlogic.architecture

internal class CycleDetector {
    fun find(edges: Set<Pair<String, String>>): List<List<String>> {
        val adjacency = buildAdjacency(edges)
        val state = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val cycles = linkedSetOf<List<String>>()
        adjacency.keys.sorted().forEach { node ->
            if (state[node] == null) {
                visit(node, adjacency, state, stack, cycles)
            }
        }
        return cycles.toList()
    }

    private fun buildAdjacency(
        edges: Set<Pair<String, String>>
    ): Map<String, Set<String>> {
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        edges.forEach { (from, to) ->
            adjacency.getOrPut(from) { linkedSetOf() }.add(to)
            adjacency.getOrPut(to) { linkedSetOf() }
        }
        return adjacency
    }

    private fun visit(
        node: String,
        adjacency: Map<String, Set<String>>,
        state: MutableMap<String, Int>,
        stack: ArrayDeque<String>,
        cycles: MutableSet<List<String>>
    ) {
        state[node] = VISITING
        stack.addLast(node)
        for (target in adjacency[node].orEmpty()) {
            when (state[target]) {
                null -> visit(target, adjacency, state, stack, cycles)
                VISITING -> recordCycle(target, stack, cycles)
            }
        }
        stack.removeLast()
        state[node] = VISITED
    }

    private fun recordCycle(
        start: String,
        stack: ArrayDeque<String>,
        cycles: MutableSet<List<String>>
    ) {
        val values = stack.toList()
        val startIndex = values.indexOf(start)
        if (startIndex >= 0) {
            cycles += values.drop(startIndex).sorted()
        }
    }

    private companion object {
        const val VISITING = 1
        const val VISITED = 2
    }
}
