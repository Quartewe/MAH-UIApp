package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ShowDefinition
import kotlinx.serialization.json.*
import java.io.File

/** Reads afresh on every refresh. Runtime output is not part of the immutable PI declaration. */
object ShowContentReader {
    private val pretty = Json { prettyPrint = true }

    fun read(root: File, show: ShowDefinition): String {
        val base = root.canonicalFile
        val path = show.path.removePrefix("{PROJECT_DIR}/").removePrefix("./")
        require(!File(path).isAbsolute && ':' !in path && '\\' !in path) { "Invalid show path" }
        val file = File(base, path).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Show path escapes the project" }
        if (!file.isFile) return ""
        require(file.length() <= 2 * 1024 * 1024) { "Show file is too large" }
        val raw = file.readText(Charsets.UTF_8)
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        if (show.jsonPath.isNullOrBlank() && (show.mode == "markdown" || parsed == null)) return raw
        var value = parsed ?: return raw
        show.jsonPath?.split('.')?.filter(String::isNotEmpty)?.forEach { key ->
            value = when (val node = value) {
                is JsonObject -> node[key]
                is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            } ?: JsonObject(emptyMap())
        }
        return "```json\n${pretty.encodeToString(JsonElement.serializer(), value)}\n```"
    }
}
