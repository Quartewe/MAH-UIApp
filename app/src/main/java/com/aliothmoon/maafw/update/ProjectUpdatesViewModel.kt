package com.aliothmoon.maafw.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.project.ProjectPackageTarget
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import timber.log.Timber

internal class ProjectUpdatesViewModel(
    private val updates: ProjectUpdateManager,
    repository: ProjectRepository,
) : ViewModel() {
    val state = updates.state

    init {
        viewModelScope.launch {
            repository.state.filterIsInstance<ProjectState.Ready>().collect {
                try {
                    updates.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Cannot read installed project versions")
                }
            }
        }
    }

    fun check(target: ProjectPackageTarget) {
        if (state.value.busy) return
        viewModelScope.launch { updates.check(target) }
    }

    fun install(target: ProjectPackageTarget) {
        if (state.value.busy) return
        viewModelScope.launch { updates.install(target) }
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
