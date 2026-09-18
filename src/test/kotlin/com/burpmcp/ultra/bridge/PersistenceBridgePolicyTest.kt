package com.burpmcp.ultra.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistenceBridgePolicyTest {
    @Test
    fun `operator preferences cannot be read or changed through generic MCP storage`() {
        assertTrue(PersistenceBridge.isReservedPreferenceKey("mcp_allow_destructive"))
        assertTrue(PersistenceBridge.isReservedPreferenceKey("MCP_AUTH_TOKEN"))
        assertTrue(PersistenceBridge.isReservedPreferenceKey("mcp_scope_mode"))
        assertFalse(PersistenceBridge.isReservedPreferenceKey("research_note"))
    }
}
