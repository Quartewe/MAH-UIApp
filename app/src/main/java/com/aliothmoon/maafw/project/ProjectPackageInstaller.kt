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
    val resourceSizes: Map<String, Long> = emptyMap(),
    val resourceContentId: String = "",
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

    /** Old installations gain a size inventory only after checking their existing hashes once. */
    @Synchronized
    fun resourcesComplete(verifyHashes: Boolean = false): Boolean {
        val installed = state()
        val expected = installed.owners["resource"].orEmpty()
        if (installed.resourceVersion.isBlank() || !hasRequiredResources(expected.keys)) return false
        val migrate = installed.resourceSizes.keys != expected.keys
        val sizes = linkedMapOf<String, Long>()
        for ((name, hash) in expected) {
            if (!isResourcePath(name)) return false
            val file = checked(root, name)
            if (!file.isFile) return false
            sizes[name] = file.length()
            if (!migrate && installed.resourceSizes[name] != file.length()) return false
            if ((migrate || verifyHashes) && !sha256(file).equals(hash, true)) return false
        }
        if (migrate) {
            val temporary = File(root, "$STATE.tmp")
            durableWrite(temporary, json.encodeToString(installed.copy(resourceSizes = sizes)))
            java.nio.file.Files.move(temporary.toPath(), File(root, STATE).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        return true
    }

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
        if (target == ProjectPackageTarget.Resource) {
            installResources(listOf(ResourceArchive(archive, version)))
            return
        }
        recover()
        check(root.isDirectory) { "Project is not installed" }
        val before = state()
        val unpacked = File(work, "archive").also { it.mkdirs() }
        try {
            unzip(archive, unpacked)
            val manifest = File(unpacked, MANIFEST).takeIf(File::isFile)?.let {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
            val mapped = projectFiles(unpacked, requireNotNull(manifest) { "Missing project manifest" }, version)
            val owner = "project"
            require(mapped.keys.none { it in before.owners["resource"].orEmpty() }) { "Package overlaps another update channel" }
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
                val hash = sha256(file)
                if (!mutablePath(name)) require(sha256(dest) == hash) { "Copied file hash mismatch: $name" }
                hash
            }
            val installedInterface = checked(candidate, "interface.json")
            val pi = Json.parseToJsonElement(installedInterface.readText()).jsonObject.toMutableMap()
            pi["resource_version"] = JsonPrimitive(before.resourceVersion)
            installedInterface.writeText(JsonObject(pi).toString())
            validate(candidate)
            val after = before.copy(
                projectVersion = version,
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

    /** Returns a content identity only for a complete, hash-verified baseline (including legacy installs). */
    @Synchronized
    fun verifiedResourceContentId(): String? {
        if (!resourcesComplete(verifyHashes = true)) return null
        return resourceContentId(resourceInventory(state()))
    }

    private fun resourceInventory(installed: InstalledProjectPackages): Map<String, ResourceFile> =
        installed.owners["resource"].orEmpty().mapKeys { resourceSource(it.key) }.mapValues { (path, hash) ->
            ResourceFile(installed.resourceSizes[resourceDestination(path)] ?: -1, hash)
        }

    /** A whole delta chain is staged and committed once; no intermediate version becomes active. */
    @Synchronized
    fun installResources(archives: List<ResourceArchive>) {
        require(archives.isNotEmpty() && archives.size <= 32) { "Invalid resource update chain" }
        recover()
        check(root.isDirectory) { "Project is not installed" }
        var installed = state()
        try {
            work.mkdirs()
            check(root.copyRecursively(candidate, overwrite = true)) { "Cannot stage current project" }
            for ((index, archive) in archives.withIndex()) {
                archive.expected?.let {
                    require(archive.file.length() == it.size && sha256(archive.file) == it.sha256) { "Resource archive digest mismatch" }
                }
                val unpacked = File(work, "archive-$index").also { it.mkdirs() }
                unzip(archive.file, unpacked)
                val manifestFile = File(unpacked, RESOURCE_MANIFEST)
                val manifest = if (manifestFile.isFile) readResourceJson<ResourceManifest>(manifestFile).also { it.validate() } else null
                require(archive.expected == null || manifest == archive.expected.manifest) { "Package manifest differs from release descriptor" }
                require(manifest == null || manifest.version == archive.version) { "Resource version differs from release" }
                val payload = unpacked.walkTopDown().filter(File::isFile)
                    .filter { it != manifestFile }.associateBy { it.relativeTo(unpacked).invariantSeparatorsPath }
                val targetFiles = manifest?.files ?: resourceFiles(unpacked).mapKeys { resourceSource(it.key) }
                    .mapValues { ResourceFile(it.value.length(), sha256(it.value)) }
                val changed = manifest?.changed?.toSet() ?: targetFiles.keys
                require(payload.keys == changed) { "Resource archive contains missing or unlisted files" }
                val mappedTarget = targetFiles.mapKeys { resourceDestination(it.key) }
                val oldFiles = installed.owners["resource"].orEmpty()
                require(mappedTarget.keys.none { it in installed.owners["project"].orEmpty() }) { "Package overlaps project-owned files" }
                val removed = oldFiles.keys - mappedTarget.keys
                if (manifest?.kind == "hotfix") {
                    require(installed.resourceVersion == manifest.baseVersion) { "Hotfix baseline version mismatch" }
                    // Old APKs lack sizes. Derive them only while verifying every baseline hash.
                    val baseline = oldFiles.mapKeys { resourceSource(it.key) }.mapValues { (path, hash) ->
                        val file = checked(candidate, resourceDestination(path))
                        require(file.isFile && sha256(file) == hash) { "Hotfix baseline is damaged: $path" }
                        val recorded = installed.resourceSizes[resourceDestination(path)]
                        require(recorded == null || recorded == file.length()) { "Hotfix baseline size mismatch: $path" }
                        ResourceFile(file.length(), hash)
                    }
                    require(resourceContentId(baseline) == manifest.baseContentId) { "Hotfix baseline content mismatch" }
                    require(manifest.deleted.map(::resourceDestination).toSet() == removed) { "Hotfix deletion list mismatch" }
                    require(changed == targetFiles.filter { (path, entry) -> baseline[path] != entry }.keys) { "Hotfix change list mismatch" }
                }
                removed.forEach { path ->
                    require(isResourcePath(path) && path !in installed.owners["project"].orEmpty()) { "Invalid resource deletion" }
                    checked(candidate, path).takeIf(File::isFile)?.let { check(it.delete()) }
                }
                changed.forEach { path ->
                    val source = payload.getValue(path)
                    val entry = targetFiles.getValue(path)
                    require(source.length() == entry.size && sha256(source) == entry.sha256) { "Resource payload mismatch: $path" }
                    val dest = checked(candidate, resourceDestination(path))
                    dest.parentFile!!.mkdirs()
                    source.copyTo(dest, overwrite = true)
                }
                // Verify all target files, including unchanged files, before changing versions.
                mappedTarget.forEach { (path, entry) ->
                    val file = checked(candidate, path)
                    require(file.isFile && file.length() == entry.size && sha256(file) == entry.sha256) { "Incomplete target resources: $path" }
                }
                require(hasRequiredResources(mappedTarget.keys)) { "Incomplete resource image archive" }
                for (name in listOf("ui.json", "characters.json", "ar.json")) {
                    val value = Json.parseToJsonElement(checked(candidate, "resource/index/$name").readText())
                    require(value is JsonObject || value is JsonArray) { "Invalid resource index: $name" }
                }
                installed = installed.copy(resourceVersion = archive.version,
                    resourceContentId = resourceContentId(targetFiles),
                    owners = installed.owners + ("resource" to mappedTarget.mapValues { it.value.sha256 }),
                    resourceSizes = mappedTarget.mapValues { it.value.size })
            }
            val piFile = checked(candidate, "interface.json")
            val pi = Json.parseToJsonElement(piFile.readText()).jsonObject.toMutableMap()
            pi["resource_version"] = JsonPrimitive(installed.resourceVersion)
            piFile.writeText(JsonObject(pi).toString())
            validate(candidate)
            installed = installed.copy(revision = UUID.randomUUID().toString())
            durableWrite(File(candidate, STATE), json.encodeToString(installed))
            check(root.renameTo(backup)) { "Cannot back up installed project" }
            check(candidate.renameTo(root)) { "Cannot activate staged project" }
            durableWrite(committed, installed.revision)
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

    /** Compatibility path for historical full archives without a resource manifest. */
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
        internal fun isResourcePath(name: String): Boolean = listOf(
            "resource/base/image/", "resource/index/", "resource/base/model/",
            "resource/base/pipeline/", "resource/announcement/",
        ).any(name::startsWith)
        private fun hasRequiredResources(names: Set<String>): Boolean =
            listOf("ui.json", "characters.json", "ar.json").all { "resource/index/$it" in names } &&
                names.any { it.startsWith("resource/base/image/character/") } &&
                names.any { it.startsWith("resource/base/image/ar/") }
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
