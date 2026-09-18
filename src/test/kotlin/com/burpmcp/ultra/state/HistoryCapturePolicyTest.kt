package com.burpmcp.ultra.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryCapturePolicyTest {
    @Test fun `host and extension filters are applied together`() {
        val policy = HistoryCapturePolicy.normalized(
            enabled = true,
            inScopeOnly = false,
            includeHosts = listOf("*.example.com"),
            excludeHosts = listOf("analytics.example.com"),
            excludeExtensions = listOf(".png", "JS")
        )
        assertTrue(policy.accepts("api.example.com", "https://api.example.com/users?id=1", false))
        assertFalse(policy.accepts("analytics.example.com", "https://analytics.example.com/x", true))
        assertFalse(policy.accepts("api.example.com", "https://api.example.com/app.js?v=1", true))
        assertFalse(policy.accepts("example.net", "https://example.net/users", true))
    }

    @Test fun `scope-only capture rejects out of scope traffic`() {
        val policy = HistoryCapturePolicy(inScopeOnly = true)
        assertFalse(policy.accepts("example.com", "https://example.com/", false))
        assertTrue(policy.accepts("example.com", "https://example.com/", true))
    }
}
