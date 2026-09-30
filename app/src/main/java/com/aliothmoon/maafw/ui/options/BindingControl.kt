package com.aliothmoon.maafw.ui.options

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSwitch

val LocalBindingChange = androidx.compose.runtime.staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/** Shared by the regular editor and the overlay; mode belongs to the source option. */
@Composable
fun BindingControl(option: OptionEditorState, locked: Boolean, onSetBinding: (String, Boolean) -> Unit) {
    val binding = option.binding ?: return
    MaaLabeledControlRow(label = stringResource(R.string.mah_binding_per_target, binding.targetLabel)) {
        MaaSwitch(checked = binding.perTarget, enabled = !locked,
            onCheckedChange = { onSetBinding(option.name, it) })
    }
}
