package com.burpmcp.ultra.core

/** Builds predictable captions for logical Repeater tab groups. */
object RepeaterTabNames {
    private const val MAX_COMPONENT_LENGTH = 80

    fun compose(group: String?, label: String?, index: Int? = null): String? {
        val cleanGroup = clean(group)
        val cleanLabel = clean(label)
        if (cleanGroup == null) return cleanLabel

        val number = index?.coerceAtLeast(1)?.toString()?.padStart(2, '0')
        return listOfNotNull(cleanGroup, number, cleanLabel).joinToString(" · ")
    }

    private fun clean(value: String?): String? {
        val cleaned = value
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.take(MAX_COMPONENT_LENGTH)
            ?.takeIf { it.isNotEmpty() }
        return cleaned
    }
}
