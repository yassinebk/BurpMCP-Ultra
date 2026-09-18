package com.burpmcp.ultra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedHistorySearchTest {
    @Test fun `cursor window is exclusive`() {
        assertTrue(BoundedHistorySearch.inWindow(50, beforeId = 51, afterId = 49))
        assertFalse(BoundedHistorySearch.inWindow(51, beforeId = 51, afterId = null))
        assertFalse(BoundedHistorySearch.inWindow(49, beforeId = null, afterId = 49))
    }

    @Test fun `limits cannot be unbounded`() {
        assertEquals(1, BoundedHistorySearch.limit(0))
        assertEquals(BoundedHistorySearch.MAX_LIMIT, BoundedHistorySearch.limit(Int.MAX_VALUE))
        assertEquals(BoundedHistorySearch.MAX_SCAN_LIMIT, BoundedHistorySearch.scanLimit(Int.MAX_VALUE))
        assertEquals(BoundedHistorySearch.MAX_TIME_BUDGET_MS, BoundedHistorySearch.timeBudgetMs(Long.MAX_VALUE))
    }

    @Test fun `snippet marks omitted context`() {
        assertEquals("…def…", BoundedHistorySearch.snippet("abcdefghij", 4, 5, contextChars = 1))
    }
}
