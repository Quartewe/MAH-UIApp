package com.aliothmoon.maafw.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.project.ProjectPackageTarget
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.update.ProjectUpdatesViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ProjectUpdatesSection(locked: Boolean, model: ProjectUpdatesViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.enabled) return
    MaaInfoRow(stringResource(R.string.mah_update_project), state.versions.projectVersion)
    MaaInfoRow(stringResource(R.string.mah_update_resource), state.versions.resourceVersion)
    Text(stringResource(R.string.mah_update_source))
    for ((target, label) in listOf(
        ProjectPackageTarget.Project to R.string.mah_update_check_project,
        ProjectPackageTarget.Resource to R.string.mah_update_check_resource,
    )) {
        MaaOutlinedButton(
            enabled = !locked && !state.busy,
            onClick = { model.check(target) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
            Spacer(Modifier.width(MaaDesignTokens.Spacing.sm))
            Text(stringResource(label))
        }
        val entry = state.entries[target]
        entry?.candidate?.let { candidate ->
            TextButton(enabled = !locked && !state.busy, onClick = { model.install(target) }) {
                Text(stringResource(R.string.mah_update_install, candidate.update.version))
            }
        }
        if (state.activeTarget == target) {
            state.progress?.let { LinearProgressIndicator(progress = { it }) } ?: LinearProgressIndicator()
        }
        entry?.message?.let { Text(it.asString()) }
    }
}
