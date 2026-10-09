package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.project.ProjectPackageInstaller
import com.aliothmoon.maafw.project.ProjectPackageTarget
import com.aliothmoon.maafw.project.ProjectState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectUpdateManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `resource candidate survives a later manual project check and can be installed separately`() = runTest {
        val gateway = RecordingHttpClientHelper(
            release("resource-new", "mah_res-full-resource-new.zip"),
            release("v2.0.0", "MAH-project-android-v2.0.0.zip"),
        )
        var downloaded: String? = null
        val manager = manager(gateway, mockk {
            coEvery { download(any(), any()) } coAnswers {
                downloaded = firstArg<ResolvedUpdate>().downloadUrl
                UpdateDownloadResult.Failed(UpdateDownloadFailure.NETWORK)
            }
        })

        assertTrue(manager.check(ProjectPackageTarget.Resource))
        assertEquals("resource-new", manager.state.value.entries[ProjectPackageTarget.Resource]?.candidate?.update?.version)
        assertTrue(manager.check(ProjectPackageTarget.Project))
        assertEquals(2, manager.state.value.entries.count { it.value.candidate != null })

        manager.install(ProjectPackageTarget.Resource)
        assertEquals("https://example.com/mah_res-full-resource-new.zip", downloaded)
        // A failed download remains retryable and does not remove the other update.
        assertEquals(2, manager.state.value.entries.count { it.value.candidate != null })
        assertNotNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.message)
        assertFalse(manager.state.value.busy)
    }

    @Test
    fun `current installed resource version permits checking the project next`() = runTest {
        val gateway = RecordingHttpClientHelper(
            release("resource-old", "mah_res-full-resource-old.zip"),
            release("v2.0.0", "MAH-project-android-v2.0.0.zip"),
        )
        val manager = manager(gateway)

        assertFalse(manager.check(ProjectPackageTarget.Resource))
        assertTrue(manager.check(ProjectPackageTarget.Project))
        assertNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.candidate)
        assertNotNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.message)
        assertTrue(gateway.requests[0].first.contains("/repos/owner/resources/releases"))
        assertTrue(gateway.requests[1].first.contains("/repos/owner/project/releases"))
    }

    @Test
    fun `failed resource check retains its error while project check succeeds`() = runTest {
        val manager = manager(RecordingHttpClientHelper(
            FakeHttpResponse(503, "{}"),
            release("v2.0.0", "MAH-project-android-v2.0.0.zip"),
        ))
        assertFalse(manager.check(ProjectPackageTarget.Resource))
        assertTrue(manager.check(ProjectPackageTarget.Project))
        assertNotNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.message)
        assertNotNull(manager.state.value.entries[ProjectPackageTarget.Project]?.candidate)
        assertFalse(manager.state.value.busy)
    }

    @Test
    fun `rechecking one target clears its old candidate without clearing the other target`() = runTest {
        val manager = manager(RecordingHttpClientHelper(
            release("resource-new", "mah_res-full-resource-new.zip"),
            release("v2.0.0", "MAH-project-android-v2.0.0.zip"),
            release("resource-old", "mah_res-full-resource-old.zip"),
        ))
        assertTrue(manager.check(ProjectPackageTarget.Resource))
        assertTrue(manager.check(ProjectPackageTarget.Project))
        assertFalse(manager.check(ProjectPackageTarget.Resource))
        assertNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.candidate)
        assertNotNull(manager.state.value.entries[ProjectPackageTarget.Project]?.candidate)
    }

    @Test
    fun `cancelled check releases busy state and does not report an available update`() = runTest {
        val gateway = RecordingHttpClientHelper()
        coEvery { gateway.mock.get(any(), any(), any()) } throws CancellationException("cancel")
        val manager = manager(gateway)
        try {
            manager.check(ProjectPackageTarget.Resource)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(manager.state.value.busy)
            assertNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.candidate)
        }
    }

    private fun manager(
        gateway: RecordingHttpClientHelper,
        downloader: OkHttpUpdateDownloader = mockk(),
    ): ProjectUpdateManager {
        val root = temporary.newFolder()
        root.resolve(ProjectPackageInstaller.STATE).writeText(
            """{"projectVersion":"v1.0.0","resourceVersion":"resource-old"}""",
        )
        val definition = ProjectDefinition(
            name = "test", version = "v1.0.0", resources = emptyList(), tasks = emptyList(),
            groups = emptyList(), options = emptyMap(), templates = emptyList(),
            metadata = ProjectMetadata(projectRepository = "owner/project", resourceRepository = "owner/resources"),
        )
        val installer = mockk<PiInstaller> { every { installedDir() } returns root }
        return ProjectUpdateManager(
            FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
            installer, GitHubReleasesApi(gateway.mock), downloader, mockk(),
        )
    }

    private fun release(version: String, name: String) = FakeHttpResponse(200,
        """[{"tag_name":"$version","assets":[{"name":"$name","browser_download_url":"https://example.com/$name"}]}]""",
    )
}
