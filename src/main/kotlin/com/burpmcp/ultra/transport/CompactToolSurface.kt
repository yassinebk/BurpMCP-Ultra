package com.burpmcp.ultra.transport

import com.burpmcp.ultra.safety.ActionPolicy
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

enum class ToolProfile {
    COMPACT,
    FULL;

    companion object {
        fun resolve(value: String? = System.getenv("BURPMCP_TOOL_PROFILE") ?: System.getProperty("burpmcp.tool.profile")): ToolProfile =
            if (value.equals("full", ignoreCase = true) || value.equals("legacy", ignoreCase = true)) FULL else COMPACT
    }
}

/** Replaces the legacy one-tool-per-action surface with a small set of typed routers. */
object CompactToolSurface {
    private val hiddenByDefaultPrefixes = listOf("util_", "log_", "persistence_", "preference_", "ai_", "config_")
    private val hiddenByDefault = setOf(
        "burp_shutdown", "burp_import_project_config", "burp_import_user_config",
        "burp_export_project_config", "burp_export_user_config", "burp_task_engine_set",
        "burp_command_line_args"
    )

    fun visibleInCompact(name: String): Boolean =
        name !in hiddenByDefault && hiddenByDefaultPrefixes.none(name::startsWith)

    fun routerFor(name: String): String {
        val prefix = name.substringBefore('_')
        return when (prefix) {
            "proxy" -> "burp_proxy"
            "http" -> "burp_http"
            "repeater", "intruder", "organizer", "comparer", "decoder" -> "burp_workbench"
            "scanner", "bcheck", "scancheck" -> "burp_scanner"
            "collaborator" -> "burp_collaborator"
            "websocket" -> "burp_websocket"
            "scope", "sitemap" -> "burp_target"
            "events" -> "burp_events"
            "findings" -> "burp_findings"
            "session" -> "burp_session"
            "burp", "project", "extension" -> "burp_local"
            else -> "burp_research"
        }
    }

    fun apply(
        server: Server,
        profile: ToolProfile,
        destructiveAllowed: () -> Boolean,
        execAllowed: () -> Boolean
    ) {
        if (profile == ToolProfile.FULL) return

        val originals = server.tools.values
            .filter { visibleInCompact(it.tool.name) }
            .associateBy { it.tool.name }
        server.removeTools(server.tools.keys.toList())

        val grouped = originals.values.groupBy { routerFor(it.tool.name) }.toSortedMap()
        val routers = grouped.map { (routerName, actions) ->
            router(routerName, actions.sortedBy { it.tool.name }, destructiveAllowed, execAllowed)
        }
        server.addTools(routers + capabilitiesTool(grouped))
    }

    private fun router(
        routerName: String,
        actions: List<RegisteredTool>,
        destructiveAllowed: () -> Boolean,
        execAllowed: () -> Boolean
    ): RegisteredTool {
        val byName = actions.associateBy { it.tool.name }
        val actionNames = byName.keys.sorted()
        val schema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "Action to execute")
                    put("enum", buildJsonArray { actionNames.forEach(::add) })
                }
                putJsonObject("arguments") {
                    put("type", "object")
                    put("description", "Arguments for the selected action; call burp_capabilities for its exact schema")
                }
                putJsonObject("confirm") {
                    put("type", "boolean")
                    put("description", "Required for operator-enabled destructive or exec actions")
                }
            },
            required = listOf("action")
        )
        return RegisteredTool(
            tool = Tool(
                name = routerName,
                description = "Burp ${routerName.removePrefix("burp_")} router. Select an action from the schema and pass its parameters in arguments.",
                inputSchema = schema
            ),
            handler = { request: CallToolRequest ->
                val outer = request.params.arguments ?: emptyMap()
                val action = (outer["action"] as? JsonPrimitive)?.content
                val target = action?.let(byName::get)
                if (action == null || target == null) {
                    errorResult("Unknown or missing action for $routerName")
                } else {
                    val tier = ActionPolicy.classify(action)
                    val operatorAllowed = ActionPolicy.isAllowed(action, destructiveAllowed(), execAllowed())
                    if (!operatorAllowed) {
                        ToolSafety.blocked(action, tier)
                    } else if ((tier == ActionPolicy.Tier.DESTRUCTIVE || tier == ActionPolicy.Tier.EXEC) &&
                        (outer["confirm"] as? JsonPrimitive)?.booleanOrNull != true) {
                        ToolSafety.blocked(action, tier, needsConfirmation = true)
                    } else {
                        val forwarded = outer["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                        target.handler.invoke(CallToolRequest(CallToolRequestParams(action, forwarded)))
                    }
                }
            }
        )
    }

    private fun capabilitiesTool(grouped: Map<String, List<RegisteredTool>>): RegisteredTool = RegisteredTool(
        tool = Tool(
            name = "burp_capabilities",
            description = "Discover compact Burp MCP actions and retrieve their exact argument schemas.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "Optional action name; omit for the compact router summary")
                    }
                },
                required = emptyList()
            ),
            annotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true)
        ),
        handler = { request ->
            val requested = (request.params.arguments?.get("action") as? JsonPrimitive)?.content
            val all = grouped.values.flatten().associateBy { it.tool.name }
            val output = if (requested == null) {
                buildJsonObject {
                    put("profile", "compact")
                    put("router_count", grouped.size)
                    put("action_count", all.size)
                    put("routers", buildJsonObject {
                        grouped.forEach { (router, tools) -> put(router, buildJsonArray { tools.map { it.tool.name }.sorted().forEach(::add) }) }
                    })
                    put("full_profile", "Set BURPMCP_TOOL_PROFILE=full before launching Burp to expose legacy individual tools")
                }
            } else {
                val tool = all[requested]?.tool
                if (tool == null) buildJsonObject { put("error", "Unknown compact action: $requested") }
                else buildJsonObject {
                    put("action", tool.name)
                    put("router", routerFor(tool.name))
                    put("description", tool.description ?: "")
                    put("tier", ActionPolicy.classify(tool.name).name.lowercase())
                    put("required", buildJsonArray { (tool.inputSchema.required ?: emptyList()).forEach(::add) })
                    put("properties", tool.inputSchema.properties ?: JsonObject(emptyMap()))
                }
            }
            CallToolResult(content = listOf(TextContent(output.toString())), isError = output.containsKey("error"))
        }
    )

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(TextContent(buildJsonObject { put("error", message) }.toString())),
        isError = true
    )
}
