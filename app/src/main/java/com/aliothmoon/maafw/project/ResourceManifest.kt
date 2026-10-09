package com.aliothmoon.maafw.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class ResourceFile(val size: Long, val sha256: String)

@Serializable
data class ResourceManifest(
    val format: Int,
    val version: String,
    val contentId: String,
    val files: Map<String, ResourceFile>,
    val kind: String,
    val baseVersion: String = "",
    val baseContentId: String = "",
    val changed: List<String>,
    val deleted: List<String> = emptyList(),
) {
    fun validate() {
        require(format == 1 && version.isNotBlank() && files.isNotEmpty() && files.size <= 100_000) { "Invalid resource manifest" }
        files.forEach { (path, entry) ->
            resourceDestination(path)
            require(entry.size in 0..268435456L && SHA256.matches(entry.sha256)) { "Invalid resource file: $path" }
        }
        require(files.values.sumOf { it.size } <= 2L * 1024 * 1024 * 1024) { "Resources exceed size limit" }
        require(contentId == resourceContentId(files)) { "Resource content ID mismatch" }
        require(changed.toSet().size == changed.size && deleted.toSet().size == deleted.size) { "Duplicate resource operations" }
        require(changed.all { it in files } && deleted.none { it in files }) { "Invalid resource operations" }
        deleted.forEach(::resourceDestination)
        when (kind) {
            "full" -> require(baseVersion.isEmpty() && baseContentId.isEmpty() && deleted.isEmpty() && changed.toSet() == files.keys) { "Invalid full manifest" }
            "hotfix" -> require(baseVersion.isNotBlank() && SHA256.matches(baseContentId)) { "Missing hotfix baseline" }
            else -> error("Unsupported resource package kind")
        }
    }
}

@Serializable
data class ResourcePackageDescription(val name: String, val size: Long, val sha256: String, val manifest: ResourceManifest)

@Serializable
data class ResourceReleaseDescription(val format: Int, val version: String, val contentId: String, val packages: List<ResourcePackageDescription>) {
    fun validate() {
        require(format == 1 && packages.size in 1..2 && packages.map { it.name }.toSet().size == packages.size) { "Invalid resource release" }
        require(packages.count { it.manifest.kind == "full" } == 1 && packages.count { it.manifest.kind == "hotfix" } <= 1) { "Ambiguous resource packages" }
        packages.forEach {
            it.manifest.validate()
            require(it.manifest.version == version && it.manifest.contentId == contentId && it.manifest.files == packages.first().manifest.files) { "Inconsistent resource release" }
            require(it.name.startsWith("mah_res-${it.manifest.kind}-") && it.name.endsWith(".zip") && '/' !in it.name && '\\' !in it.name) { "Invalid asset name" }
            require(it.size in 1..2147483648L && SHA256.matches(it.sha256)) { "Invalid resource archive metadata" }
        }
    }
}

data class ResourceArchive(val file: File, val version: String, val expected: ResourcePackageDescription? = null)

internal const val RESOURCE_MANIFEST = "resource-manifest.json"
internal const val RESOURCE_RELEASE = "mah_res-manifest.json"
internal const val MAX_RESOURCE_JSON = 8L * 1024 * 1024
internal val SHA256 = Regex("[0-9a-f]{64}")
internal val RESOURCE_ROOTS = linkedMapOf("image/" to "resource/base/image/", "index/" to "resource/index/",
    "model/" to "resource/base/model/", "pipeline/" to "resource/base/pipeline/", "announcement/" to "resource/announcement/")

internal fun resourceDestination(path: String): String {
    require(path.none { it.code < 32 || it == '\\' || it == ':' } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid resource path: $path" }
    val entry = RESOURCE_ROOTS.entries.firstOrNull { path.startsWith(it.key) } ?: error("Unsupported resource path: $path")
    return entry.value + path.removePrefix(entry.key)
}

internal fun resourceSource(path: String): String {
    val entry = RESOURCE_ROOTS.entries.firstOrNull { path.startsWith(it.value) } ?: error("Unsupported installed resource: $path")
    return (entry.key + path.removePrefix(entry.value)).also(::resourceDestination)
}

/** Must match tools/build_release.py: UTF-16 path order, UTF-8 tab-separated rows. */
internal fun resourceContentId(files: Map<String, ResourceFile>): String = digestBytes(
    files.toSortedMap().entries.joinToString("") { (path, file) -> "$path\t${file.size}\t${file.sha256}\n" }.toByteArray(Charsets.UTF_8),
)

internal fun digestBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal inline fun <reified T> readResourceJson(file: File): T {
    require(file.length() <= MAX_RESOURCE_JSON) { "Resource manifest is too large" }
    return Json.decodeFromString<T>(file.readText())
}
