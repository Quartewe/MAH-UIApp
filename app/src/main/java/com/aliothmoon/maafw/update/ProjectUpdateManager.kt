package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.*
import com.aliothmoon.maafw.runner.RunLauncher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProjectUpdateCandidate(val target: ProjectPackageTarget, val update: ResolvedUpdate, val resources: ResourceUpdatePlan? = null)

internal data class ProjectUpdateEntry(
    val candidate: ProjectUpdateCandidate? = null,
    val message: UiText? = null,
)

internal data class ProjectUpdatesState(
    val versions: InstalledProjectPackages = InstalledProjectPackages(),
    val enabled: Boolean = false,
    val activeTarget: ProjectPackageTarget? = null,
    val entries: Map<ProjectPackageTarget, ProjectUpdateEntry> = emptyMap(),
    val progress: Float? = null,
) {
    val busy: Boolean get() = activeTarget != null
}

/** Shared by startup checks and the home screen, including results found before Home is opened. */
class ProjectUpdateManager internal constructor(
    private val repository: ProjectRepository,
    private val installer: PiInstaller,
    private val api: GitHubReleasesApi,
    private val downloader: OkHttpUpdateDownloader,
    private val launcher: RunLauncher,
) {
    private val mutable = MutableStateFlow(ProjectUpdatesState())
    internal val state = mutable.asStateFlow()
    private val gate = Mutex()

    internal suspend fun refresh() {
        val metadata = (repository.state.value as? ProjectState.Ready)?.definition?.metadata ?: return
        val versions = withContext(Dispatchers.IO) {
            ProjectPackageInstaller(installer.installedDir()).state()
        }
        mutable.update { it.copy(versions = versions, enabled = metadata.resourceRepository != null) }
    }

    internal fun clear(target: ProjectPackageTarget) {
        mutable.update { it.copy(entries = it.entries - target) }
    }

    suspend fun check(target: ProjectPackageTarget, channel: UpdateChannel = UpdateChannel.STABLE): Boolean = gate.withLock {
        mutable.update { it.copy(activeTarget = target, progress = null, entries = it.entries - target) }
        try {
            refresh()
            val metadata = (repository.state.value as? ProjectState.Ready)?.definition?.metadata
            val repo = if (target == ProjectPackageTarget.Resource) metadata?.resourceRepository else metadata?.projectRepository
            // Generic PI projects may not configure these independent update channels.
            if (repo.isNullOrBlank()) return@withLock false
            requireNotNull(api.parseRepository(repo)) { "Invalid update repository" }
            val releaseResult = if (target == ProjectPackageTarget.Resource) api.releases(repo) else {
                when (val latest = api.latestRelease(repo, channel)) {
                    is UpdateSourceOutcome.Ok -> UpdateSourceOutcome.Ok(listOf(latest.value))
                    is UpdateSourceOutcome.Failed -> latest
                }
            }
            val releases = when (val result = releaseResult) {
                is UpdateSourceOutcome.Ok -> result.value
                is UpdateSourceOutcome.Failed -> {
                    setResult(target, ProjectUpdateEntry(message = result.detail ?: result.reason.message))
                    return@withLock false
                }
            }
            if (target == ProjectPackageTarget.Resource) {
                val plan = ResourceUpdateService(downloader).plan(releases, installer.installedDir())
                setResult(target, when {
                    plan == null -> ProjectUpdateEntry(message = uiTextOf(R.string.mah_update_no_package))
                    !plan.available -> ProjectUpdateEntry(message = uiTextOf(R.string.mah_update_current))
                    else -> ProjectUpdateEntry(candidate = ProjectUpdateCandidate(target, plan.full.update,
                        plan.takeIf { it.full.description != null }))
                })
                return@withLock plan?.available == true
            }
            val current = mutable.value.versions.projectVersion
            val selection = releases.firstOrNull()?.let { release ->
                selectProjectAsset(release.assets, target)?.let { release to it }
            }
            if (selection == null) {
                setResult(target, ProjectUpdateEntry(message = uiTextOf(R.string.mah_update_no_package)))
                return@withLock false
            }
            val (release, asset) = selection
            setResult(target, if (release.tag == current) {
                ProjectUpdateEntry(message = uiTextOf(R.string.mah_update_current))
            } else {
                ProjectUpdateEntry(candidate = ProjectUpdateCandidate(target, ResolvedUpdate(
                    source = UpdateSource.GITHUB, version = release.tag,
                    downloadUrl = asset.downloadUrl, sha256 = asset.sha256, fileExtension = "zip",
                )))
            })
            release.tag != current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setResult(target, ProjectUpdateEntry(message = if (e is ResourcePreparationException) e.reason
                else uiTextOf(R.string.mah_update_failed, e.message.orEmpty())))
            false
        } finally {
            mutable.update { it.copy(activeTarget = null) }
        }
    }

    internal suspend fun install(target: ProjectPackageTarget) = gate.withLock {
        val candidate = mutable.value.entries[target]?.candidate ?: return@withLock
        mutable.update { it.copy(activeTarget = target, progress = null) }
        setResult(target, ProjectUpdateEntry(candidate = candidate))
        try {
            if (candidate.resources != null) {
                val resources = ResourceUpdateService(downloader)
                val root = installer.installedDir()
                val progress: (ResourcePreparationPhase, Float?) -> Unit = { _, value ->
                    mutable.update { it.copy(progress = value) }
                }
                val prepared = resources.prepare(candidate.resources, root, progress)
                launcher.changeProjectWhenIdle {
                    withContext(NonCancellable) {
                        resources.installPrepared(candidate.resources, prepared, root, progress)
                        repository.reload()
                        refresh()
                    }
                }
                setResult(target, ProjectUpdateEntry(message = uiTextOf(R.string.mah_update_done)))
                return@withLock
            }
            val download = downloader.download(candidate.update) { done, total ->
                mutable.update { it.copy(progress = if (total > 0) done.toFloat() / total else null) }
            }
            when (download) {
                is UpdateDownloadResult.Failed -> setResult(target, ProjectUpdateEntry(
                    candidate = candidate, message = download.detail ?: download.reason.message,
                ))
                is UpdateDownloadResult.Downloaded -> {
                    launcher.changeProjectWhenIdle {
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) {
                                installer.installUpdate(download.update.file, target, candidate.update.version)
                            }
                            repository.reload()
                            refresh()
                        }
                    }
                    setResult(target, ProjectUpdateEntry(message = uiTextOf(R.string.mah_update_done)))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setResult(target, ProjectUpdateEntry(
                candidate = candidate, message = if (e is ResourcePreparationException) e.reason
                    else uiTextOf(R.string.mah_update_failed, e.message.orEmpty()),
            ))
        } finally {
            mutable.update { it.copy(activeTarget = null, progress = null) }
        }
    }

    private fun setResult(target: ProjectPackageTarget, entry: ProjectUpdateEntry) {
        mutable.update { it.copy(entries = it.entries + (target to entry)) }
    }
}
