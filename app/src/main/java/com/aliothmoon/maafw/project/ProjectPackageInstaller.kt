package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.DiagnosticSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

@Serializable
data class InstalledProjectPackages(
    val projectVersion: String = "",
    val resourceVersion: String = "",
    val revision: String = "",
    val owners: Map<String, Map<String, String>> = emptyMap(),
)

enum class ProjectPackageTarget { Project, Resource }

/**
 * Filesystem transaction, independent of UI and transport. The caller excludes task execution.
 * Only a complete, validated candidate replaces the stable PI directory. An interrupted swap is
 * rolled back on startup before either the loader or runner is allowed to see it.
 */
class ProjectPackageInstaller(private val root: File) {
    private val work = File(root.parentFile, ".pi-update")
    private val candidate = File(work, "candidate")
    private val backup = File(work, "backup")
    private val committed = File(work, "committed")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun state(): InstalledProjectPackages = File(root, STATE).takeIf(File::isFile)?.let {
        json.decodeFromString<InstalledProjectPackages>(it.readText())
    } ?: InstalledProjectPackages()

    @Synchronized
    fun recover() {
        if (backup.isDirectory && !committed.isFile) {
            if (root.exists()) check(root.deleteRecursively()) { "Cannot remove interrupted update" }
            check(backup.renameTo(root)) { "Cannot restore previous project" }
        }
        if (work.exists()) check(work.deleteRecursively()) { "Cannot clear update staging" }
    }

    @Synchronized
    fun install(archive: File, target: ProjectPackageTarget, version: String) {
        recover()
        check(root.isDirectory) { "Project is not installed" }
        val before = state()
        val unpacked = File(work, "archive").also { it.mkdirs() }
        try {
            unzip(archive, unpacked)
            val manifest = File(unpacked, MANIFEST).takeIf(File::isFile)?.let {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
            val mapped = when (target) {
                ProjectPackageTarget.Project -> projectFiles(unpacked, requireNotNull(manifest) { "Missing project manifest" }, version)
                ProjectPackageTarget.Resource -> resourceFiles(unpacked)
            }
            val owner = if (target == ProjectPackageTarget.Project) "project" else "resource"
            val otherOwner = if (owner == "project") "resource" else "project"
            require(mapped.keys.none { it in before.owners[otherOwner].orEmpty() }) { "Package overlaps another update channel" }
            check(root.copyRecursively(candidate, overwrite = true)) { "Cannot stage current project" }
            // Only the previous package's own files may be removed. Runtime paths remain user-owned.
            (before.owners[owner].orEmpty().keys - mapped.keys).filterNot(::mutablePath).forEach {
                checked(candidate, it).takeIf(File::isFile)?.let { file -> check(file.delete()) }
            }
            val hashes = mapped.mapValues { (name, file) ->
                val dest = checked(candidate, name)
                if (!mutablePath(name) || !dest.exists()) {
                    dest.parentFile!!.mkdirs()
                    file.copyTo(dest, overwrite = true)
                }
                sha256(file)
            }
            val installedInterface = checked(candidate, "interface.json")
            val pi = Json.parseToJsonElement(installedInterface.readText()).jsonObject.toMutableMap()
            if (target == ProjectPackageTarget.Resource) pi["resource_version"] = JsonPrimitive(version)
            else pi["resource_version"] = JsonPrimitive(before.resourceVersion)
            installedInterface.writeText(JsonObject(pi).toString())
            validate(candidate)
            val after = before.copy(
                projectVersion = if (target == ProjectPackageTarget.Project) version else before.projectVersion,
                resourceVersion = if (target == ProjectPackageTarget.Resource) version else before.resourceVersion,
                revision = UUID.randomUUID().toString(),
                owners = before.owners + (owner to hashes),
            )
            durableWrite(File(candidate, STATE), json.encodeToString(after))
            check(root.renameTo(backup)) { "Cannot back up installed project" }
            check(candidate.renameTo(root)) { "Cannot activate staged project" }
            durableWrite(committed, after.revision)
        } catch (error: Exception) {
            recover()
            throw error
        }
        recover()
    }

    private fun projectFiles(dir: File, manifest: JsonObject, version: String): Map<String, File> {
        require(manifest["format"]?.jsonPrimitive?.int == 1 && manifest["target"]?.jsonPrimitive?.content == "project") { "Unsupported package manifest" }
        require(manifest["version"]?.jsonPrimitive?.content == version) { "Package version differs from release" }
        // A script update cannot replace the APK's Python/native runtime.
        require(manifest["framework"]?.jsonPrimitive?.content == "v5.14.2" &&
            manifest["agentCore"]?.jsonPrimitive?.content == "3.13.15-maafw5.14.2") { "Update the app before installing this project" }
        val files = requireNotNull(manifest["files"] as? JsonObject) { "Missing file hashes" }
        require("interface.json" in files && "agent/main.py" in files) { "Incomplete project package" }
        return files.mapValues { (name, expected) ->
            require(name in setOf("interface.json", "icon.png", "LICENSE", "CONTACT") ||
                listOf("resource/", "agent/", "data/", "docs/", "config/maa_option.json").any(name::startsWith)) { "Invalid project path: $name" }
            val file = checked(dir, name)
            require(file.isFile && sha256(file).equals(expected.jsonPrimitive.content, true)) { "File hash mismatch: $name" }
            file
        }
    }

    /** Existing mah_res releases have no baseline/deletion manifest; only full archives are selected. */
    private fun resourceFiles(dir: File): Map<String, File> {
        val mappings = mapOf("image/" to "resource/base/image/", "index/" to "resource/index/",
            "model/" to "resource/base/model/", "pipeline/" to "resource/base/pipeline/",
            "announcement/" to "resource/announcement/")
        val result = linkedMapOf<String, File>()
        dir.walkTopDown().filter(File::isFile).forEach { file ->
            val name = file.relativeTo(dir).invariantSeparatorsPath
            val entry = mappings.entries.firstOrNull { name.startsWith(it.key) }
                ?: error("Unsupported resource path: $name")
            val dest = entry.value + name.removePrefix(entry.key)
            require(result.put(dest, file) == null) { "Duplicate resource path" }
        }
        for (index in listOf("ui.json", "characters.json", "ar.json")) {
            val file = requireNotNull(result["resource/index/$index"]) { "Missing resource index: $index" }
            val value = Json.parseToJsonElement(file.readText())
            require(value is JsonObject || value is JsonArray) { "Invalid resource index: $index" }
        }
        require(result.keys.any { it.startsWith("resource/base/image/character/") } &&
            result.keys.any { it.startsWith("resource/base/image/ar/") }) { "Incomplete resource image archive" }
        return result
    }

    private fun validate(dir: File) {
        when (val loaded = ProjectLoader(DirectoryProjectSource(dir)).load()) {
            is ProjectLoadResult.Failure -> error("Updated project cannot be loaded")
            is ProjectLoadResult.Ready -> require(loaded.diagnostics.none { it.severity == DiagnosticSeverity.Error }) { "Updated project has invalid declarations" }
        }
    }

    companion object {
        const val STATE = ".mah-install.json"
        const val MANIFEST = "mah-package.json"
        private fun mutablePath(name: String): Boolean = name.startsWith("config/") || name.startsWith("data/") || name.startsWith("debug/")

        fun checked(base: File, name: String): File {
            require(name.isNotBlank() && !name.startsWith('/') && ':' !in name && '\\' !in name &&
                name.split('/').none { it == ".." || it.isEmpty() }) { "Invalid archive path: $name" }
            val file = File(base, name).canonicalFile
            require(file.path.startsWith(base.canonicalPath + File.separator)) { "Archive path escapes destination" }
            return file
        }

        private fun unzip(archive: File, dir: File) {
            val seen = mutableSetOf<String>()
            var bytes = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removeSuffix("/")
                    val file = checked(dir, name)
                    require(seen.add(name) && seen.size <= 100_000) { "Invalid archive entries" }
                    if (!entry.isDirectory) {
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { out ->
                            val buffer = ByteArray(128 * 1024)
                            var fileBytes = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                bytes += n; fileBytes += n
                                require(bytes <= 2L * 1024 * 1024 * 1024 && fileBytes <= 256L * 1024 * 1024) { "Archive exceeds size limit" }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        private fun durableWrite(file: File, text: String) {
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }

        private fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
