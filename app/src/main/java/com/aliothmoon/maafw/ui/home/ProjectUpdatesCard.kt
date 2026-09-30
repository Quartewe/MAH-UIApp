package com.aliothmoon.maafw.ui.home

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.project.ProjectPackageTarget
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.update.ProjectUpdatesViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ProjectUpdatesCard(locked: Boolean, model: ProjectUpdatesViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.enabled) return
    MaaCard(title = stringResource(R.string.mah_update_title)) {
        MaaInfoRow(stringResource(R.string.mah_update_project), state.versions.projectVersion)
        MaaInfoRow(stringResource(R.string.mah_update_resource), state.versions.resourceVersion)
        Text(stringResource(R.string.mah_update_source))
        TextButton(enabled = !locked && !state.busy, onClick = { model.check(ProjectPackageTarget.Project) }) {
            Text(stringResource(R.string.mah_update_check_project))
        }
        TextButton(enabled = !locked && !state.busy, onClick = { model.check(ProjectPackageTarget.Resource) }) {
            Text(stringResource(R.string.mah_update_check_resource))
        }
        state.candidate?.let { candidate ->
            TextButton(enabled = !locked && !state.busy, onClick = model::install) {
                Text(stringResource(R.string.mah_update_install, candidate.update.version))
            }
        }
        if (state.busy) {
            state.progress?.let { LinearProgressIndicator(progress = { it }) } ?: LinearProgressIndicator()
        }
        state.message?.let { Text(it.asString()) }
    }
}
