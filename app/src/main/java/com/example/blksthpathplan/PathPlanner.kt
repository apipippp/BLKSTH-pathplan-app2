package com.example.blksthpathplan

import java.util.*
import kotlin.math.abs
import kotlin.math.min

class PathPlanner {
    val startNode = 2
    val exitNodes = listOf(10, 12)

    val graph = mapOf(
        1 to listOf(2, 4), 2 to listOf(1, 3, 5), 3 to listOf(2, 6),
        4 to listOf(1, 5, 7), 5 to listOf(2, 4, 6, 8), 6 to listOf(3, 5, 9),
        7 to listOf(4, 8, 10), 8 to listOf(5, 7, 9, 11), 9 to listOf(6, 8, 12),
        10 to listOf(7, 11), 11 to listOf(10, 8, 12), 12 to listOf(9, 11)
    )

    val coords = mapOf(
        1 to Pair(0, 0), 2 to Pair(1, 0), 3 to Pair(2, 0),
        4 to Pair(0, 1), 5 to Pair(1, 1), 6 to Pair(2, 1),
        7 to Pair(0, 2), 8 to Pair(1, 2), 9 to Pair(2, 2),
        10 to Pair(0, 3), 11 to Pair(1, 3), 12 to Pair(2, 3)
    )

    data class State(val pos: Int, val taken: Set<Int>, val count: Int)

    fun heuristic(node: Int): Double {
        val (x1, y1) = coords[node] ?: return Double.MAX_VALUE
        var minH = Double.MAX_VALUE
        for (exitNode in exitNodes) {
            val (x2, y2) = coords[exitNode] ?: continue
            val h = (abs(x1 - x2) + abs(y1 - y2)).toDouble()
            if (h < minH) minH = h
        }
        return minH
    }

    fun findPath(r2: List<Int>, fake: Int?): Pair<List<Int>, Set<Int>>? {
        val pq = PriorityQueue<Triple<Double, State, List<Int>>>(compareBy { it.first })
        
        val pickableStart = r2.filter { it in listOf(1, 2, 3) || (graph[startNode]?.contains(it) ?: false) }
        val maxStartPickup = min(pickableStart.size, 2)
        
        for (r in 0..maxStartPickup) {
            val combos = getCombinations(pickableStart, r)
            for (combo in combos) {
                if (r2.contains(startNode) && !combo.contains(startNode)) continue
                val state = State(startNode, combo.toSet(), r)
                pq.add(Triple(0.0, state, emptyList()))
            }
        }

        val visited = mutableSetOf<Pair<Int, Set<Int>>>()

        while (pq.isNotEmpty()) {
            val triple = pq.poll() ?: continue
            val (_, state, path) = triple
            val (pos, taken, count) = state

            if (!visited.add(pos to taken)) continue

            val currentPath = path + pos

            if (pos in exitNodes && count == 2) {
                return currentPath to taken
            }

            graph[pos]?.forEach { n ->
                if (n != fake) {
                    if (!(r2.contains(n) && !taken.contains(n))) {
                        val pickable = r2.filter { !taken.contains(it) && (graph[n]?.contains(it) ?: false) }
                        val maxPickup = min(pickable.size, 2 - count)
                        
                        for (r in 0..maxPickup) {
                            val combos = getCombinations(pickable, r)
                            for (combo in combos) {
                                val newTaken = taken + combo
                                val newCount = count + r
                                val g = currentPath.size.toDouble()
                                val h = heuristic(n)
                                pq.add(Triple(g + h, State(n, newTaken, newCount), currentPath))
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun <T> getCombinations(list: List<T>, n: Int): List<List<T>> {
        if (n == 0) return listOf(emptyList())
        if (list.isEmpty()) return emptyList()
        val result = mutableListOf<List<T>>()
        for (i in list.indices) {
            val head = list[i]
            val tail = list.subList(i + 1, list.size)
            for (c in getCombinations(tail, n - 1)) {
                result.add(listOf(head) + c)
            }
        }
        return result
    }
}
