package com.burpmcp.ultra.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveHistoryIndexTest {
    @Test fun `entry and byte caps evict oldest traffic`() {
        val index = LiveHistoryIndex(maxEntries = 2, maxBytes = 1_000, maxMessageChars = 100)
        repeat(3) { n -> index.recordRequest(n, "GET", "https://x/$n", "x", 443, true, "r$n") }
        assertNull(index.get(0))
        assertEquals(listOf(2, 1), index.newest().map { it.messageId })
    }

    @Test fun `responses update their matching request`() {
        val index = LiveHistoryIndex()
        index.recordRequest(7, "POST", "https://x/a", "x", 443, true, "request")
        index.recordResponse(7, "response", 201, "JSON")
        assertEquals(201, index.get(7)?.statusCode)
        assertEquals("response", index.get(7)?.response)
    }

    @Test fun `large messages are capped`() {
        val index = LiveHistoryIndex(maxMessageChars = 4)
        index.recordRequest(1, "GET", "https://x", "x", 443, true, "123456")
        assertEquals("1234", index.get(1)?.request)
        assertTrue(index.get(1)?.requestTruncated == true)
    }

    @Test fun `total byte budget evicts even below entry cap`() {
        val index = LiveHistoryIndex(maxEntries = 100, maxBytes = 20, maxMessageChars = 100)
        index.recordRequest(1, "GET", "https://x/1", "x", 443, true, "123456")
        index.recordRequest(2, "GET", "https://x/2", "x", 443, true, "abcdef")
        assertNull(index.get(1))
        assertEquals(1, index.stats().entries)
    }

    @Test fun `clear is explicit and measurable`() {
        val index = LiveHistoryIndex()
        index.recordRequest(1, "GET", "https://x", "x", 443, true, "r")
        assertEquals(1, index.clear())
        assertEquals(0, index.stats().entries)
    }
}
