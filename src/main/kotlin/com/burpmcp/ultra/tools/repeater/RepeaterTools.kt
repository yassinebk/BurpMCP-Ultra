package com.burpmcp.ultra.tools.repeater

import com.burpmcp.ultra.bridge.RepeaterBridge
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.*

object RepeaterTools {

    fun register(server: Server, bridge: RepeaterBridge) {
        server.addTool(
            name = "repeater_send",
            description = "Send an HTTP request to Burp Suite's Repeater tool. " +
                "Creates a new Repeater tab with the specified request, allowing manual " +
                "replay and modification. Parameters: request (raw HTTP request string), " +
                "host (target hostname), port (target port number), use_tls (boolean, " +
                "whether to use HTTPS), tab_name (optional, name for the Repeater tab).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("request") { put("type", "string"); put("description", "Raw HTTP request string") }
                    putJsonObject("host") { put("type", "string"); put("description", "Target hostname") }
                    putJsonObject("port") { put("type", "integer"); put("description", "Target port number") }
                    putJsonObject("use_tls") { put("type", "boolean"); put("description", "Whether to use HTTPS (TLS)") }
                    putJsonObject("tab_name") { put("type", "string"); put("description", "Optional name for the Repeater tab") }
                    putJsonObject("group_name") { put("type", "string"); put("description", "Optional logical group prefix for the tab caption") }
                    putJsonObject("purpose") { put("type", "string"); put("description", "Optional test purpose stored in the MCP tab manifest") }
                    putJsonObject("source_history_id") { put("type", "integer"); put("description", "Optional source proxy-history id") }
                },
                required = listOf("request", "host", "port")
            )
        ) { request ->
            try {
                val args = request.params.arguments ?: emptyMap()
                val rawRequest = args["request"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("""{"error":"Missing required parameter: request"}""")),
                        isError = true
                    )
                val host = args["host"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("""{"error":"Missing required parameter: host"}""")),
                        isError = true
                    )
                val port = args["port"]?.jsonPrimitive?.intOrNull
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("""{"error":"Missing required parameter: port"}""")),
                        isError = true
                    )
                val useTls = args["use_tls"]?.jsonPrimitive?.booleanOrNull ?: false
                val tabName = args["tab_name"]?.jsonPrimitive?.contentOrNull
                val groupName = args["group_name"]?.jsonPrimitive?.contentOrNull
                val purpose = args["purpose"]?.jsonPrimitive?.contentOrNull
                val sourceHistoryId = args["source_history_id"]?.jsonPrimitive?.intOrNull

                val result = bridge.sendToRepeater(rawRequest, host, port, useTls, tabName, groupName, purpose = purpose, sourceHistoryId = sourceHistoryId)
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("""{"error":"${e.message}"}""")),
                    isError = true
                )
            }
        }

        server.addTool(
            name = "repeater_send_batch",
            description = "Create up to 100 related Repeater tabs together with numbered logical-group captions. Native Repeater tab groups are not exposed by Montoya.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("group_name") { put("type", "string") }
                    putJsonObject("requests") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("request") { put("type", "string") }
                                putJsonObject("host") { put("type", "string") }
                                putJsonObject("port") { put("type", "integer") }
                                putJsonObject("use_tls") { put("type", "boolean") }
                                putJsonObject("tab_name") { put("type", "string") }
                                putJsonObject("purpose") { put("type", "string") }
                                putJsonObject("source_history_id") { put("type", "integer") }
                            }
                            putJsonArray("required") { add("request"); add("host"); add("port") }
                        }
                    }
                },
                required = listOf("group_name", "requests")
            )
        ) { request ->
            try {
                val args = request.params.arguments ?: emptyMap()
                val groupName = args["group_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool CallToolResult(listOf(TextContent("{\"error\":\"Missing required parameter: group_name\"}")), isError = true)
                val rawItems = args["requests"]?.jsonArray
                    ?: return@addTool CallToolResult(listOf(TextContent("{\"error\":\"Missing required parameter: requests\"}")), isError = true)
                if (rawItems.isEmpty()) {
                    return@addTool CallToolResult(listOf(TextContent("{\"error\":\"requests must not be empty\"}")), isError = true)
                }
                val items = rawItems.mapIndexed { index, element ->
                    val item = element.jsonObject
                    val raw = item["request"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("requests[$index].request is required")
                    val host = item["host"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("requests[$index].host is required")
                    val port = item["port"]?.jsonPrimitive?.intOrNull
                        ?: throw IllegalArgumentException("requests[$index].port is required")
                    RepeaterBridge.BatchItem(
                        request = raw,
                        host = host,
                        port = port,
                        useTls = item["use_tls"]?.jsonPrimitive?.booleanOrNull ?: false,
                        tabName = item["tab_name"]?.jsonPrimitive?.contentOrNull,
                        purpose = item["purpose"]?.jsonPrimitive?.contentOrNull,
                        sourceHistoryId = item["source_history_id"]?.jsonPrimitive?.intOrNull
                    )
                }
                CallToolResult(content = listOf(TextContent(bridge.sendBatchToRepeater(groupName, items).toString())))
            } catch (e: Exception) {
                CallToolResult(listOf(TextContent(buildJsonObject { put("error", e.message ?: "Invalid batch") }.toString())), isError = true)
            }
        }

        server.addTool(
            name = "repeater_manifest_list",
            description = "List Repeater tabs created through MCP with logical group, purpose, and source-history metadata.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("group_name") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                },
                required = emptyList()
            )
        ) { request ->
            val args = request.params.arguments ?: emptyMap()
            CallToolResult(listOf(TextContent(bridge.listManifest(
                args["group_name"]?.jsonPrimitive?.contentOrNull,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 100
            ).toString())))
        }

        server.addTool(
            name = "repeater_manifest_groups",
            description = "Summarize logical MCP-created Repeater tab groups.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) { _ -> CallToolResult(listOf(TextContent(bridge.manifestGroups().toString()))) }

        server.addTool(
            name = "repeater_manifest_clear",
            description = "Clear MCP manifest metadata for all tabs or one logical group. Native Repeater tabs are not closed or modified.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { putJsonObject("group_name") { put("type", "string") } },
                required = emptyList()
            )
        ) { request ->
            val group = request.params.arguments?.get("group_name")?.jsonPrimitive?.contentOrNull
            CallToolResult(listOf(TextContent(bridge.clearManifest(group).toString())))
        }
    }
}
