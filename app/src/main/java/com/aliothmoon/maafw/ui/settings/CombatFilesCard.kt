package com.aliothmoon.maafw.ui.settings

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.project.CombatFileStore
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaNavigationRow
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import timber.log.Timber

/** SAF launcher 随设置页注册，管理面板关闭后仍能处理文件选择结果。 */
@Composable
internal fun CombatFilesCard(state: SessionUiState) {
    val installer: PiInstaller = koinInject()
    val runner: RunnerPort = koinInject()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var visible by rememberSaveable { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<CombatFileStore.Entry>>(emptyList()) }
    var listError by remember { mutableStateOf(false) }
    val importedFormat = stringResource(R.string.combat_files_imported)
    val exported = stringResource(R.string.combat_files_exported)
    val invalid = stringResource(R.string.combat_files_invalid)
    val failed = stringResource(R.string.combat_files_failed)
    val locked = stringResource(R.string.msg_locked_while_running)
    val copied = stringResource(R.string.combat_files_copied)
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun store() = CombatFileStore(installer.installedDir())

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        busy = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val entry = withContext(Dispatchers.IO) {
                    if (runner.state.value.phase.isBusy) return@withContext null
                    val name = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    requireNotNull(name)
                    requireNotNull(context.contentResolver.openInputStream(uri)).use {
                        store().importFile(name, it)
                    }
                }
                if (entry == null) message(locked) else {
                    refresh++
                    message(importedFormat.format(entry.name))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                message(invalid)
            } catch (e: Exception) {
                Timber.w(e, "Combat file import failed")
                message(failed)
            } finally {
                busy = false
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val path = exportPath
        exportPath = null
        busy = false
        if (uri == null || path == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = store().exportFile(path)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
                message(exported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Combat file export failed")
                message(failed)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(visible, refresh, state.projectState) {
        if (!visible) return@LaunchedEffect
        listError = false
        try {
            entries = withContext(Dispatchers.IO) { store().list() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Combat file listing failed")
            entries = emptyList()
            listError = true
        }
    }

    MaaCard(title = stringResource(R.string.combat_files_title)) {
        MaaNavigationRow(
            label = stringResource(R.string.combat_files_manage),
            onClick = { visible = true },
        )
    }
    if (visible) {
        MaaModalSheet(onDismiss = { visible = false }) { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
                MaaSheetHeader(title = stringResource(R.string.combat_files_title), onClose = { visible = false })
                Text(
                    stringResource(R.string.combat_files_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MaaOutlinedButton(
                    enabled = !busy && !state.configurationLocked && state.projectState is ProjectState.Ready,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        try {
                            importer.launch(arrayOf("*/*"))
                        } catch (e: Exception) {
                            busy = false
                            message(failed)
                        }
                    },
                ) { Text(stringResource(R.string.combat_files_import)) }
                if (listError) {
                    Text(failed, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.mah_show_refresh)) }
                } else if (entries.isEmpty()) {
                    Text(stringResource(R.string.combat_files_empty))
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    items(entries, key = { it.relativePath }) { entry ->
                        MaaCard {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            Text(entry.relativePath, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(entry.name))
                                    message(copied)
                                }) { Text(stringResource(R.string.combat_files_copy_name)) }
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        exportPath = entry.relativePath
                                        try {
                                            exporter.launch(entry.name)
                                        } catch (e: Exception) {
                                            busy = false
                                            exportPath = null
                                            message(failed)
                                        }
                                    },
                                ) { Text(stringResource(R.string.combat_files_export)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
