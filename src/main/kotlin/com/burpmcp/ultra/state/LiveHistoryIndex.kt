package com.burpmcp.ultra.state

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Size-bounded index of traffic observed after the extension loads.
 * Searches never touch Burp's project database, so their cost is independent of project size.
 */
class LiveHistoryIndex(
    private val maxEntries: Int = 20_000,
    private val maxBytes: Long = 128L * 1024 * 1024,
    private val maxMessageChars: Int = 32_000
) {
    data class Entry(
        val messageId: Int,
        val method: String,
        val url: String,
        val host: String,
        val port: Int,
        val secure: Boolean,
        val request: String,
        val requestTruncated: Boolean,
        val response: String? = null,
        val responseTruncated: Boolean = false,
        val statusCode: Int? = null,
        val mimeType: String? = null,
        val observedAt: String = Instant.now().toString()
    ) {
        val storedChars: Int get() = request.length + (response?.length ?: 0)
    }

    data class Stats(val entries: Int, val storedBytes: Long, val maxEntries: Int, val maxBytes: Long)

    private val entries = ConcurrentHashMap<Int, Entry>()
    private val order = ConcurrentLinkedDeque<Int>()
    private val lock = Any()
    private var storedBytes = 0L

    fun recordRequest(
        messageId: Int,
        method: String,
        url: String,
        host: String,
        port: Int,
        secure: Boolean,
        rawRequest: String
    ) {
        val (request, truncated) = cap(rawRequest)
        synchronized(lock) {
            val previous = entries[messageId]
            val replacement = Entry(
                messageId = messageId,
                method = method,
                url = url,
                host = host,
                port = port,
                secure = secure,
                request = request,
                requestTruncated = truncated,
                response = previous?.response,
                responseTruncated = previous?.responseTruncated ?: false,
                statusCode = previous?.statusCode,
                mimeType = previous?.mimeType,
                observedAt = previous?.observedAt ?: Instant.now().toString()
            )
            replace(messageId, previous, replacement)
        }
    }

    fun recordResponse(messageId: Int, rawResponse: String, statusCode: Int, mimeType: String?) {
        val (response, truncated) = cap(rawResponse)
        synchronized(lock) {
            val previous = entries[messageId] ?: return
            replace(
                messageId,
                previous,
                previous.copy(
                    response = response,
                    responseTruncated = truncated,
                    statusCode = statusCode,
                    mimeType = mimeType
                )
            )
        }
    }

    fun newest(beforeMessageId: Int? = null, limit: Int = 100): List<Entry> {
        val cappedLimit = limit.coerceIn(1, 10_000)
        val result = ArrayList<Entry>(cappedLimit)
        val iterator = order.descendingIterator()
        while (iterator.hasNext() && result.size < cappedLimit) {
            val id = iterator.next()
            if (beforeMessageId != null && id >= beforeMessageId) continue
            entries[id]?.let(result::add)
        }
        return result
    }

    fun get(messageId: Int): Entry? = entries[messageId]

    fun restore(entry: Entry) {
        synchronized(lock) {
            val previous = entries[entry.messageId]
            replace(entry.messageId, previous, entry)
        }
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(entries.size, storedBytes, maxEntries, maxBytes)
    }

    fun clear(): Int = synchronized(lock) {
        val removed = entries.size
        entries.clear()
        order.clear()
        storedBytes = 0
        removed
    }

    private fun cap(value: String): Pair<String, Boolean> =
        if (value.length <= maxMessageChars) value to false else value.take(maxMessageChars) to true

    private fun replace(messageId: Int, previous: Entry?, replacement: Entry) {
        if (previous == null) order.addLast(messageId)
        entries[messageId] = replacement
        storedBytes += (replacement.storedChars - (previous?.storedChars ?: 0)).toLong() * 2
        trim()
    }

    private fun trim() {
        while (entries.size > maxEntries || storedBytes > maxBytes) {
            val oldestId = order.pollFirst() ?: break
            val removed = entries.remove(oldestId) ?: continue
            storedBytes = (storedBytes - removed.storedChars.toLong() * 2).coerceAtLeast(0)
        }
    }
}
