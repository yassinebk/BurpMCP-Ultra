package com.burpmcp.ultra.transport

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactToolSurfaceTest {
    @Test
    fun `profile defaults to compact and keeps an explicit full escape hatch`() {
        assertEquals(ToolProfile.COMPACT, ToolProfile.resolve(null))
        assertEquals(ToolProfile.FULL, ToolProfile.resolve("full"))
        assertEquals(ToolProfile.FULL, ToolProfile.resolve("legacy"))
    }

    @Test
    fun `compact visibility removes local utilities and administrative primitives`() {
        assertFalse(CompactToolSurface.visibleInCompact("util_hash"))
        assertFalse(CompactToolSurface.visibleInCompact("ai_prompt"))
        assertFalse(CompactToolSurface.visibleInCompact("persistence_store"))
        assertFalse(CompactToolSurface.visibleInCompact("burp_shutdown"))
        assertTrue(CompactToolSurface.visibleInCompact("proxy_history_summary"))
        assertTrue(CompactToolSurface.visibleInCompact("repeater_send_batch"))
    }

    @Test
    fun `compact profile replaces individual tools with routers and discovery`() {
        val server = testServer()
        addStub(server, "proxy_history_summary")
        addStub(server, "proxy_index_clear")
        addStub(server, "http_send_request")
        addStub(server, "util_hash")

        CompactToolSurface.apply(server, ToolProfile.COMPACT, { false }, { false })

        assertEquals(setOf("burp_proxy", "burp_http", "burp_capabilities"), server.tools.keys)
        val proxyActions = server.tools.getValue("burp_proxy").tool.inputSchema.properties
            ?.get("action")?.toString().orEmpty()
        assertTrue(proxyActions.contains("proxy_history_summary"))
        assertTrue(proxyActions.contains("proxy_index_clear"))
        assertFalse(server.tools.keys.contains("util_hash"))
    }

    @Test
    fun `full profile preserves individual tools`() {
        val server = testServer()
        addStub(server, "proxy_history_summary")
        addStub(server, "util_hash")

        CompactToolSurface.apply(server, ToolProfile.FULL, { false }, { false })

        assertEquals(setOf("proxy_history_summary", "util_hash"), server.tools.keys)
    }

    private fun testServer() = Server(
        serverInfo = Implementation("test", "1"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()))
    )

    private fun addStub(server: Server, name: String) {
        server.addTool(
            name = name,
            description = name,
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) { CallToolResult(content = listOf(TextContent("{}"))) }
    }
}
