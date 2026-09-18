package com.burpmcp.ultra.transport

import com.burpmcp.ultra.safety.ActionPolicy
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Applies one operator-owned safety policy to every registered tool. */
object ToolSafety {
    fun wrapAll(
        server: Server,
        destructiveAllowed: () -> Boolean,
        execAllowed: () -> Boolean
    ) {
        val existing = server.tools.values.toList()
        server.removeTools(existing.map { it.tool.name })
        server.addTools(existing.map { registered ->
            val name = registered.tool.name
            val tier = ActionPolicy.classify(name)
            RegisteredTool(
                tool = registered.tool.copy(
                    annotations = ToolAnnotations(
                        readOnlyHint = tier == ActionPolicy.Tier.READ,
                        destructiveHint = tier == ActionPolicy.Tier.DESTRUCTIVE,
                        idempotentHint = tier == ActionPolicy.Tier.READ,
                        openWorldHint = name.startsWith("http_") || name.startsWith("scanner_") ||
                            name.startsWith("recon_") || name.startsWith("collaborator_")
                    )
                ),
                handler = { request: CallToolRequest ->
                    if (!ActionPolicy.isAllowed(name, destructiveAllowed(), execAllowed())) {
                        blocked(name, tier)
                    } else {
                        registered.handler.invoke(request)
                    }
                }
            )
        })
    }

    fun blocked(name: String, tier: ActionPolicy.Tier, needsConfirmation: Boolean = false): CallToolResult {
        val preference = if (tier == ActionPolicy.Tier.EXEC) "mcp_allow_exec" else "mcp_allow_destructive"
        val message = if (needsConfirmation) {
            "Blocked: '$name' requires confirm=true after the operator enables $preference."
        } else {
            "Blocked: '$name' is ${tier.name.lowercase()} and the operator has not enabled $preference."
        }
        return CallToolResult(
            content = listOf(TextContent(buildJsonObject {
                put("error", message)
                put("tool", name)
                put("tier", tier.name.lowercase())
                put("blocked_by", preference)
                put("retryable", false)
            }.toString())),
            isError = true
        )
    }
}
