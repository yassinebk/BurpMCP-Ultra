package com.burpmcp.ultra.state

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class PersistentHistoryStore(
    private val file: Path,
    private val maxFileBytes: Long = 256L * 1024 * 1024
) {
    data class Stats(val path: String, val exists: Boolean, val bytes: Long, val maxBytes: Long)

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun append(entry: LiveHistoryIndex.Entry) {
        ensureParent()
        val line = serialize(redact(entry)) + "\n"
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        restrictPermissions()
    }

    @Synchronized
    fun load(maxEntries: Int): List<LiveHistoryIndex.Entry> {
        if (!Files.exists(file)) return emptyList()
        val latest = LinkedHashMap<Int, LiveHistoryIndex.Entry>()
        Files.newBufferedReader(file).useLines { lines ->
            lines.forEach { line ->
                parse(line)?.let { entry ->
                    latest.remove(entry.messageId)
                    latest[entry.messageId] = entry
                    while (latest.size > maxEntries) latest.remove(latest.keys.first())
                }
            }
        }
        return latest.values.toList()
    }

    @Synchronized
    fun compact(entriesOldestFirst: List<LiveHistoryIndex.Entry>, force: Boolean = false): Boolean {
        if (entriesOldestFirst.isEmpty() && Files.exists(file) && Files.size(file) > 0L) return false
        if (!force && (!Files.exists(file) || Files.size(file) <= maxFileBytes)) return false
        ensureParent()
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { writer ->
            entriesOldestFirst.forEach { entry ->
                writer.append(serialize(redact(entry))).append('\n')
            }
        }
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        restrictPermissions()
        return true
    }

    @Synchronized
    fun clear(): Boolean = !Files.exists(file) || Files.deleteIfExists(file)

    fun stats(): Stats {
        val exists = Files.exists(file)
        return Stats(file.toString(), exists, if (exists) Files.size(file) else 0L, maxFileBytes)
    }

    private fun serialize(entry: LiveHistoryIndex.Entry): String = buildJsonObject {
        put("message_id", entry.messageId)
        put("method", entry.method)
        put("url", entry.url)
        put("host", entry.host)
        put("port", entry.port)
        put("secure", entry.secure)
        put("request", entry.request)
        put("request_truncated", entry.requestTruncated)
        entry.response?.let { put("response", it) }
        put("response_truncated", entry.responseTruncated)
        entry.statusCode?.let { put("status_code", it) }
        entry.mimeType?.let { put("mime_type", it) }
        put("observed_at", entry.observedAt)
    }.toString()

    private fun parse(line: String): LiveHistoryIndex.Entry? = try {
        val value = json.parseToJsonElement(line).jsonObject
        LiveHistoryIndex.Entry(
            messageId = value.getValue("message_id").jsonPrimitive.int,
            method = value.getValue("method").jsonPrimitive.content,
            url = value.getValue("url").jsonPrimitive.content,
            host = value.getValue("host").jsonPrimitive.content,
            port = value.getValue("port").jsonPrimitive.int,
            secure = value.getValue("secure").jsonPrimitive.boolean,
            request = value.getValue("request").jsonPrimitive.content,
            requestTruncated = value["request_truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
            response = value["response"]?.jsonPrimitive?.contentOrNull,
            responseTruncated = value["response_truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
            statusCode = value["status_code"]?.jsonPrimitive?.intOrNull,
            mimeType = value["mime_type"]?.jsonPrimitive?.contentOrNull,
            observedAt = value["observed_at"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    } catch (_: Exception) {
        null
    }

    private fun redact(entry: LiveHistoryIndex.Entry) = entry.copy(
        url = redactMessage(entry.url),
        request = redactMessage(entry.request),
        response = entry.response?.let(::redactMessage)
    )

    private fun redactMessage(message: String): String {
        val headerNames = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key")
        val headerRedacted = message.lineSequence().joinToString("\n") { line ->
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).trim().lowercase() in headerNames) {
                line.substring(0, separator + 1) + " [REDACTED]"
            } else line
        }
        return SECRET_VALUE.replace(headerRedacted) { match ->
            match.groupValues[1] + match.groupValues[2] + "[REDACTED]" + match.groupValues[2]
        }
    }

    private fun ensureParent() = Files.createDirectories(file.parent)

    private fun restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ))
        } catch (_: Exception) { }
    }

    companion object {
        private val SECRET_VALUE = Regex(
            "(?i)([\\\"']?(?:password|passwd|access_token|refresh_token|client_secret|api_key)[\\\"']?\\s*[:=]\\s*)([\\\"']?)[^\\\"'&\\s,}]+\\2"
        )

        fun forProject(projectName: String, baseDir: Path = Path.of(System.getProperty("user.home") ?: ".", ".burpmcp-ultra", "history")): PersistentHistoryStore {
            val digest = MessageDigest.getInstance("SHA-256").digest(projectName.ifEmpty { "default" }.toByteArray())
            val key = digest.take(12).joinToString("") { "%02x".format(it) }
            return PersistentHistoryStore(baseDir.resolve("$key.jsonl"))
        }
    }
}
