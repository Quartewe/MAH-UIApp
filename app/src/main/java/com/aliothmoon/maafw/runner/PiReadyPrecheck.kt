package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.PiInstallCoordinator
import com.aliothmoon.maafw.project.PiInstallState

/** Also blocks scheduled runs when resource preparation or a manual repair has failed. */
class PiReadyPrecheck(private val installation: PiInstallCoordinator) : RunPrecheck {
    override suspend fun evaluate(ctx: RunContext): Verdict =
        if (installation.state.value == PiInstallState.Ready) Verdict.Pass
        else Verdict.Block(uiTextOf(R.string.msg_project_not_loaded))
}
