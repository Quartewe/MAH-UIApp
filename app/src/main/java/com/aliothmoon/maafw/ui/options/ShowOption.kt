package com.aliothmoon.maafw.ui.options

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ShowContentReader
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun ShowOption(option: OptionEditorState) {
    val installer: PiInstaller = koinInject()
    var refresh by remember(option.name) { mutableIntStateOf(0) }
    Column {
        option.shows.forEach { show ->
            val content by produceState("", show, refresh) {
                value = withContext(Dispatchers.IO) {
                    runCatching { ShowContentReader.read(installer.installedDir(), show) }
                        .getOrElse { it.message.orEmpty() }
                }
            }
            Text(show.label)
            if (content.isBlank()) Text(stringResource(R.string.mah_show_empty)) else MaaMarkdown(text = content)
        }
        TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.mah_show_refresh)) }
    }
}
