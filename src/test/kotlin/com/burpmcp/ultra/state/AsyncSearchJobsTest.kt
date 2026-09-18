package com.burpmcp.ultra.state

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AsyncSearchJobsTest {
    @Test fun `completed jobs expose their structured result`() {
        AsyncSearchJobs().use { jobs ->
            val id = jobs.submit { buildJsonObject { put("matches", 3) } }
            var snapshot: AsyncSearchJobs.Snapshot? = null
            repeat(100) {
                snapshot = jobs.get(id)
                if (snapshot?.status == "completed") return@repeat
                Thread.sleep(5)
            }
            assertNotNull(snapshot)
            assertEquals("completed", snapshot!!.status)
            assertEquals("3", snapshot!!.result?.get("matches").toString())
        }
    }
}
