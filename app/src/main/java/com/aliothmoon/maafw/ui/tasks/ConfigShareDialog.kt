package com.aliothmoon.maafw.ui.tasks

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.config.share.ConfigShare
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** The preview and commit use the same definition; a resource reload invalidates the preview. */
@Composable
internal fun ConfigShareDialog(state: SessionUiState, onDismiss: () -> Unit) {
    val store: UserConfigurationStore = koinInject()
    val projects: ProjectRepository = koinInject()
    val runner: RunnerPort = koinInject()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val definition = (state.projectState as? ProjectState.Ready)?.definition
    var text by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf<String?>(null) }
    var prepared by remember(definition) { mutableStateOf<ConfigShare.Import?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    val lockedText = stringResource(R.string.msg_locked_while_running)
    val savedText = stringResource(R.string.config_share_saved)
    val staleText = stringResource(R.string.config_share_stale)
    val canWrite = !busy && !state.configurationLocked && definition != null
    fun failure(error: Exception) {
        if (error is CancellationException) throw error
        status = error.message ?: "Configuration operation failed"
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; prepared = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    val raw = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= ConfigShare.MAX_BYTES) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    require(raw.size <= ConfigShare.MAX_BYTES) { "Configuration exceeds 4 MiB" }
                    name to raw.decodeToString(throwOnInvalidSequence = true)
                }
                fileName = loaded.first; text = loaded.second; status = ""
            } catch (e: Exception) { failure(e) } finally { busy = false }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = exported
        exported = null
        if (uri != null && snapshot != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(snapshot.encodeToByteArray()) } }
                status = savedText
            } catch (e: Exception) { failure(e) } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.config_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                Text(stringResource(R.string.config_share_hint), style = MaterialTheme.typography.bodySmall)
                Text(state.activeConfiguration?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = text, onValueChange = { text = it; fileName = null; prepared = null; status = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(max = MaaDesignTokens.Spacing.lg * 8), maxLines = 6,
                    label = { Text(stringResource(R.string.config_share_code)) }, enabled = !busy)
                Row {
                    TextButton(enabled = canWrite, onClick = {
                        scope.launch {
                            busy = true; prepared = null
                            try {
                                val current = store.data.first()
                                val config = requireNotNull(current.configuration(current.activeConfigurationId)) { "No active configuration" }
                                text = withContext(Dispatchers.Default) { ConfigShare.encode(config, requireNotNull(definition), current.activeResourceName) }
                                fileName = null; status = ""
                            } catch (e: Exception) { failure(e) } finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.config_share_generate)) }
                    TextButton(enabled = !busy && text.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.common_copy)) }
                    TextButton(enabled = !busy && text.isNotBlank(), onClick = { exported = text; saver.launch("configuration.txt") }) { Text(stringResource(R.string.common_save)) }
                }
                Row {
                    TextButton(enabled = canWrite, onClick = { opener.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.config_share_open)) }
                    TextButton(enabled = canWrite && text.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true; prepared = null
                            try {
                                prepared = withContext(Dispatchers.Default) { ConfigShare.decode(text, requireNotNull(definition), fileName) }
                                status = ""
                            } catch (e: Exception) { failure(e) } finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.config_share_preview)) }
                }
                prepared?.let { preview ->
                    Text(stringResource(R.string.config_share_preview_count, preview.configurations.size, preview.configurations.sumOf { it.tasks.size }))
                    if (preview.versionWarnings.isNotEmpty()) Text(stringResource(R.string.config_share_versions, preview.versionWarnings.joinToString("\n")))
                    preview.resourceName?.let { Text(stringResource(R.string.config_share_resource, it)) }
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = canWrite && prepared != null, onClick = {
                val preview = prepared ?: return@TextButton
                scope.launch {
                    busy = true
                    try {
                        store.update { current ->
                            check(!runner.state.value.phase.isBusy) { lockedText }
                            check((projects.state.value as? ProjectState.Ready)?.definition === definition) { staleText }
                            current.copy(initialized = true, configurations = current.configurations + preview.configurations,
                                activeConfigurationId = preview.configurations.first().id,
                                activeResourceName = preview.resourceName ?: current.activeResourceName)
                        }
                        onDismiss()
                    } catch (e: Exception) { failure(e) } finally { busy = false }
                }
            }) { Text(stringResource(R.string.config_share_import)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
