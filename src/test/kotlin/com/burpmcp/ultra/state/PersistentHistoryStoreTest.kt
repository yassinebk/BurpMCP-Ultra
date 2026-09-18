package com.burpmcp.ultra.state

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentHistoryStoreTest {
    @Test fun `sidecar redacts secrets and restores the newest snapshot`() {
        val directory = Files.createTempDirectory("burpmcp-history-test")
        try {
            val file = directory.resolve("index.jsonl")
            val store = PersistentHistoryStore(file, maxFileBytes = 1_000_000)
            val request = "GET / HTTP/1.1\r\nAuthorization: Bearer secret\r\nCookie: sid=secret\r\n\r\n"
            val original = entry(7, request, null)
            store.append(original)
            store.append(original.copy(response = "HTTP/1.1 200 OK\r\nSet-Cookie: token=secret\r\n\r\n{\"access_token\":\"secret\"}"))

            val raw = file.readText()
            assertFalse("Bearer secret" in raw)
            assertFalse("sid=secret" in raw)
            assertFalse("token=secret" in raw)
            assertFalse("access_token\":\"secret" in raw)
            assertFalse("access_token=secret" in raw)
            val restored = store.load(10)
            assertEquals(1, restored.size)
            assertTrue(restored.single().response?.contains("[REDACTED]") == true)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `forced compaction keeps one snapshot per supplied entry`() {
        val directory = Files.createTempDirectory("burpmcp-history-test")
        try {
            val file = directory.resolve("index.jsonl")
            val store = PersistentHistoryStore(file, maxFileBytes = 1)
            store.append(entry(1, "one", null))
            store.append(entry(1, "one", "updated"))
            store.compact(listOf(entry(1, "one", "updated")), force = true)
            assertEquals(1, file.readText().lineSequence().count { it.isNotBlank() })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun entry(id: Int, request: String, response: String?) = LiveHistoryIndex.Entry(
        messageId = id,
        method = "GET",
        url = "https://example.com/?access_token=secret",
        host = "example.com",
        port = 443,
        secure = true,
        request = request,
        requestTruncated = false,
        response = response
    )
}
