package com.burpmcp.ultra.transport

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSafetyTest {
    @Test
    fun `central wrapper blocks operator-gated tools before their handler runs`() {
        val server = Server(
            serverInfo = Implementation("test", "1"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
        )
        var invoked = false
        server.addTool(
            name = "events_clear",
            description = "test",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) {
            invoked = true
            CallToolResult(content = listOf(TextContent("{}")))
        }

        ToolSafety.wrapAll(server, destructiveAllowed = { false }, execAllowed = { false })

        val registered = server.tools.getValue("events_clear")
        val result = runBlocking {
            registered.handler.invoke(CallToolRequest(CallToolRequestParams("events_clear", buildJsonObject {})))
        }
        assertTrue(registered.tool.annotations?.destructiveHint == true)
        assertTrue(result.isError == true)
        assertFalse(invoked)
    }
}
