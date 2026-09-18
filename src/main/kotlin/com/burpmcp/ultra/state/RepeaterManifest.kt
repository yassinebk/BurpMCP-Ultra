package com.burpmcp.ultra.state

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

class RepeaterManifest(private val capacity: Int = 5_000) {
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val group: String?,
        val caption: String,
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val method: String,
        val path: String,
        val purpose: String? = null,
        val sourceHistoryId: Int? = null,
        val createdAt: String = Instant.now().toString()
    )

    private val entries = ConcurrentLinkedDeque<Entry>()

    fun add(entry: Entry): Entry {
        entries.addFirst(entry)
        while (entries.size > capacity) entries.pollLast()
        return entry
    }

    fun list(group: String? = null, limit: Int = 100): List<Entry> = entries.asSequence()
        .filter { group == null || it.group.equals(group, ignoreCase = true) }
        .take(limit.coerceIn(1, 1_000)).toList()

    fun groups(): Map<String, Int> = entries.mapNotNull { it.group }.groupingBy { it }.eachCount().toSortedMap()

    fun clear(group: String? = null): Int {
        if (group == null) {
            val count = entries.size
            entries.clear()
            return count
        }
        val selected = entries.filter { it.group.equals(group, ignoreCase = true) }
        selected.forEach(entries::remove)
        return selected.size
    }
}
