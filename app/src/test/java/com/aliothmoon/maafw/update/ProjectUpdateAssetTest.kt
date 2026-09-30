package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.project.ProjectPackageTarget
import org.junit.Assert.*
import org.junit.Test

class ProjectUpdateAssetTest {
    @Test fun `resource updater accepts only complete resource assets and project assets stay separate`() {
        val assets = listOf("mah_res-hotfix-A.zip", "mah_res-full-A.zip", "MAH-project-android-v1.zip", "MAH.apk")
            .map { GitHubReleasesApi.Asset(it, "https://example.com/$it", "sha256:" + "0".repeat(64)) }
        assertEquals("mah_res-full-A.zip", selectProjectAsset(assets, ProjectPackageTarget.Resource)?.name)
        assertEquals("MAH-project-android-v1.zip", selectProjectAsset(assets, ProjectPackageTarget.Project)?.name)
        assertNull(selectProjectAsset(assets.filter { "full" !in it.name }, ProjectPackageTarget.Resource))
    }
}
