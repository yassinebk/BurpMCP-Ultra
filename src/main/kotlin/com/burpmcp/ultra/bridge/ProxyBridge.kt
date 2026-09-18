package com.burpmcp.ultra.bridge

import burp.api.montoya.MontoyaApi
import com.burpmcp.ultra.core.BodyText
import com.burpmcp.ultra.core.BoundedHistorySearch
import com.burpmcp.ultra.core.ProxyHistorySearch
import com.burpmcp.ultra.core.StatusCodeRange
import com.burpmcp.ultra.core.HighlightColorName
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.message.MimeType
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.ProxyHistoryFilter
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketHistoryFilter
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.proxy.http.InterceptedRequest
import burp.api.montoya.proxy.http.InterceptedResponse
import burp.api.montoya.proxy.http.ProxyRequestHandler
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction
import burp.api.montoya.proxy.http.ProxyResponseHandler
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction
import burp.api.montoya.websocket.Direction
import com.burpmcp.ultra.events.EventBus
import com.burpmcp.ultra.safety.HeaderSafety
import com.burpmcp.ultra.safety.SafeRegex
import com.burpmcp.ultra.state.ProxyRule
import com.burpmcp.ultra.state.StateManager
import com.burpmcp.ultra.state.LiveHistoryIndex
import com.burpmcp.ultra.state.AsyncSearchJobs
import com.burpmcp.ultra.state.HistoryCapturePolicy
import com.burpmcp.ultra.state.PersistentHistoryStore
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Bridge wrapping the Montoya Proxy API.
 *
 * Provides MCP-friendly methods for reading proxy history, controlling
 * interception, annotating items, and managing proxy rules. Also creates
 * the [ProxyRequestHandler] and [ProxyResponseHandler] registered during
 * extension initialization.
 */
class ProxyBridge(
    private val api: MontoyaApi,
    private val eventBus: EventBus,
    private val stateManager: StateManager
) {
    private val liveHistoryIndex = LiveHistoryIndex()
    private val preferences = api.persistence().preferences()
    private val persistentHistoryStore = PersistentHistoryStore.forProject(
        try { api.project().name() } catch (_: Exception) { "" }
    )
    private val searchJobs = AsyncSearchJobs()
    private val droppedPersistenceWrites = AtomicLong(0)
    private val persistenceExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2_000),
        { runnable -> Thread(runnable, "burpmcp-history-store").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var persistenceEnabled = preferenceBoolean("mcp_persist_proxy_index", false)
    @Volatile private var capturePolicy = loadCapturePolicy()

    init {
        if (persistenceEnabled) {
            try {
                persistentHistoryStore.load(20_000).forEach(liveHistoryIndex::restore)
            } catch (e: Exception) {
                api.logging().logToError("BurpMCP-Ultra: persistent proxy index restore failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------
    // History retrieval
    // ---------------------------------------------------------------

    /**
     * Returns a slice of the HTTP proxy history with optional filtering.
     *
     * @param startIndex Zero-based offset into the filtered history.
     * @param count Maximum number of items to return.
     * @param host Optional hostname filter (case-insensitive substring match).
     * @param method Optional HTTP method filter (exact, case-insensitive).
     * @param statusCode Optional exact status code filter.
     * @param mimeType Optional MIME type filter (case-insensitive name match).
     * @param inScopeOnly When true, only return in-scope items.
     * @param includeRequest Whether to include the full request text.
     * @param includeResponse Whether to include the full response text.
     * @param statusCodeRange Optional "min-max" range string for status codes.
     * @return JSON object with total count, returned count, and items array.
     */
    fun getHistory(
        startIndex: Int,
        count: Int,
        host: String?,
        method: String?,
        statusCode: Int?,
        mimeType: String?,
        inScopeOnly: Boolean,
        includeRequest: Boolean = false,
        includeResponse: Boolean = false,
        statusCodeRange: String? = null,
        maxResponseLength: Int? = null
    ): JsonObject {
        // Resolve the optional MIME type filter up front so an unrecognized value
        // produces a clear error instead of silently dropping the filter (which
        // would return ALL history as if it matched).
        val resolvedMimeType: MimeType? = if (mimeType != null) {
            resolveMimeType(mimeType)
                ?: return buildJsonObject {
                    put(
                        "error",
                        "Unknown mime_type: $mimeType. Valid values: ${mimeTypeValidValues()}"
                    )
                }
        } else {
            null
        }

        // Validate the status-code range ONCE, up front — a malformed range now
        // returns a clear error instead of silently dropping the filter (which used
        // to return items outside the caller's intended range).
        val resolvedRange: StatusCodeRange.Range? = when (val r = StatusCodeRange.parse(statusCodeRange)) {
            is StatusCodeRange.Result.None -> null
            is StatusCodeRange.Result.Ok -> r.range
            is StatusCodeRange.Result.Invalid -> return buildJsonObject { put("error", r.message) }
        }

        val filter = ProxyHistoryFilter { item ->
            if (inScopeOnly && !item.request().isInScope()) return@ProxyHistoryFilter false
            if (host != null && !item.host().contains(host, ignoreCase = true)) return@ProxyHistoryFilter false
            if (method != null && !item.method().equals(method, ignoreCase = true)) return@ProxyHistoryFilter false
            if (statusCode != null && item.hasResponse()) {
                if (item.response().statusCode().toInt() != statusCode) return@ProxyHistoryFilter false
            }
            if (resolvedRange != null && item.hasResponse()) {
                val sc = item.response().statusCode().toInt()
                if (!resolvedRange.contains(sc)) return@ProxyHistoryFilter false
            }
            if (resolvedMimeType != null && item.mimeType() != resolvedMimeType) {
                return@ProxyHistoryFilter false
            }
            true
        }

        val allItems = api.proxy().history(filter)
        val totalFiltered = allItems.size
        val slice = allItems.drop(startIndex).take(count.coerceIn(0, 1000))

        return buildJsonObject {
            put("total_filtered", totalFiltered)
            put("start_index", startIndex)
            put("returned", slice.size)
            put("items", buildJsonArray {
                slice.forEach { item ->
                    add(serializeHistoryItem(item, includeRequest, includeResponse, maxResponseLength ?: 200_000))
                }
            })
        }
    }

    /**
     * Searches proxy history using a regex pattern.
     *
     * @param pattern Regex pattern to match.
     * @param searchIn Where to search: "request", "response", or "both".
     * @param caseSensitive Whether the regex is case-sensitive.
     * @param maxResults Maximum number of matching items to return.
     * @param inScopeOnly When true, only search in-scope items.
     * @param includeRequest Whether to include the full request text.
     * @param includeResponse Whether to include the full response text.
     * @return JSON object with match count and items array.
     */
    fun searchHistory(
        pattern: String,
        searchIn: String,
        caseSensitive: Boolean,
        maxResults: Int,
        inScopeOnly: Boolean,
        includeRequest: Boolean = false,
        includeResponse: Boolean = false,
        maxResponseLength: Int? = null
    ): JsonObject {
        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        val compiledPattern = Pattern.compile(pattern, flags)

        val filter = ProxyHistoryFilter { item ->
            if (inScopeOnly && !item.request().isInScope()) return@ProxyHistoryFilter false

            val t = ProxyHistorySearch.targets(searchIn)

            var found = false
            if (t.url) {
                // Match against the fully-reconstructed URL (scheme+host+path+query),
                // which the request text does not expose as one contiguous string.
                val url = try { item.request().url() } catch (_: Exception) { "" }
                found = compiledPattern.matcher(url).find()
            }
            if (!found && t.request) {
                // Request text only. item.contains(pattern) inherits
                // HttpRequestResponse.contains, which matches BOTH request and
                // response, so a request-only search must inspect the request text.
                found = compiledPattern.matcher(item.request().toString()).find()
            }
            if (!found && t.response && item.hasResponse()) {
                found = compiledPattern.matcher(item.response().toString()).find()
            }
            found
        }

        val matches = api.proxy().history(filter)
        val limited = matches.take(maxResults.coerceIn(0, 1000))

        return buildJsonObject {
            put("total_matches", matches.size)
            put("returned", limited.size)
            put("pattern", pattern)
            put("search_in", searchIn)
            put("items", buildJsonArray {
                limited.forEach { item ->
                    add(serializeHistoryItem(item, includeRequest, includeResponse, maxResponseLength ?: 200_000))
                }
            })
        }
    }

    /**
     * Returns a compact, newest-first history page without serializing message bodies.
     * [beforeId] and [afterId] are exclusive cursors over Burp's stable history ids.
     */
    fun getHistorySummary(
        beforeId: Int?,
        afterId: Int?,
        limit: Int,
        host: String?,
        method: String?,
        statusCode: Int?,
        inScopeOnly: Boolean
    ): JsonObject {
        val effectiveLimit = BoundedHistorySearch.limit(limit)
        val history = api.proxy().history()
        val selected = ArrayList<ProxyHttpRequestResponse>(effectiveLimit + 1)

        for (item in history.asReversed()) {
            if (!BoundedHistorySearch.inWindow(item.id(), beforeId, afterId)) continue
            if (host != null && !item.host().contains(host, ignoreCase = true)) continue
            if (method != null && !item.method().equals(method, ignoreCase = true)) continue
            if (statusCode != null && historyStatus(item) != statusCode) continue
            if (inScopeOnly && !item.request().isInScope()) continue
            selected.add(item)
            if (selected.size > effectiveLimit) break
        }

        val hasMore = selected.size > effectiveLimit
        val page = if (hasMore) selected.subList(0, effectiveLimit) else selected
        return buildJsonObject {
            put("history_size", history.size)
            put("returned", page.size)
            put("has_more", hasMore)
            page.lastOrNull()?.let { put("next_before_id", it.id()) }
            put("items", buildJsonArray {
                page.forEach { add(serializeHistorySummary(it)) }
            })
        }
    }

    /** Retrieves exactly one history item by Burp id. */
    fun getHistoryEntry(id: Int, maxMessageLength: Int?): JsonObject {
        val item = api.proxy().history().firstOrNull { it.id() == id }
            ?: return buildJsonObject {
                put("error", "Proxy history entry $id was not found")
                put("id", id)
            }

        return buildJsonObject {
            put("item", serializeHistoryItem(
                item = item,
                includeRequest = true,
                includeResponse = true,
                maxResponseLength = maxMessageLength ?: 200_000
            ))
        }
    }

    /**
     * Searches a bounded, newest-first history window and returns only metadata plus snippets.
     * Unlike [searchHistory], work stops at explicit scan, match, and wall-clock limits.
     */
    fun searchHistoryBounded(
        pattern: String,
        searchIn: String,
        caseSensitive: Boolean,
        beforeId: Int?,
        afterId: Int?,
        scanLimit: Int,
        maxResults: Int,
        timeBudgetMs: Long,
        inScopeOnly: Boolean
    ): JsonObject {
        if (pattern.length > BoundedHistorySearch.MAX_PATTERN_LENGTH) {
            return buildJsonObject {
                put("error", "Pattern exceeds ${BoundedHistorySearch.MAX_PATTERN_LENGTH} characters")
            }
        }
        try {
            SafeRegex.compile(pattern, ignoreCase = !caseSensitive)
        } catch (e: Exception) {
            return buildJsonObject { put("error", "Invalid regex: ${e.message ?: "syntax error"}") }
        }

        val effectiveScanLimit = BoundedHistorySearch.scanLimit(scanLimit)
        val effectiveMaxResults = maxResults.coerceIn(1, 200)
        val effectiveBudgetMs = BoundedHistorySearch.timeBudgetMs(timeBudgetMs)
        val deadlineNs = System.nanoTime() + effectiveBudgetMs * 1_000_000
        val targets = ProxyHistorySearch.targets(searchIn)
        val iterator = api.proxy().history().asReversed().asSequence()
            .filter { BoundedHistorySearch.inWindow(it.id(), beforeId, afterId) }
            .iterator()

        var scanned = 0
        var matchCount = 0
        var lastScannedId: Int? = null
        var stoppedReason = "end_of_history"
        val matches = buildJsonArray {
            while (iterator.hasNext()) {
                if (scanned >= effectiveScanLimit) {
                    stoppedReason = "scan_limit"
                    break
                }
                if (System.nanoTime() >= deadlineNs) {
                    stoppedReason = "time_budget"
                    break
                }

                val item = iterator.next()
                scanned++
                lastScannedId = item.id()
                if (inScopeOnly && !item.request().isInScope()) continue

                val match = findHistoryMatch(item, pattern, !caseSensitive, targets) ?: continue
                add(buildJsonObject {
                    serializeHistorySummaryInto(this, item)
                    put("match_location", match.location)
                    put("snippet", match.snippet)
                })
                matchCount++
                if (matchCount >= effectiveMaxResults) {
                    stoppedReason = "match_limit"
                    break
                }
            }
        }

        val hasMore = iterator.hasNext()
        return buildJsonObject {
            put("pattern", pattern)
            put("search_in", searchIn)
            put("scanned", scanned)
            put("returned", matches.size)
            put("has_more", hasMore)
            put("stopped_reason", if (hasMore) stoppedReason else "end_of_history")
            if (hasMore && lastScannedId != null) put("next_before_id", lastScannedId)
            put("items", matches)
        }
    }

    fun getLiveIndexSummary(beforeMessageId: Int?, limit: Int): JsonObject {
        val effectiveLimit = BoundedHistorySearch.limit(limit)
        val selected = liveHistoryIndex.newest(beforeMessageId, effectiveLimit + 1)
        val hasMore = selected.size > effectiveLimit
        val page = if (hasMore) selected.subList(0, effectiveLimit) else selected
        val stats = liveHistoryIndex.stats()
        return buildJsonObject {
            put("source", "live_index")
            put("entries", stats.entries)
            put("stored_bytes", stats.storedBytes)
            put("returned", page.size)
            put("has_more", hasMore)
            page.lastOrNull()?.let { put("next_before_message_id", it.messageId) }
            put("items", buildJsonArray { page.forEach { add(serializeLiveEntry(it, includeMessages = false)) } })
        }
    }

    fun getLiveIndexEntry(messageId: Int): JsonObject {
        val entry = liveHistoryIndex.get(messageId)
            ?: return buildJsonObject {
                put("error", "Live index entry $messageId was not found or has been evicted")
                put("message_id", messageId)
            }
        return buildJsonObject { put("item", serializeLiveEntry(entry, includeMessages = true)) }
    }

    fun searchLiveIndex(
        pattern: String,
        searchIn: String,
        caseSensitive: Boolean,
        beforeMessageId: Int?,
        scanLimit: Int,
        maxResults: Int,
        timeBudgetMs: Long
    ): JsonObject {
        if (pattern.length > BoundedHistorySearch.MAX_PATTERN_LENGTH) {
            return buildJsonObject { put("error", "Pattern exceeds ${BoundedHistorySearch.MAX_PATTERN_LENGTH} characters") }
        }
        try {
            SafeRegex.compile(pattern, ignoreCase = !caseSensitive)
        } catch (e: Exception) {
            return buildJsonObject { put("error", "Invalid regex: ${e.message ?: "syntax error"}") }
        }

        val effectiveScanLimit = BoundedHistorySearch.scanLimit(scanLimit)
        val effectiveMaxResults = maxResults.coerceIn(1, 200)
        val deadlineNs = System.nanoTime() + BoundedHistorySearch.timeBudgetMs(timeBudgetMs) * 1_000_000
        val targets = ProxyHistorySearch.targets(searchIn)
        val candidates = liveHistoryIndex.newest(beforeMessageId, effectiveScanLimit)
        var scanned = 0
        var matchCount = 0
        var lastScannedId: Int? = null
        val matches = buildJsonArray {
            for (entry in candidates) {
                if (Thread.currentThread().isInterrupted) break
                if (System.nanoTime() >= deadlineNs) break
                scanned++
                lastScannedId = entry.messageId
                val match = findLiveMatch(entry, pattern, !caseSensitive, targets) ?: continue
                add(buildJsonObject {
                    serializeLiveEntryInto(this, entry, includeMessages = false)
                    put("match_location", match.location)
                    put("snippet", match.snippet)
                })
                matchCount++
                if (matchCount >= effectiveMaxResults) break
            }
        }
        val hasMore = scanned < candidates.size || candidates.size >= effectiveScanLimit || matches.size >= effectiveMaxResults
        return buildJsonObject {
            put("source", "live_index")
            put("scanned", scanned)
            put("returned", matches.size)
            put("has_more", hasMore)
            if (hasMore && lastScannedId != null) put("next_before_message_id", lastScannedId)
            put("items", matches)
        }
    }

    fun getLiveIndexStats(): JsonObject {
        val stats = liveHistoryIndex.stats()
        val disk = persistentHistoryStore.stats()
        return buildJsonObject {
            put("entries", stats.entries)
            put("stored_bytes", stats.storedBytes)
            put("max_entries", stats.maxEntries)
            put("max_bytes", stats.maxBytes)
            put("scope", if (persistenceEnabled) "Current project sidecar plus newly observed traffic" else "Traffic observed after BurpMCP-Ultra loaded")
            put("persistence_enabled", persistenceEnabled)
            put("persistent_path", disk.path)
            put("persistent_bytes", disk.bytes)
            put("persistent_max_bytes", disk.maxBytes)
            put("pending_persistence_writes", persistenceExecutor.queue.size)
            put("dropped_persistence_writes", droppedPersistenceWrites.get())
        }
    }

    fun clearLiveIndex(): JsonObject = buildJsonObject {
        put("cleared", liveHistoryIndex.clear())
        put("scope", "live_index_only")
        put("burp_proxy_history_modified", false)
    }

    fun getIndexPolicy(): JsonObject = buildJsonObject {
        val policy = capturePolicy
        put("capture_enabled", policy.enabled)
        put("in_scope_only", policy.inScopeOnly)
        put("include_hosts", buildJsonArray { policy.includeHosts.forEach(::add) })
        put("exclude_hosts", buildJsonArray { policy.excludeHosts.forEach(::add) })
        put("exclude_extensions", buildJsonArray { policy.excludeExtensions.sorted().forEach(::add) })
        put("persistence_enabled", persistenceEnabled)
        put("persistent_redaction", "authorization, proxy-authorization, cookie, set-cookie, x-api-key, password and token values")
    }

    fun configureIndexPolicy(
        captureEnabled: Boolean?,
        inScopeOnly: Boolean?,
        includeHosts: List<String>?,
        excludeHosts: List<String>?,
        excludeExtensions: List<String>?,
        persist: Boolean?
    ): JsonObject {
        val old = capturePolicy
        val updated = HistoryCapturePolicy.normalized(
            enabled = captureEnabled ?: old.enabled,
            inScopeOnly = inScopeOnly ?: old.inScopeOnly,
            includeHosts = includeHosts ?: old.includeHosts,
            excludeHosts = excludeHosts ?: old.excludeHosts,
            excludeExtensions = excludeExtensions ?: old.excludeExtensions
        )
        capturePolicy = updated
        preferences.setBoolean("mcp_proxy_index_capture_enabled", updated.enabled)
        preferences.setBoolean("mcp_proxy_index_scope_only", updated.inScopeOnly)
        preferences.setString("mcp_proxy_index_include_hosts", updated.includeHosts.joinToString(","))
        preferences.setString("mcp_proxy_index_exclude_hosts", updated.excludeHosts.joinToString(","))
        preferences.setString("mcp_proxy_index_exclude_extensions", updated.excludeExtensions.joinToString(","))

        if (persist != null && persist != persistenceEnabled) {
            persistenceEnabled = persist
            preferences.setBoolean("mcp_persist_proxy_index", persist)
            if (persist) {
                submitPersistence {
                    persistentHistoryStore.compact(liveHistoryIndex.newest(limit = 20_000).asReversed(), force = true)
                }
            }
        }
        return getIndexPolicy()
    }

    fun clearPersistentIndex(): JsonObject {
        val deleted = try { persistentHistoryStore.clear() } catch (_: Exception) { false }
        return buildJsonObject {
            put("deleted", deleted)
            put("scope", "extension_sidecar_only")
            put("burp_proxy_history_modified", false)
        }
    }

    fun startLiveIndexSearchJob(
        pattern: String,
        searchIn: String,
        caseSensitive: Boolean,
        beforeMessageId: Int?,
        scanLimit: Int,
        maxResults: Int,
        timeBudgetMs: Long
    ): JsonObject {
        val id = searchJobs.submit {
            searchLiveIndex(pattern, searchIn, caseSensitive, beforeMessageId, scanLimit, maxResults, timeBudgetMs)
        }
        return buildJsonObject { put("job_id", id); put("status", "queued") }
    }

    fun getSearchJob(id: String): JsonObject {
        val job = searchJobs.get(id) ?: return buildJsonObject { put("error", "Search job $id was not found") }
        return buildJsonObject {
            put("job_id", job.id)
            put("status", job.status)
            put("created_at", job.createdAt)
            job.result?.let { put("result", it) }
            job.error?.let { put("error", it) }
        }
    }

    fun cancelSearchJob(id: String): JsonObject = buildJsonObject {
        put("job_id", id)
        put("cancelled", searchJobs.cancel(id))
    }

    fun close() {
        searchJobs.close()
        persistenceExecutor.shutdown()
        try { persistenceExecutor.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (persistenceEnabled) {
            try { persistentHistoryStore.compact(liveHistoryIndex.newest(limit = 20_000).asReversed(), force = true) } catch (_: Exception) { }
        }
    }

    /**
     * Returns WebSocket proxy history with optional filtering.
     *
     * @param startIndex Zero-based offset.
     * @param count Maximum number of items.
     * @param host Optional hostname filter.
     * @param direction Optional direction filter: "CLIENT_TO_SERVER" or "SERVER_TO_CLIENT".
     * @return JSON object with items array.
     */
    fun getWebSocketHistory(
        startIndex: Int,
        count: Int,
        host: String?,
        direction: String?
    ): JsonObject {
        val dirFilter = direction?.let {
            try { Direction.valueOf(it.uppercase()) } catch (_: Exception) { null }
        }

        val filter = ProxyWebSocketHistoryFilter { item ->
            if (host != null) {
                val upgradeHost = try { item.upgradeRequest()?.httpService()?.host() } catch (_: Exception) { null }
                if (upgradeHost != null && !upgradeHost.contains(host, ignoreCase = true)) {
                    return@ProxyWebSocketHistoryFilter false
                }
            }
            if (dirFilter != null && item.direction() != dirFilter) {
                return@ProxyWebSocketHistoryFilter false
            }
            true
        }

        val allItems = api.proxy().webSocketHistory(filter)
        val totalFiltered = allItems.size
        val slice = allItems.drop(startIndex).take(count.coerceIn(0, 1000))

        return buildJsonObject {
            put("total_filtered", totalFiltered)
            put("start_index", startIndex)
            put("returned", slice.size)
            put("items", buildJsonArray {
                slice.forEach { item -> add(serializeWebSocketItem(item)) }
            })
        }
    }

    /**
     * Searches WebSocket proxy history using a regex pattern on message payloads.
     *
     * @param pattern Regex pattern.
     * @param direction Optional direction filter.
     * @param maxResults Maximum results.
     * @return JSON object with match results.
     */
    fun searchWebSocketHistory(
        pattern: String,
        direction: String?,
        maxResults: Int
    ): JsonObject {
        val compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        val dirFilter = direction?.let {
            try { Direction.valueOf(it.uppercase()) } catch (_: Exception) { null }
        }

        val filter = ProxyWebSocketHistoryFilter { item ->
            if (dirFilter != null && item.direction() != dirFilter) return@ProxyWebSocketHistoryFilter false
            item.contains(compiledPattern)
        }

        val matches = api.proxy().webSocketHistory(filter)
        val limited = matches.take(maxResults.coerceIn(0, 1000))

        return buildJsonObject {
            put("total_matches", matches.size)
            put("returned", limited.size)
            put("pattern", pattern)
            put("items", buildJsonArray {
                limited.forEach { item -> add(serializeWebSocketItem(item)) }
            })
        }
    }

    // ---------------------------------------------------------------
    // Intercept control
    // ---------------------------------------------------------------

    /** Enables proxy interception. */
    fun enableIntercept(): JsonObject {
        api.proxy().enableIntercept()
        eventBus.emit("proxy.intercept", buildJsonObject {
            put("enabled", true)
            put("timestamp", Instant.now().toString())
        })
        return buildJsonObject { put("intercept_enabled", true) }
    }

    /** Disables proxy interception. */
    fun disableIntercept(): JsonObject {
        api.proxy().disableIntercept()
        eventBus.emit("proxy.intercept", buildJsonObject {
            put("enabled", false)
            put("timestamp", Instant.now().toString())
        })
        return buildJsonObject { put("intercept_enabled", false) }
    }

    /** Returns the current intercept state. */
    fun isInterceptEnabled(): JsonObject {
        return buildJsonObject {
            put("intercept_enabled", api.proxy().isInterceptEnabled)
        }
    }

    // ---------------------------------------------------------------
    // Annotation
    // ---------------------------------------------------------------

    /**
     * Annotates a proxy history item by its index (message ID).
     *
     * @param index The proxy history item message ID.
     * @param comment Optional comment to set.
     * @param highlight Optional highlight color name (e.g., "RED", "BLUE").
     * @return JSON object confirming the annotation.
     */
    fun annotateHistoryItem(index: Int, comment: String?, highlight: String?): JsonObject {
        val history = api.proxy().history()
        val item = history.find { it.id() == index }
            ?: return buildJsonObject {
                put("error", "History item with id $index not found")
            }

        // Validate the highlight colour BEFORE mutating anything. Montoya's
        // HighlightColor.highlightColor(name) returns NONE (not an exception) for an
        // unknown colour, so trusting it used to CLEAR any existing highlight while
        // still reporting annotated:true — a silent clear masquerading as success.
        val canonicalHighlight: String? = if (highlight != null) {
            HighlightColorName.canonical(highlight)
                ?: return buildJsonObject {
                    put(
                        "error",
                        "Invalid highlight color: '$highlight'. Valid values: ${HighlightColorName.validValues()}"
                    )
                }
        } else {
            null
        }

        val annotations = item.annotations()
        if (comment != null) {
            annotations.setNotes(comment)
        }
        if (canonicalHighlight != null) {
            // canonicalHighlight is a validated enum constant name, so valueOf is safe.
            annotations.setHighlightColor(HighlightColor.valueOf(canonicalHighlight))
        }

        return buildJsonObject {
            put("index", index)
            put("annotated", true)
            if (comment != null) put("comment", comment)
            if (canonicalHighlight != null) put("highlight", canonicalHighlight)
        }
    }

    // ---------------------------------------------------------------
    // Proxy handlers (registered during extension init)
    // ---------------------------------------------------------------

    /**
     * Creates a [ProxyRequestHandler] that:
     * 1. Evaluates proxy rules from [StateManager.proxyRules] against each request.
     * 2. Applies matching rule actions (modify, drop, tag).
     * 3. Emits "proxy.request" events to the [EventBus].
     */
    fun createRequestHandler(): ProxyRequestHandler {
        return object : ProxyRequestHandler {
            override fun handleRequestReceived(interceptedRequest: InterceptedRequest): ProxyRequestReceivedAction {
                val policy = capturePolicy
                if (policy.accepts(interceptedRequest.httpService().host(), interceptedRequest.url(), interceptedRequest.isInScope())) {
                    liveHistoryIndex.recordRequest(
                        messageId = interceptedRequest.messageId(),
                        method = interceptedRequest.method(),
                        url = interceptedRequest.url(),
                        host = interceptedRequest.httpService().host(),
                        port = interceptedRequest.httpService().port(),
                        secure = interceptedRequest.httpService().secure(),
                        rawRequest = cappedMessageText(interceptedRequest.toByteArray())
                    )
                    persistSnapshot(interceptedRequest.messageId())
                }
                // Emit event for every request passing through the proxy
                emitRequestEvent(interceptedRequest)

                // Find matching request rules
                val matchingRules = stateManager.proxyRules.filter { rule ->
                    rule.enabled && rule.type.equals("request", ignoreCase = true) &&
                        matchesRequestRule(rule, interceptedRequest)
                }

                if (matchingRules.isEmpty()) {
                    return ProxyRequestReceivedAction.continueWith(interceptedRequest)
                }

                var currentRequest: HttpRequest = interceptedRequest
                var currentAnnotations = interceptedRequest.annotations()
                var shouldDrop = false

                for (rule in matchingRules) {
                    when (rule.action.lowercase()) {
                        "drop" -> {
                            shouldDrop = true
                            eventBus.emit("proxy.rule.applied", buildJsonObject {
                                put("rule_id", rule.ruleId)
                                put("action", "drop")
                                put("url", interceptedRequest.url())
                                put("timestamp", Instant.now().toString())
                            })
                            break
                        }
                        "modify" -> {
                            currentRequest = applyRequestModifications(rule, currentRequest)
                            eventBus.emit("proxy.rule.applied", buildJsonObject {
                                put("rule_id", rule.ruleId)
                                put("action", "modify")
                                put("url", interceptedRequest.url())
                                put("timestamp", Instant.now().toString())
                            })
                        }
                        "tag" -> {
                            if (rule.tagComment != null) {
                                currentAnnotations = currentAnnotations.withNotes(rule.tagComment)
                            }
                            if (rule.tagHighlight != null) {
                                val color = resolveHighlightColor(rule.tagHighlight)
                                if (color != null) {
                                    currentAnnotations = currentAnnotations.withHighlightColor(color)
                                }
                            }
                            eventBus.emit("proxy.rule.applied", buildJsonObject {
                                put("rule_id", rule.ruleId)
                                put("action", "tag")
                                put("url", interceptedRequest.url())
                                put("timestamp", Instant.now().toString())
                            })
                        }
                    }
                }

                return if (shouldDrop) {
                    ProxyRequestReceivedAction.drop()
                } else {
                    ProxyRequestReceivedAction.continueWith(currentRequest, currentAnnotations)
                }
            }

            override fun handleRequestToBeSent(interceptedRequest: InterceptedRequest): ProxyRequestToBeSentAction {
                return ProxyRequestToBeSentAction.continueWith(interceptedRequest)
            }
        }
    }

    /**
     * Creates a [ProxyResponseHandler] that:
     * 1. Evaluates proxy rules from [StateManager.proxyRules] against each response.
     * 2. Applies matching rule actions (modify, drop, tag).
     * 3. Emits "proxy.response" events to the [EventBus].
     */
    fun createResponseHandler(): ProxyResponseHandler {
        return object : ProxyResponseHandler {
            override fun handleResponseReceived(interceptedResponse: InterceptedResponse): ProxyResponseReceivedAction {
                liveHistoryIndex.recordResponse(
                    messageId = interceptedResponse.messageId(),
                    rawResponse = cappedMessageText(interceptedResponse.toByteArray()),
                    statusCode = interceptedResponse.statusCode().toInt(),
                    mimeType = try { interceptedResponse.mimeType().name } catch (_: Exception) { null }
                )
                persistSnapshot(interceptedResponse.messageId())
                // Emit event for every response passing through the proxy
                emitResponseEvent(interceptedResponse)

                // Find matching response rules
                val matchingRules = stateManager.proxyRules.filter { rule ->
                    rule.enabled && rule.type.equals("response", ignoreCase = true) &&
                        matchesResponseRule(rule, interceptedResponse)
                }

                if (matchingRules.isEmpty()) {
                    return ProxyResponseReceivedAction.continueWith(interceptedResponse)
                }

                var currentResponse: HttpResponse = interceptedResponse
                var currentAnnotations = interceptedResponse.annotations()
                var shouldDrop = false

                for (rule in matchingRules) {
                    when (rule.action.lowercase()) {
                        "drop" -> {
                            shouldDrop = true
                            eventBus.emit("proxy.rule.applied", buildJsonObject {
                                put("rule_id", rule.ruleId)
                                put("action", "drop")
                                put("url", interceptedResponse.request()?.url() ?: "")
                                put("timestamp", Instant.now().toString())
                            })
                            break
                        }
                        "modify" -> {
                            currentResponse = applyResponseModifications(rule, currentResponse)
                            eventBus.emit("proxy.rule.applied", buildJsonObject {
                                put("rule_id", rule.ruleId)
                                put("action", "modify")
                                put("url", interceptedResponse.request()?.url() ?: "")
                                put("timestamp", Instant.now().toString())
                            })
                        }
                        "tag" -> {
                            if (rule.tagComment != null) {
                                currentAnnotations = currentAnnotations.withNotes(rule.tagComment)
                            }
                            if (rule.tagHighlight != null) {
                                val color = resolveHighlightColor(rule.tagHighlight)
                                if (color != null) {
                                    currentAnnotations = currentAnnotations.withHighlightColor(color)
                                }
                            }
                            eventBus.emit("proxy.rule.applied", buildJsonObject {
                                put("rule_id", rule.ruleId)
                                put("action", "tag")
                                put("url", interceptedResponse.request()?.url() ?: "")
                                put("timestamp", Instant.now().toString())
                            })
                        }
                    }
                }

                return if (shouldDrop) {
                    ProxyResponseReceivedAction.drop()
                } else {
                    ProxyResponseReceivedAction.continueWith(currentResponse, currentAnnotations)
                }
            }

            override fun handleResponseToBeSent(interceptedResponse: InterceptedResponse): ProxyResponseToBeSentAction {
                return ProxyResponseToBeSentAction.continueWith(interceptedResponse)
            }
        }
    }

    // ---------------------------------------------------------------
    // Rule matching helpers
    // ---------------------------------------------------------------

    /**
     * Tests whether a request matches a proxy rule's conditions.
     * All non-null conditions must match (logical AND).
     */
    private fun matchesRequestRule(rule: ProxyRule, request: InterceptedRequest): Boolean {
        if (rule.matchHost != null) {
            val hostPattern = rule.matchHost.replace("*", ".*")
            if (!SafeRegex.matches(hostPattern, request.httpService().host(), ignoreCase = true)) {
                return false
            }
        }
        if (rule.matchUrl != null) {
            if (!SafeRegex.containsMatchIn(rule.matchUrl, request.url(), ignoreCase = true)) {
                return false
            }
        }
        if (rule.matchMethod != null) {
            if (!request.method().equals(rule.matchMethod, ignoreCase = true)) {
                return false
            }
        }
        if (rule.matchHeader != null) {
            val headerStr = request.headers().joinToString("\r\n") { "${it.name()}: ${it.value()}" }
            if (!SafeRegex.containsMatchIn(rule.matchHeader, headerStr, ignoreCase = true)) {
                return false
            }
        }
        if (rule.matchBody != null) {
            val body = request.bodyToString()
            if (!SafeRegex.containsMatchIn(rule.matchBody, body, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    /**
     * Tests whether a response matches a proxy rule's conditions.
     * All non-null conditions must match (logical AND).
     */
    private fun matchesResponseRule(rule: ProxyRule, response: InterceptedResponse): Boolean {
        if (rule.matchHost != null) {
            val host = try { response.request()?.httpService()?.host() } catch (_: Exception) { null }
            if (host != null) {
                val hostPattern = rule.matchHost.replace("*", ".*")
                if (!SafeRegex.matches(hostPattern, host, ignoreCase = true)) {
                    return false
                }
            }
        }
        if (rule.matchUrl != null) {
            val url = try { response.request()?.url() } catch (_: Exception) { null }
            if (url != null && !SafeRegex.containsMatchIn(rule.matchUrl, url, ignoreCase = true)) {
                return false
            }
        }
        if (rule.matchStatus != null) {
            if (response.statusCode().toInt() != rule.matchStatus) {
                return false
            }
        }
        if (rule.matchHeader != null) {
            val headerStr = response.headers().joinToString("\r\n") { "${it.name()}: ${it.value()}" }
            if (!SafeRegex.containsMatchIn(rule.matchHeader, headerStr, ignoreCase = true)) {
                return false
            }
        }
        if (rule.matchBody != null) {
            val body = response.bodyToString()
            if (!SafeRegex.containsMatchIn(rule.matchBody, body, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------
    // Modification helpers
    // ---------------------------------------------------------------

    /**
     * Applies a rule's modification directives to an HTTP request.
     * Returns a new (possibly modified) request; the original is not mutated.
     */
    private fun applyRequestModifications(rule: ProxyRule, request: HttpRequest): HttpRequest {
        var modified = request

        // Add header
        if (rule.modifyAddHeader != null) {
            HeaderSafety.parseHeaderLine(rule.modifyAddHeader)?.let { (name, value) ->
                modified = modified.withAddedHeader(name, value)
            }
        }

        // Remove header
        if (rule.modifyRemoveHeader != null) {
            modified = modified.withRemovedHeader(rule.modifyRemoveHeader)
        }

        // Replace headers
        rule.modifyReplaceHeader?.forEach { (headerName, headerValue) ->
            modified = modified.withUpdatedHeader(headerName, headerValue)
        }

        // Body regex replacement
        if (rule.modifyBodyRegex != null && rule.modifyBodyReplacement != null) {
            val body = modified.bodyToString()
            val newBody = SafeRegex.replace(rule.modifyBodyRegex, body, rule.modifyBodyReplacement, ignoreCase = true)
            modified = modified.withBody(newBody)
        }

        return modified
    }

    /**
     * Applies a rule's modification directives to an HTTP response.
     * Returns a new (possibly modified) response; the original is not mutated.
     */
    private fun applyResponseModifications(rule: ProxyRule, response: HttpResponse): HttpResponse {
        var modified = response

        // Add header
        if (rule.modifyAddHeader != null) {
            HeaderSafety.parseHeaderLine(rule.modifyAddHeader)?.let { (name, value) ->
                modified = modified.withAddedHeader(name, value)
            }
        }

        // Remove header
        if (rule.modifyRemoveHeader != null) {
            modified = modified.withRemovedHeader(rule.modifyRemoveHeader)
        }

        // Replace headers
        rule.modifyReplaceHeader?.forEach { (headerName, headerValue) ->
            modified = modified.withUpdatedHeader(headerName, headerValue)
        }

        // Body regex replacement
        if (rule.modifyBodyRegex != null && rule.modifyBodyReplacement != null) {
            val body = modified.bodyToString()
            val newBody = SafeRegex.replace(rule.modifyBodyRegex, body, rule.modifyBodyReplacement, ignoreCase = true)
            modified = modified.withBody(newBody)
        }

        return modified
    }

    // ---------------------------------------------------------------
    // Event emission helpers
    // ---------------------------------------------------------------

    private fun emitRequestEvent(request: InterceptedRequest) {
        eventBus.emit("proxy.request", buildJsonObject {
            put("message_id", request.messageId())
            put("method", request.method())
            put("url", request.url())
            put("host", request.httpService().host())
            put("port", request.httpService().port())
            put("secure", request.httpService().secure())
            put("in_scope", request.isInScope())
            put("timestamp", Instant.now().toString())
        })
    }

    private fun emitResponseEvent(response: InterceptedResponse) {
        eventBus.emit("proxy.response", buildJsonObject {
            put("message_id", response.messageId())
            put("status_code", response.statusCode().toInt())
            put("mime_type", response.mimeType().name)
            try {
                val req = response.request()
                if (req != null) {
                    put("url", req.url())
                    put("host", req.httpService().host())
                }
            } catch (_: Exception) {
                // request may not be available
            }
            put("timestamp", Instant.now().toString())
        })
    }

    // ---------------------------------------------------------------
    // Serialization helpers
    // ---------------------------------------------------------------

    /**
     * Serializes a [ProxyHttpRequestResponse] to a [JsonObject].
     */
    private fun serializeHistoryItem(
        item: ProxyHttpRequestResponse,
        includeRequest: Boolean,
        includeResponse: Boolean,
        maxResponseLength: Int? = null
    ): JsonObject {
        return buildJsonObject {
            put("index", item.id())
            put("host", item.host())
            put("port", item.port())
            put("secure", item.secure())
            put("method", item.method())
            put("url", item.url())
            put("path", item.path())
            put("edited", item.edited())
            put("mime_type", item.mimeType().name)
            put("has_response", item.hasResponse())

            // Timing
            try {
                val time = item.time()
                if (time != null) put("time", time.toString())
            } catch (_: Exception) { }

            // Timing data
            try {
                val td = item.timingData()
                if (td != null) {
                    put("timing", buildJsonObject {
                        put("time_to_first_byte_ms", td.timeBetweenRequestSentAndStartOfResponse().toMillis())
                        put("time_to_complete_ms", td.timeBetweenRequestSentAndEndOfResponse().toMillis())
                    })
                }
            } catch (_: Exception) { }

            // Response metadata
            if (item.hasResponse()) {
                try {
                    val resp = item.response()
                    put("status_code", resp.statusCode().toInt())
                    put("response_length", resp.body().length())
                    put("response_mime_type", resp.mimeType().name)
                } catch (_: Exception) { }
            }

            // Annotations
            try {
                val ann = item.annotations()
                if (ann.hasNotes()) put("comment", ann.notes())
                if (ann.hasHighlightColor()) put("highlight", ann.highlightColor().name)
            } catch (_: Exception) { }

            // Request headers
            try {
                put("request_headers", buildJsonArray {
                    item.request().headers().forEach { h ->
                        add(buildJsonObject {
                            put("name", h.name())
                            put("value", h.value())
                        })
                    }
                })
            } catch (_: Exception) { }

            // Full request text. Routed through BodyText so a binary body (a file upload) can
            // never put an unencodable lone surrogate into the JSON — see issue #12.
            if (includeRequest) {
                try {
                    val r = BodyText.render(item.request().toString(), maxResponseLength, label = "request body")
                    put("request", r.text)
                    if (r.binary) put("request_binary", true)
                    if (r.truncated) put("request_truncated", true)
                } catch (_: Exception) {
                    put("request", "")
                }
            }

            // Full response text. A binary body (font/image/archive) is replaced by a placeholder
            // rather than string-converted: doing the latter corrupted the WHOLE batch's JSON for
            // the client, not just this item (issue #12).
            if (includeResponse && item.hasResponse()) {
                try {
                    val mime = try { item.response().mimeType().name } catch (_: Exception) { null }
                    val r = BodyText.render(item.response().toString(), maxResponseLength, mime, "response body")
                    put("response", r.text)
                    if (r.binary) put("response_binary", true)
                    if (r.truncated) put("response_truncated", true)
                } catch (_: Exception) {
                    put("response", "")
                }
            }
        }
    }

    private fun serializeHistorySummary(item: ProxyHttpRequestResponse): JsonObject =
        buildJsonObject { serializeHistorySummaryInto(this, item) }

    private fun serializeHistorySummaryInto(builder: JsonObjectBuilder, item: ProxyHttpRequestResponse) {
        builder.put("id", item.id())
        builder.put("method", item.method())
        builder.put("host", item.host())
        builder.put("port", item.port())
        builder.put("secure", item.secure())
        builder.put("path", item.path())
        builder.put("url", item.url())
        builder.put("has_response", item.hasResponse())
        historyStatus(item)?.let { builder.put("status_code", it) }
        if (item.hasResponse()) {
            try {
                builder.put("response_length", item.response().body().length())
                builder.put("mime_type", item.response().mimeType().name)
            } catch (_: Exception) { }
        }
        try { item.time()?.let { builder.put("time", it.toString()) } } catch (_: Exception) { }
    }

    private data class BoundedMatch(val location: String, val snippet: String)

    private fun serializeLiveEntry(entry: LiveHistoryIndex.Entry, includeMessages: Boolean): JsonObject =
        buildJsonObject { serializeLiveEntryInto(this, entry, includeMessages) }

    private fun serializeLiveEntryInto(
        builder: JsonObjectBuilder,
        entry: LiveHistoryIndex.Entry,
        includeMessages: Boolean
    ) {
        builder.put("message_id", entry.messageId)
        builder.put("method", entry.method)
        builder.put("url", entry.url)
        builder.put("host", entry.host)
        builder.put("port", entry.port)
        builder.put("secure", entry.secure)
        builder.put("observed_at", entry.observedAt)
        entry.statusCode?.let { builder.put("status_code", it) }
        entry.mimeType?.let { builder.put("mime_type", it) }
        builder.put("request_truncated", entry.requestTruncated)
        builder.put("response_truncated", entry.responseTruncated)
        if (includeMessages) {
            builder.put("request", BodyText.stripLoneSurrogates(entry.request))
            entry.response?.let { builder.put("response", BodyText.stripLoneSurrogates(it)) }
        }
    }

    private fun findLiveMatch(
        entry: LiveHistoryIndex.Entry,
        pattern: String,
        ignoreCase: Boolean,
        targets: ProxyHistorySearch.Targets
    ): BoundedMatch? {
        fun find(location: String, text: String): BoundedMatch? {
            val result = SafeRegex.find(pattern, text, ignoreCase, maxInputLen = 64_000, timeoutMs = 50)
                ?: return null
            return BoundedMatch(location, BoundedHistorySearch.snippet(text, result.range.first, result.range.last + 1))
        }
        if (targets.url) find("url", entry.url)?.let { return it }
        if (targets.request) find("request", entry.request)?.let { return it }
        if (targets.response) entry.response?.let { find("response", it) }?.let { return it }
        return null
    }

    private fun findHistoryMatch(
        item: ProxyHttpRequestResponse,
        pattern: String,
        ignoreCase: Boolean,
        targets: ProxyHistorySearch.Targets
    ): BoundedMatch? {
        fun find(location: String, text: String): BoundedMatch? {
            val result = SafeRegex.find(
                pattern = pattern,
                input = text,
                ignoreCase = ignoreCase,
                maxInputLen = 500_000,
                timeoutMs = 50
            ) ?: return null
            return BoundedMatch(
                location,
                BoundedHistorySearch.snippet(text, result.range.first, result.range.last + 1)
            )
        }

        if (targets.url) find("url", item.url())?.let { return it }
        if (targets.request) find("request", cappedMessageText(item.request().toByteArray(), 500_000))?.let { return it }
        if (targets.response && item.hasResponse()) {
            find("response", cappedMessageText(item.response().toByteArray(), 500_000))?.let { return it }
        }
        return null
    }

    private fun persistSnapshot(messageId: Int) {
        if (!persistenceEnabled) return
        val snapshot = liveHistoryIndex.get(messageId) ?: return
        submitPersistence {
            if (!persistenceEnabled) return@submitPersistence
            try {
                persistentHistoryStore.append(snapshot)
                persistentHistoryStore.compact(liveHistoryIndex.newest(limit = 20_000).asReversed())
            } catch (e: Exception) {
                api.logging().logToError("BurpMCP-Ultra: proxy index persistence failed: ${e.message}")
            }
        }
    }

    private fun submitPersistence(operation: () -> Unit) {
        try {
            persistenceExecutor.execute(operation)
        } catch (_: RejectedExecutionException) {
            droppedPersistenceWrites.incrementAndGet()
        }
    }

    private fun loadCapturePolicy(): HistoryCapturePolicy = HistoryCapturePolicy.normalized(
        enabled = preferenceBoolean("mcp_proxy_index_capture_enabled", true),
        inScopeOnly = preferenceBoolean("mcp_proxy_index_scope_only", false),
        includeHosts = preferenceList("mcp_proxy_index_include_hosts"),
        excludeHosts = preferenceList("mcp_proxy_index_exclude_hosts"),
        excludeExtensions = preferenceList("mcp_proxy_index_exclude_extensions")
    )

    private fun preferenceBoolean(key: String, default: Boolean): Boolean =
        try { preferences.getBoolean(key) ?: default } catch (_: Exception) { default }

    private fun preferenceList(key: String): List<String> =
        try { preferences.getString(key)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList() }
        catch (_: Exception) { emptyList() }

    private fun historyStatus(item: ProxyHttpRequestResponse): Int? =
        if (!item.hasResponse()) null else try { item.response().statusCode().toInt() } catch (_: Exception) { null }

    private fun cappedMessageText(bytes: burp.api.montoya.core.ByteArray, maxBytes: Int = 32_000): String {
        val end = bytes.length().coerceAtMost(maxBytes)
        return BodyText.stripLoneSurrogates(bytes.subArray(0, end).toString())
    }

    /**
     * Serializes a [ProxyWebSocketMessage] to a [JsonObject].
     */
    private fun serializeWebSocketItem(item: ProxyWebSocketMessage): JsonObject {
        return buildJsonObject {
            put("id", item.id())
            put("websocket_id", item.webSocketId())
            put("direction", item.direction().name)
            put("listener_port", item.listenerPort())

            try {
                val time = item.time()
                if (time != null) put("time", time.toString())
            } catch (_: Exception) { }

            try {
                val payload = item.payload()
                // Binary WebSocket frames are the same JSON hazard as binary HTTP bodies (issue #12).
                val r = BodyText.render(payload.toString(), label = "payload")
                put("payload", r.text)
                if (r.binary) put("payload_binary", true)
                if (r.truncated) put("payload_truncated", true)
                put("payload_length", payload.length())
            } catch (_: Exception) {
                put("payload", "")
                put("payload_length", 0)
            }

            try {
                val editedPayload = item.editedPayload()
                if (editedPayload != null) {
                    put("edited_payload", BodyText.render(editedPayload.toString(), label = "payload").text)
                }
            } catch (_: Exception) { }

            try {
                val ann = item.annotations()
                if (ann.hasNotes()) put("comment", ann.notes())
                if (ann.hasHighlightColor()) put("highlight", ann.highlightColor().name)
            } catch (_: Exception) { }

            try {
                val upgradeReq = item.upgradeRequest()
                if (upgradeReq != null) {
                    put("upgrade_url", upgradeReq.url())
                    put("upgrade_host", upgradeReq.httpService().host())
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Resolves a caller-supplied MIME type string to a [MimeType] enum constant.
     *
     * Accepts both the Montoya enum constant names (e.g. "JSON", "HTML") and a
     * set of common content-type / alias spellings (e.g. "application/json",
     * "text/html", "javascript"). Returns null if it cannot be resolved.
     */
    private fun resolveMimeType(input: String): MimeType? {
        val normalized = input.trim()

        // Direct enum constant match (case-insensitive), e.g. "JSON", "PLAIN_TEXT".
        try {
            return MimeType.valueOf(normalized.uppercase())
        } catch (_: Exception) {
            // fall through to alias resolution
        }

        // Common content-type strings and short aliases mapped to enum constants.
        return when (normalized.lowercase()) {
            "application/json", "json", "text/json" -> MimeType.JSON
            "text/html", "html", "application/xhtml+xml", "xhtml" -> MimeType.HTML
            "text/plain", "plain", "text" -> MimeType.PLAIN_TEXT
            "text/css", "css" -> MimeType.CSS
            "application/javascript", "text/javascript", "javascript", "js", "script" -> MimeType.SCRIPT
            "application/xml", "text/xml", "xml" -> MimeType.XML
            "application/yaml", "text/yaml", "yaml", "yml" -> MimeType.YAML
            "text/event-stream", "sse" -> MimeType.SSE
            "application/rtf", "text/rtf", "rtf" -> MimeType.RTF
            "image/svg+xml", "svg" -> MimeType.IMAGE_SVG_XML
            "image/png", "png" -> MimeType.IMAGE_PNG
            "image/jpeg", "image/jpg", "jpeg", "jpg" -> MimeType.IMAGE_JPEG
            "image/gif", "gif" -> MimeType.IMAGE_GIF
            "image/bmp", "bmp" -> MimeType.IMAGE_BMP
            "image/tiff", "tiff", "tif" -> MimeType.IMAGE_TIFF
            else -> null
        }
    }

    /**
     * A human-readable hint listing the accepted MIME type values, for error messages.
     */
    private fun mimeTypeValidValues(): String {
        val constants = MimeType.values().joinToString(", ") { it.name }
        return "$constants (also accepts common aliases such as application/json, text/html, " +
            "text/plain, application/javascript, application/xml, and image content-types like " +
            "image/png, image/jpeg, image/gif, image/svg+xml)"
    }

    /**
     * Resolves a highlight color name to a [HighlightColor] enum value.
     * Tries the Montoya factory method first, then falls back to valueOf.
     */
    private fun resolveHighlightColor(name: String): HighlightColor? {
        return try {
            HighlightColor.highlightColor(name)
        } catch (_: Exception) {
            try { HighlightColor.valueOf(name.uppercase()) } catch (_: Exception) { null }
        }
    }
}
