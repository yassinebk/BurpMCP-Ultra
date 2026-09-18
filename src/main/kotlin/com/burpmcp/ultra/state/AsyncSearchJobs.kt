package com.burpmcp.ultra.state

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class AsyncSearchJobs(private val maxJobs: Int = 100) : AutoCloseable {
    data class Snapshot(
        val id: String,
        val status: String,
        val createdAt: String,
        val result: JsonObject? = null,
        val error: String? = null
    )

    private data class Job(
        val id: String,
        val createdAt: String,
        @Volatile var status: String = "queued",
        @Volatile var result: JsonObject? = null,
        @Volatile var error: String? = null,
        @Volatile var future: Future<*>? = null
    )

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "burpmcp-index-search").apply { isDaemon = true }
    }
    private val jobs = ConcurrentHashMap<String, Job>()

    fun submit(operation: () -> JsonObject): String {
        prune()
        val job = Job(UUID.randomUUID().toString(), Instant.now().toString())
        jobs[job.id] = job
        job.future = executor.submit {
            job.status = "running"
            try {
                job.result = operation()
                job.status = if (Thread.currentThread().isInterrupted) "cancelled" else "completed"
            } catch (e: InterruptedException) {
                job.status = "cancelled"
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                job.error = e.message ?: e.javaClass.simpleName
                job.status = "failed"
            }
        }
        return job.id
    }

    fun get(id: String): Snapshot? = jobs[id]?.let { Snapshot(it.id, it.status, it.createdAt, it.result, it.error) }

    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        val cancelled = job.future?.cancel(true) ?: false
        if (cancelled) job.status = "cancelled"
        return cancelled
    }

    private fun prune() {
        if (jobs.size < maxJobs) return
        jobs.values.filter { it.status in setOf("completed", "failed", "cancelled") }
            .sortedBy { it.createdAt }.take(jobs.size - maxJobs + 1).forEach { jobs.remove(it.id) }
    }

    override fun close() {
        executor.shutdownNow()
        jobs.clear()
    }
}
