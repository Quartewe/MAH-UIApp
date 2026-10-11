package com.aliothmoon.maafw.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** MAH Agent 按文件名在 data/auto_combat 下递归查找，名称必须全树唯一。 */
class CombatFileStore(projectDir: File) {
    private val root = File(projectDir, "data/auto_combat").canonicalFile

    data class Entry(val name: String, val relativePath: String)

    fun list(): List<Entry> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { it.canonicalFile.toPath().startsWith(root.toPath()) }
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .map { checked(it.relativeTo(root).invariantSeparatorsPath) }
            .map { Entry(it.name, it.relativeTo(root).invariantSeparatorsPath) }
            .sortedBy { it.relativePath }
            .toList()
    }

    /** 原文保存，保留键顺序、未知字段和数值；UTF-8 BOM 去掉后 Agent 可直接读取。 */
    @Synchronized
    fun importFile(name: String, input: InputStream): Entry {
        require(name.isNotBlank() && name.endsWith(".json", ignoreCase = true))
        require(name.none { it in "\\/:*?\"<>|[]" || it.isISOControl() })
        val text = readBounded(input).decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
        val document = Json.parseToJsonElement(text) as? JsonObject
        require(document?.get("team") is JsonObject && document["fight"] is JsonObject) {
            "Expected team and fight objects"
        }
        check(root.isDirectory || root.mkdirs()) { "Cannot create combat directory" }
        val occupied = list().mapTo(mutableSetOf()) { it.name.lowercase(java.util.Locale.ROOT) }
        val stem = name.dropLast(5)
        var filename = "$stem.json"
        var suffix = 2
        while (filename.lowercase(java.util.Locale.ROOT) in occupied || File(root, filename).exists()) {
            filename = "${stem}_${suffix++}.json"
        }
        val destination = checked(filename)
        val temporary = File.createTempFile(".combat-import-", ".tmp", root)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            Files.move(temporary.toPath(), destination.toPath())
        } finally {
            temporary.delete()
        }
        return Entry(destination.name, destination.name)
    }

    fun exportFile(relativePath: String): ByteArray {
        val file = checked(relativePath)
        require(file.isFile && file.extension.equals("json", ignoreCase = true))
        return file.inputStream().use(::readBounded)
    }

    private fun checked(relativePath: String): File {
        require(!File(relativePath).isAbsolute && '\\' !in relativePath && ':' !in relativePath)
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root)
        return file
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Combat file exceeds 2 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
