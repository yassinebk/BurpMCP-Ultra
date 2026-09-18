package com.burpmcp.ultra.bridge

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.HttpService
import com.burpmcp.ultra.safety.RequestHygiene
import com.burpmcp.ultra.core.RepeaterTabNames
import com.burpmcp.ultra.state.RepeaterManifest
import kotlinx.serialization.json.*

class RepeaterBridge(private val api: MontoyaApi) {
    private val manifest = RepeaterManifest()

    data class BatchItem(
        val request: String,
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val tabName: String?,
        val purpose: String? = null,
        val sourceHistoryId: Int? = null
    )

    fun sendToRepeater(
        request: String,
        host: String,
        port: Int,
        useTls: Boolean,
        tabName: String?,
        groupName: String? = null,
        groupIndex: Int? = null,
        purpose: String? = null,
        sourceHistoryId: Int? = null
    ): JsonObject {
        val httpService = HttpService.httpService(host, port, useTls)
        // Normalize line endings so a bare-LF request doesn't fold into the HTTP/2
        // :path and land in Repeater "kettled" and unsendable (issue #7 request-line variant).
        val httpRequest = HttpRequest.httpRequest(httpService, RequestHygiene.normalizeCrlf(request))

        val caption = RepeaterTabNames.compose(groupName, tabName, groupIndex)
        if (caption != null) {
            api.repeater().sendToRepeater(httpRequest, caption)
        } else {
            api.repeater().sendToRepeater(httpRequest)
        }

        val requestLine = RequestHygiene.normalizeCrlf(request).lineSequence().firstOrNull().orEmpty().split(' ')
        val manifestEntry = manifest.add(RepeaterManifest.Entry(
            group = groupName,
            caption = caption ?: "auto",
            host = host,
            port = port,
            useTls = useTls,
            method = requestLine.getOrNull(0).orEmpty(),
            path = requestLine.getOrNull(1).orEmpty(),
            purpose = purpose?.trim()?.take(500),
            sourceHistoryId = sourceHistoryId
        ))

        return buildJsonObject {
            put("manifest_id", manifestEntry.id)
            put("tab_name", caption ?: "auto")
            if (groupName != null) put("logical_group", groupName)
            put("status", "created")
            put("host", host)
            put("port", port)
            put("use_tls", useTls)
        }
    }

    fun sendBatchToRepeater(groupName: String, items: List<BatchItem>): JsonObject {
        val capped = items.take(100)
        val results = buildJsonArray {
            capped.forEachIndexed { index, item ->
                add(sendToRepeater(
                    request = item.request,
                    host = item.host,
                    port = item.port,
                    useTls = item.useTls,
                    tabName = item.tabName,
                    groupName = groupName,
                    groupIndex = index + 1,
                    purpose = item.purpose,
                    sourceHistoryId = item.sourceHistoryId
                ))
            }
        }
        return buildJsonObject {
            put("logical_group", groupName)
            put("created", results.size)
            put("native_group", false)
            put("note", "Montoya does not expose native Repeater tab groups; captions keep these tabs adjacent and identifiable.")
            put("tabs", results)
        }
    }

    fun listManifest(group: String?, limit: Int): JsonObject {
        val entries = manifest.list(group, limit)
        return buildJsonObject {
            put("returned", entries.size)
            group?.let { put("group", it) }
            put("tabs", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("manifest_id", entry.id)
                        entry.group?.let { put("group", it) }
                        put("caption", entry.caption)
                        put("host", entry.host)
                        put("port", entry.port)
                        put("use_tls", entry.useTls)
                        put("method", entry.method)
                        put("path", entry.path)
                        entry.purpose?.let { put("purpose", it) }
                        entry.sourceHistoryId?.let { put("source_history_id", it) }
                        put("created_at", entry.createdAt)
                    })
                }
            })
        }
    }

    fun manifestGroups(): JsonObject = buildJsonObject {
        put("groups", buildJsonArray {
            manifest.groups().forEach { (name, count) ->
                add(buildJsonObject { put("name", name); put("tabs", count) })
            }
        })
        put("native_groups_supported", false)
    }

    fun clearManifest(group: String?): JsonObject = buildJsonObject {
        put("cleared", manifest.clear(group))
        group?.let { put("group", it) }
        put("repeater_tabs_modified", false)
    }
}
