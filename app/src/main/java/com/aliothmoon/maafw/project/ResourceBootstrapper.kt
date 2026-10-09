package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.i18n.UiText
import java.io.File
import java.io.IOException

enum class ResourcePreparationPhase { Checking, Downloading, Installing }

fun interface ResourceBootstrapper {
    suspend fun ensure(
        root: File,
        verifyHashes: Boolean,
        progress: (ResourcePreparationPhase, Float?) -> Unit,
    )
}

class ResourcePreparationException(val reason: UiText) : IOException("Resource preparation failed")
