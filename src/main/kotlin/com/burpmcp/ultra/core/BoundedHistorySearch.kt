package com.burpmcp.ultra.core

/** Pure helpers shared by the bounded proxy-history tools. */
object BoundedHistorySearch {
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 1_000
    const val DEFAULT_SCAN_LIMIT = 1_000
    const val MAX_SCAN_LIMIT = 10_000
    const val DEFAULT_TIME_BUDGET_MS = 3_000L
    const val MAX_TIME_BUDGET_MS = 15_000L
    const val MAX_PATTERN_LENGTH = 2_000

    fun limit(value: Int): Int = value.coerceIn(1, MAX_LIMIT)

    fun scanLimit(value: Int): Int = value.coerceIn(1, MAX_SCAN_LIMIT)

    fun timeBudgetMs(value: Long): Long = value.coerceIn(100L, MAX_TIME_BUDGET_MS)

    fun inWindow(id: Int, beforeId: Int?, afterId: Int?): Boolean =
        (beforeId == null || id < beforeId) && (afterId == null || id > afterId)

    fun snippet(input: String, matchStart: Int, matchEndExclusive: Int, contextChars: Int = 120): String {
        if (input.isEmpty()) return ""
        val start = (matchStart - contextChars.coerceAtLeast(0)).coerceAtLeast(0)
        val end = (matchEndExclusive + contextChars.coerceAtLeast(0)).coerceAtMost(input.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < input.length) "…" else ""
        return prefix + BodyText.stripLoneSurrogates(input.substring(start, end)) + suffix
    }
}
