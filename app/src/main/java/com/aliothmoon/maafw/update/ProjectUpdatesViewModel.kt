package com.aliothmoon.maafw.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.*
import com.aliothmoon.maafw.runner.RunLauncher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ProjectUpdateCandidate(val target: ProjectPackageTarget, val update: ResolvedUpdate)

internal data class ProjectUpdatesState(
    val versions: InstalledProjectPackages = InstalledProjectPackages(),
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val message: UiText? = null,
    val candidate: ProjectUpdateCandidate? = null,
    val progress: Float? = null,
)

/** Independent project/data updates use the PI's explicit GitHub repositories. */
internal class ProjectUpdatesViewModel(
    private val repository: ProjectRepository,
    private val installer: PiInstaller,
    private val api: GitHubReleasesApi,
    private val downloader: OkHttpUpdateDownloader,
    private val launcher: RunLauncher,
) : ViewModel() {
    private val mutable = MutableStateFlow(ProjectUpdatesState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.filterIsInstance<ProjectState.Ready>().collect { ready ->
                val versions = withContext(Dispatchers.IO) {
                    runCatching { ProjectPackageInstaller(installer.installedDir()).state() }.getOrDefault(InstalledProjectPackages())
                }
                mutable.update { it.copy(versions = versions, enabled = ready.definition.metadata.resourceRepository != null) }
            }
        }
    }

    fun check(target: ProjectPackageTarget) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, candidate = null, message = null, progress = null) }
        viewModelScope.launch {
            try {
                val metadata = (repository.state.value as? ProjectState.Ready)?.definition?.metadata
                val repo = if (target == ProjectPackageTarget.Resource) metadata?.resourceRepository else metadata?.projectRepository
                requireNotNull(api.parseRepository(repo)) { "Missing update repository" }
                val releases = when (val result = api.releases(repo!!)) {
                    is UpdateSourceOutcome.Ok -> result.value
                    is UpdateSourceOutcome.Failed -> {
                        mutable.update { it.copy(message = result.detail ?: result.reason.message) }
                        return@launch
                    }
                }
                // GitHub returns releases newest first. Resource tags are activity names, not semver.
                val selection = releases.asSequence().filterNot { it.prerelease }.mapNotNull { release ->
                    selectProjectAsset(release.assets, target)?.let { release to it }
                }.firstOrNull()
                if (selection == null) {
                    mutable.update { it.copy(message = uiTextOf(R.string.mah_update_no_package)) }
                    return@launch
                }
                val (release, asset) = selection
                val current = if (target == ProjectPackageTarget.Resource) mutable.value.versions.resourceVersion else mutable.value.versions.projectVersion
                if (release.tag == current) {
                    mutable.update { it.copy(message = uiTextOf(R.string.mah_update_current)) }
                } else {
                    mutable.update { it.copy(candidate = ProjectUpdateCandidate(target, ResolvedUpdate(
                        source = UpdateSource.GITHUB, version = release.tag,
                        downloadUrl = asset.downloadUrl, sha256 = asset.sha256, fileExtension = "zip",
                    ))) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = uiTextOf(R.string.mah_update_failed, e.message.orEmpty())) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun install() {
        val candidate = mutable.value.candidate ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val download = downloader.download(candidate.update) { done, total ->
                    mutable.update { it.copy(progress = if (total > 0) done.toFloat() / total else null) }
                }
                when (download) {
                    is UpdateDownloadResult.Failed -> mutable.update { it.copy(message = download.detail ?: download.reason.message) }
                    is UpdateDownloadResult.Downloaded -> {
                        launcher.changeProjectWhenIdle {
                            // Once swapping starts, cancellation must not skip recovery or the reload.
                            withContext(NonCancellable) {
                                withContext(Dispatchers.IO) {
                                    installer.installUpdate(download.update.file, candidate.target, candidate.update.version)
                                }
                                repository.reload()
                            }
                        }
                        mutable.update { it.copy(candidate = null, message = uiTextOf(R.string.mah_update_done)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = uiTextOf(R.string.mah_update_failed, e.message.orEmpty())) }
            } finally {
                mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }
}

internal fun selectProjectAsset(assets: List<GitHubReleasesApi.Asset>, target: ProjectPackageTarget): GitHubReleasesApi.Asset? =
    assets.filter { asset ->
        val name = asset.name.lowercase()
        name.endsWith(".zip") && when (target) {
            ProjectPackageTarget.Project -> name.startsWith("mah-project-android-")
            ProjectPackageTarget.Resource -> name.startsWith("mah_res-full-")
        }
    }.singleOrNull()
