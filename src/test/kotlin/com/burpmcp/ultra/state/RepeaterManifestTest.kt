package com.burpmcp.ultra.state

import kotlin.test.Test
import kotlin.test.assertEquals

class RepeaterManifestTest {
    @Test fun `manifest groups and evicts the oldest entry`() {
        val manifest = RepeaterManifest(capacity = 2)
        manifest.add(entry("Auth", "Login"))
        manifest.add(entry("Auth", "Refresh"))
        manifest.add(entry("IDOR", "Read object"))
        assertEquals(listOf("Read object", "Refresh"), manifest.list().map { it.caption })
        assertEquals(mapOf("Auth" to 1, "IDOR" to 1), manifest.groups())
        assertEquals(1, manifest.clear("Auth"))
    }

    private fun entry(group: String, caption: String) = RepeaterManifest.Entry(
        group = group,
        caption = caption,
        host = "example.com",
        port = 443,
        useTls = true,
        method = "GET",
        path = "/"
    )
}
