package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.project.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal data class ResourceDownload(val update: ResolvedUpdate, val description: ResourcePackageDescription? = null)
internal data class ResourceUpdatePlan(val full: ResourceDownload, val deltas: List<ResourceDownload> = emptyList(), val available: Boolean = true)

/** Shares the resource protocol between first-use preparation and manual/startup updates. */
internal class ResourceUpdateService(private val downloader: OkHttpUpdateDownloader) {
    suspend fun plan(
        releases: List<GitHubReleasesApi.Release>, root: File, repair: Boolean = false,
    ): ResourceUpdatePlan? = withContext(MaaDispatchers.IO) {
        val installed = ProjectPackageInstaller(root)
        val state = installed.state()
        val eligible = releases.filterNot { it.prerelease }
            .filter { selectProjectAsset(it.assets, ProjectPackageTarget.Resource) != null }
        val target = (if (repair) eligible.firstOrNull { it.tag == state.resourceVersion } else null)
            ?: eligible.firstOrNull() ?: return@withContext null
        val cache = mutableMapOf<String, ResourceReleaseDescription?>()
        suspend fun description(release: GitHubReleasesApi.Release): ResourceReleaseDescription? {
            if (cache.containsKey(release.tag)) return cache[release.tag]
            val assets = release.assets.filter { it.name == RESOURCE_RELEASE }
            require(assets.size <= 1) { "Ambiguous resource release descriptor" }
            val asset = assets.singleOrNull() ?: return null.also { cache[release.tag] = null }
            val file = download(ResolvedUpdate(UpdateSource.GITHUB, release.tag, asset.downloadUrl, asset.sha256, "json")) { _, _ -> }
            return readResourceJson<ResourceReleaseDescription>(file).also {
                it.validate()
                require(it.version == release.tag) { "Resource descriptor version mismatch" }
                cache[release.tag] = it
            }
        }
        fun asset(release: GitHubReleasesApi.Release, desc: ResourcePackageDescription): ResourceDownload {
            val found = release.assets.singleOrNull { it.name == desc.name } ?: error("Resource asset is missing: ${desc.name}")
            require(found.sha256 == null || found.sha256.removePrefix("sha256:") == desc.sha256) { "Resource asset digest differs from descriptor" }
            return ResourceDownload(ResolvedUpdate(UpdateSource.GITHUB, release.tag, found.downloadUrl, desc.sha256, "zip"), desc)
        }
        val targetDescription = description(target)
        if (targetDescription == null) {
            val full = selectProjectAsset(target.assets, ProjectPackageTarget.Resource)!!
            return@withContext ResourceUpdatePlan(ResourceDownload(ResolvedUpdate(UpdateSource.GITHUB, target.tag, full.downloadUrl, full.sha256, "zip")),
                available = repair || target.tag != state.resourceVersion)
        }
        val full = asset(target, targetDescription.packages.single { it.manifest.kind == "full" })
        if (repair) return@withContext ResourceUpdatePlan(full)
        val currentId = installed.verifiedResourceContentId()
        if (state.resourceVersion == target.tag && currentId == targetDescription.contentId) {
            return@withContext ResourceUpdatePlan(full, available = false)
        }
        if (currentId == null) return@withContext ResourceUpdatePlan(full)
        val reversed = mutableListOf<ResourceDownload>()
        val seen = mutableSetOf<Pair<String, String>>()
        var release = target
        var desc: ResourceReleaseDescription = targetDescription
        while (reversed.size < 32 && seen.add(desc.version to desc.contentId)) {
            val delta = desc.packages.singleOrNull { it.manifest.kind == "hotfix" } ?: break
            if (release.assets.count { it.name == delta.name } != 1) break
            reversed += asset(release, delta)
            val manifest = delta.manifest
            if (manifest.baseVersion == state.resourceVersion && manifest.baseContentId == currentId) {
                // Prefer the complete package if replaying many deltas would download more bytes.
                return@withContext ResourceUpdatePlan(full, if (reversed.sumOf { it.description!!.size } < full.description!!.size) reversed.reversed() else emptyList())
            }
            release = releases.singleOrNull { it.tag == manifest.baseVersion } ?: break
            desc = try {
                description(release) ?: break
            } catch (_: ResourcePreparationException) {
                // An unavailable intermediate release cannot establish a chain; the latest
                // full package remains independently verifiable from its own descriptor.
                break
            }
            if (desc.contentId != manifest.baseContentId) break
        }
        ResourceUpdatePlan(full)
    }

    private fun baselineMatches(root: File, first: ResourceDownload): Boolean {
        val manifest = first.description?.manifest ?: return false
        val installer = ProjectPackageInstaller(root)
        return installer.state().resourceVersion == manifest.baseVersion && installer.verifiedResourceContentId() == manifest.baseContentId
    }

    suspend fun prepare(plan: ResourceUpdatePlan, root: File, progress: (ResourcePreparationPhase, Float?) -> Unit): List<ResourceArchive> =
        withContext(MaaDispatchers.IO) {
            val steps = if (plan.deltas.isNotEmpty() && baselineMatches(root, plan.deltas.first())) plan.deltas else listOf(plan.full)
            prepareSteps(steps, progress)
        }

    private suspend fun prepareSteps(steps: List<ResourceDownload>, progress: (ResourcePreparationPhase, Float?) -> Unit): List<ResourceArchive> {
        val total = steps.sumOf { it.description?.size ?: 0L }
        var completed = 0L
        return steps.map { step ->
            progress(ResourcePreparationPhase.Downloading, null)
            val file = download(step.update) { done, length ->
                val denominator = if (total > 0) total else length
                progress(ResourcePreparationPhase.Downloading, if (denominator > 0) ((completed + done).toFloat() / denominator).coerceIn(0f, 1f) else null)
            }
            step.description?.let { require(file.length() == it.size) { "Resource archive size mismatch" } }
            completed += file.length()
            ResourceArchive(file, step.update.version, step.description)
        }
    }

    /** Caller holds the task/update gate; repeat baseline verification after the downloads. */
    suspend fun installPrepared(plan: ResourceUpdatePlan, prepared: List<ResourceArchive>, root: File, progress: (ResourcePreparationPhase, Float?) -> Unit) =
        withContext(MaaDispatchers.IO) {
            val first = prepared.first()
            val archives = if (first.expected?.manifest?.kind == "hotfix" &&
                !baselineMatches(root, ResourceDownload(plan.full.update, first.expected))) {
                prepareSteps(listOf(plan.full), progress)
            } else prepared
            progress(ResourcePreparationPhase.Installing, null)
            withContext(NonCancellable) { ProjectPackageInstaller(root).installResources(archives) }
        }

    private suspend fun download(update: ResolvedUpdate, progress: (Long, Long) -> Unit): File =
        when (val result = downloader.download(update, progress)) {
            is UpdateDownloadResult.Downloaded -> result.update.file
            is UpdateDownloadResult.Failed -> throw ResourcePreparationException(result.detail ?: result.reason.message)
        }
}
