package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.project.*
import com.aliothmoon.maafw.util.HttpClientHelper
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceUpdateServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var fixture: ResourceProtocolFixture
    private lateinit var root: File
    private lateinit var service: ResourceUpdateService
    private lateinit var downloader: OkHttpUpdateDownloader
    private val bodies = mutableMapOf<String, ByteArray>()
    private val requests = mutableListOf<String>()
    private val releases = mutableListOf<GitHubReleasesApi.Release>()

    @Before fun setup() {
        mockkObject(MaaDispatchers, AppPaths)
        every { MaaDispatchers.IO } returns dispatcher
        every { AppPaths.UPDATES_CACHE_DIR } returns temporary.newFolder("cache")
        fixture = ResourceProtocolFixture(temporary.newFolder("fixture"))
        root = fixture.root()
        for (version in listOf("C", "B", "A")) {
            val desc = fixture.descriptor(version)
            val metadata = Json.encodeToString(desc).toByteArray()
            bodies["/$version/mah_res-manifest.json"] = metadata
            val assets = mutableListOf(GitHubReleasesApi.Asset("mah_res-manifest.json", "https://example.com/$version/mah_res-manifest.json", "sha256:" + digestBytes(metadata)))
            desc.packages.forEach { p ->
                bodies["/$version/${p.name}"] = fixture.bytes(version, p.manifest.kind)
                assets += GitHubReleasesApi.Asset(p.name, "https://example.com/$version/${p.name}", "sha256:" + p.sha256)
            }
            releases += GitHubReleasesApi.Release(version, null, null, assets)
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            requests += path
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bodies.getValue(path).toResponseBody()).build()
        }.build()
        downloader = OkHttpUpdateDownloader(HttpClientHelper(client))
        service = ResourceUpdateService(downloader)
    }
    @After fun cleanup() = unmockkObject(MaaDispatchers, AppPaths)
    private fun baseline() = ProjectPackageInstaller(root).installResources(listOf(fixture.archive("A", "full")))

    @Test fun `cross version update downloads only the required deltas`() = runTest(dispatcher) {
        baseline()
        val plan = service.plan(releases, root)!!
        assertEquals(listOf("B", "C"), plan.deltas.map { it.update.version })
        val prepared = service.prepare(plan, root) { _, _ -> }
        service.installPrepared(plan, prepared, root) { _, _ -> }
        assertEquals("C", ProjectPackageInstaller(root).state().resourceVersion)
        assertEquals(2, requests.count { "hotfix" in it })
        assertFalse(requests.any { "full-" in it })
    }

    @Test fun `new installation and missing chain use full archive`() = runTest(dispatcher) {
        val first = service.plan(releases, root)!!
        assertTrue(first.deltas.isEmpty())
        baseline()
        assertTrue(service.plan(releases.filterNot { it.tag == "B" }, root)!!.deltas.isEmpty())
        val prepared = service.prepare(first, root) { _, _ -> }
        service.installPrepared(first, prepared, root) { _, _ -> }
        assertTrue(requests.any { "full-C" in it })
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
    }

    @Test fun `same size baseline corruption falls back to full before delta downloads`() = runTest(dispatcher) {
        baseline()
        val plan = service.plan(releases, root)!!
        File(root, "resource/base/image/ar/a.png").writeText("XX")
        val prepared = service.prepare(plan, root) { _, _ -> }
        service.installPrepared(plan, prepared, root) { _, _ -> }
        assertFalse(requests.any { "hotfix" in it })
        assertTrue(requests.any { "full-C" in it })
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
    }

    @Test fun `baseline changed during download is checked again under installation gate`() = runTest(dispatcher) {
        baseline()
        val plan = service.plan(releases, root)!!
        val prepared = service.prepare(plan, root) { _, _ -> }
        File(root, "resource/base/image/ar/a.png").delete()
        service.installPrepared(plan, prepared, root) { _, _ -> }
        assertTrue(requests.any { "full-C" in it })
        assertEquals(fixture.descriptor("C").contentId, ProjectPackageInstaller(root).verifiedResourceContentId())
    }

    @Test fun `current content avoids updates and repair selects the installed version`() = runTest(dispatcher) {
        baseline()
        assertFalse(service.plan(releases.filter { it.tag == "A" }, root)!!.available)
        val repair = service.plan(releases, root, repair = true)!!
        assertEquals("A", repair.full.update.version)
        assertTrue(repair.deltas.isEmpty())
    }

    @Test fun `corrupt download leaves installed version and files unchanged`() = runTest(dispatcher) {
        baseline()
        val plan = service.plan(releases, root)!!
        bodies["/B/mah_res-hotfix-B.zip"] = "bad".toByteArray()
        try {
            service.prepare(plan, root) { _, _ -> }
            fail("Digest verification must fail")
        } catch (_: ResourcePreparationException) {
            assertEquals("A", ProjectPackageInstaller(root).state().resourceVersion)
            assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        }
    }

    @Test fun `republished tag with different content is an update`() = runTest(dispatcher) {
        baseline()
        val changedFull = fixture.description("B", "full").let { it.copy(manifest = it.manifest.copy(version = "A")) }
        val desc = ResourceReleaseDescription(1, "A", changedFull.manifest.contentId, listOf(changedFull))
        val metadata = Json.encodeToString(desc).toByteArray()
        bodies["/A/mah_res-manifest.json"] = metadata
        val release = GitHubReleasesApi.Release("A", null, null, listOf(
            GitHubReleasesApi.Asset("mah_res-manifest.json", "https://example.com/A/mah_res-manifest.json", "sha256:" + digestBytes(metadata)),
            GitHubReleasesApi.Asset(changedFull.name, "https://example.com/B/${changedFull.name}", "sha256:" + changedFull.sha256),
        ))
        assertTrue(service.plan(listOf(release), root)!!.available)
    }

    @Test fun `unavailable intermediate descriptor falls back to latest full`() = runTest(dispatcher) {
        baseline()
        bodies["/B/mah_res-manifest.json"] = byteArrayOf(1)
        val plan = service.plan(releases, root)!!
        assertTrue(plan.deltas.isEmpty())
        assertEquals("C", plan.full.update.version)
    }

    @Test fun `invalid latest descriptor fails without modifying installed resources`() = runTest(dispatcher) {
        baseline()
        bodies["/C/mah_res-manifest.json"] = byteArrayOf(1)
        try {
            service.plan(releases, root)
            fail("Descriptor digest must be verified")
        } catch (_: ResourcePreparationException) {
            assertEquals("A", ProjectPackageInstaller(root).state().resourceVersion)
            assertFalse(requests.any { it.endsWith(".zip") })
        }
    }

    @Test fun `home update manager installs delta plan under task gate and reloads once`() = runTest(dispatcher) {
        baseline()
        val definition = com.aliothmoon.maafw.domain.ProjectDefinition(
            name = "test", version = "v1", resources = emptyList(), tasks = emptyList(),
            groups = emptyList(), options = emptyMap(), templates = emptyList(),
            metadata = com.aliothmoon.maafw.domain.ProjectMetadata(resourceRepository = "owner/resources"),
        )
        val repository = FakeProjectRepository(ProjectState.Ready(definition, emptyList()))
        val installer = mockk<PiInstaller> { every { installedDir() } returns root }
        val api = mockk<GitHubReleasesApi> {
            every { parseRepository(any()) } returns "owner/resources"
            coEvery { releases(any()) } returns UpdateSourceOutcome.Ok(this@ResourceUpdateServiceTest.releases)
        }
        var gated = false
        val launcher = mockk<com.aliothmoon.maafw.runner.RunLauncher> {
            coEvery { changeProjectWhenIdle<Unit>(any()) } coAnswers {
                gated = true
                firstArg<suspend () -> Unit>().invoke()
            }
        }
        val manager = ProjectUpdateManager(repository, installer, api, downloader, launcher)
        assertTrue(manager.check(ProjectPackageTarget.Resource))
        manager.install(ProjectPackageTarget.Resource)
        assertTrue(gated)
        assertEquals(1, repository.reloadCount)
        assertEquals("C", manager.state.value.versions.resourceVersion)
        assertNull(manager.state.value.entries[ProjectPackageTarget.Resource]?.candidate)
        assertFalse(requests.any { "full-" in it })
    }

}
