package com.burpmcp.ultra.state

data class HistoryCapturePolicy(
    val enabled: Boolean = true,
    val inScopeOnly: Boolean = false,
    val includeHosts: List<String> = emptyList(),
    val excludeHosts: List<String> = emptyList(),
    val excludeExtensions: Set<String> = emptySet()
) {
    fun accepts(host: String, url: String, inScope: Boolean): Boolean {
        if (!enabled || (inScopeOnly && !inScope)) return false
        if (includeHosts.isNotEmpty() && includeHosts.none { globMatches(it, host) }) return false
        if (excludeHosts.any { globMatches(it, host) }) return false

        val path = url.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return extension.isEmpty() || extension !in excludeExtensions
    }

    companion object {
        fun normalized(
            enabled: Boolean,
            inScopeOnly: Boolean,
            includeHosts: List<String>,
            excludeHosts: List<String>,
            excludeExtensions: Collection<String>
        ) = HistoryCapturePolicy(
            enabled = enabled,
            inScopeOnly = inScopeOnly,
            includeHosts = cleanList(includeHosts),
            excludeHosts = cleanList(excludeHosts),
            excludeExtensions = excludeExtensions.map { it.trim().removePrefix(".").lowercase() }
                .filter { it.isNotEmpty() }.toSet()
        )

        private fun cleanList(values: List<String>) = values.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }.distinct()

        private fun globMatches(pattern: String, value: String): Boolean {
            val p = pattern.lowercase()
            val v = value.lowercase()
            if ('*' !in p) return v == p
            val parts = p.split('*')
            var offset = 0
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty()) continue
                val found = v.indexOf(part, offset)
                if (found < 0 || (index == 0 && !p.startsWith('*') && found != 0)) return false
                offset = found + part.length
            }
            return p.endsWith('*') || parts.lastOrNull().orEmpty().let(v::endsWith)
        }
    }
}
