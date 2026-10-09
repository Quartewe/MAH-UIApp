package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/** Runs inside PI initialization, before ProjectRepository can load the project. */
internal class GitHubResourceBootstrapper(
    private val api: GitHubReleasesApi,
    private val downloader: OkHttpUpdateDownloader,
) : ResourceBootstrapper {
    override suspend fun ensure(
        root: File,
        verifyHashes: Boolean,
        progress: (ResourcePreparationPhase, Float?) -> Unit,
    ) = withContext(MaaDispatchers.IO) {
        // This extension applies only to managed MAH projects, not arbitrary PI bundles.
        if (!File(root, ProjectPackageInstaller.STATE).isFile) return@withContext
        val metadata = Json.parseToJsonElement(File(root, "interface.json").readText()).jsonObject
        val raw = metadata["resource_github"]?.jsonPrimitive?.contentOrNull ?: return@withContext
        progress(ResourcePreparationPhase.Checking, null)
        val packages = ProjectPackageInstaller(root)
        if (packages.resourcesComplete(verifyHashes)) return@withContext
        val repository = api.parseRepository(raw.removePrefix("https://github.com/").trimEnd('/').removeSuffix(".git"))
            ?: throw ResourcePreparationException(uiTextOf(R.string.update_fail_missing_configuration))
        val releases = when (val response = api.releases(repository)) {
            is UpdateSourceOutcome.Ok -> response.value
            is UpdateSourceOutcome.Failed -> throw ResourcePreparationException(response.detail ?: response.reason.message)
        }
        val service = ResourceUpdateService(downloader)
        val plan = service.plan(releases, root, repair = true)
            ?: throw ResourcePreparationException(uiTextOf(R.string.mah_update_no_package))
        val prepared = service.prepare(plan, root, progress)
        service.installPrepared(plan, prepared, root, progress)
        check(packages.resourcesComplete()) { "Installed resources are incomplete" }
    }
}
