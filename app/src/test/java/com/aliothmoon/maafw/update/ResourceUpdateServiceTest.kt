package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.project.*
import com.aliothmoon.maafw.util.HttpClientHelper
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
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

    private fun assetJson(asset: GitHubReleasesApi.Asset, label: String? = null) = buildJsonObject {
        put("name", asset.name)
        put("label", label)
        put("browser_download_url", asset.downloadUrl)
        put("digest", asset.sha256)
    }

    private fun renamedAsset(
        asset: GitHubReleasesApi.Asset, label: String? = asset.name,
        name: String = asset.name.removeSuffix(".zip") + "-uploaded.zip",
    ): JsonObject {
        val renamed = asset.copy(
            name = name,
            downloadUrl = asset.downloadUrl.substringBeforeLast('/') + "/$name",
        )
        bodies[renamed.downloadUrl.removePrefix("https://example.com")] =
            bodies.getValue(asset.downloadUrl.removePrefix("https://example.com"))
        return assetJson(renamed, label)
    }

    private fun publishedApi(transform: (GitHubReleasesApi.Asset) -> List<JsonObject>): GitHubReleasesApi {
        val response = buildJsonArray {
            releases.forEach { release ->
                add(buildJsonObject {
                    put("tag_name", release.tag)
                    put("assets", buildJsonArray {
                        release.assets.forEach { asset -> transform(asset).forEach { add(it) } }
                    })
                })
            }
        }
        return GitHubReleasesApi(RecordingHttpClientHelper(FakeHttpResponse(200, response.toString())).mock)
    }

    private suspend fun publishedReleases(transform: (GitHubReleasesApi.Asset) -> List<JsonObject>) =
        (publishedApi(transform).releases("owner/resources") as UpdateSourceOutcome.Ok).value

    private fun renamePackageDescriptors(version: String, names: Map<String, String>) {
        val descriptor = fixture.descriptor(version)
        val metadata = Json.encodeToString(descriptor.copy(packages = descriptor.packages.map {
            it.copy(name = names[it.manifest.kind] ?: it.name)
        })).toByteArray()
        bodies["/$version/mah_res-manifest.json"] = metadata
        val index = releases.indexOfFirst { it.tag == version }
        releases[index] = releases[index].copy(assets = releases[index].assets.map {
            if (it.name == RESOURCE_RELEASE) it.copy(sha256 = "sha256:" + digestBytes(metadata)) else it
        })
    }

    @Test fun `first use bootstrap downloads renamed full archive by unicode label`() = runTest(dispatcher) {
        val full = fixture.description("C", "full")
        val label = "mah_res-full-新秩序·新天堂#261028.zip"
        renamePackageDescriptors("C", mapOf("full" to label))
        val api = publishedApi { asset ->
            listOf(if (asset.name == full.name) renamedAsset(asset, label) else assetJson(asset))
        }
        File(root, ProjectPackageInstaller.STATE).writeText("{}")
        val pi = File(root, "interface.json")
        pi.writeText(pi.readText().replace("\"name\":\"test\"", "\"name\":\"test\",\"resource_github\":\"https://github.com/owner/resources\""))

        GitHubResourceBootstrapper(api, downloader).ensure(root, false) { _, _ -> }

        assertEquals("C", ProjectPackageInstaller(root).state().resourceVersion)
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        assertEquals(listOf("/C/mah_res-full-C-uploaded.zip"), requests.filter { it.endsWith(".zip") })
    }

    @Test fun `renamed full and hotfix archives resolve labels across the delta chain`() = runTest(dispatcher) {
        baseline()
        val published = publishedReleases { asset ->
            listOf(if (asset.name.endsWith(".zip")) renamedAsset(asset) else assetJson(asset))
        }
        val plan = service.plan(published, root)!!
        assertEquals(listOf("B", "C"), plan.deltas.map { it.update.version })

        val prepared = service.prepare(plan, root) { _, _ -> }
        service.installPrepared(plan, prepared, root) { _, _ -> }

        assertEquals("C", ProjectPackageInstaller(root).state().resourceVersion)
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        assertEquals(listOf("/B/mah_res-hotfix-B-uploaded.zip", "/C/mah_res-hotfix-C-uploaded.zip"),
            requests.filter { it.endsWith(".zip") })
    }

    @Test fun `exact name takes priority over matching labels`() = runTest(dispatcher) {
        val full = releases.first().assets.single { it.name == fixture.description("C", "full").name }
        val published = publishedReleases { asset ->
            if (asset == full) listOf(renamedAsset(asset, name = "alias.zip"), renamedAsset(asset, name = "second.zip"), assetJson(asset))
            else listOf(assetJson(asset))
        }
        assertEquals(full.downloadUrl, service.plan(published, root)!!.full.update.downloadUrl)
    }

    @Test fun `ambiguous full archive labels fail before download`() = runTest(dispatcher) {
        val full = fixture.description("C", "full")
        val published = publishedReleases { asset ->
            if (asset.name == full.name) listOf(renamedAsset(asset), renamedAsset(asset, name = "second.zip"))
            else listOf(assetJson(asset))
        }
        try {
            service.plan(published, root)
            fail("A full archive label must identify one asset")
        } catch (e: IllegalStateException) {
            assertEquals("Resource asset is missing: ${full.name}", e.message)
            assertFalse(requests.any { it.endsWith(".zip") })
        }
    }

    @Test fun `ambiguous hotfix labels fall back to full archive`() = runTest(dispatcher) {
        baseline()
        val hotfix = fixture.description("C", "hotfix")
        val published = publishedReleases { asset ->
            if (asset.name == hotfix.name) listOf(renamedAsset(asset), renamedAsset(asset, name = "second.zip"))
            else listOf(assetJson(asset))
        }
        val plan = service.plan(published, root)!!
        assertTrue(plan.deltas.isEmpty())
        assertEquals("C", plan.full.update.version)
    }

    @Test fun `matching name digest failure does not fall back to a label`() = runTest(dispatcher) {
        val full = fixture.description("C", "full")
        val published = publishedReleases { asset ->
            if (asset.name == full.name) listOf(renamedAsset(asset, name = "alias.zip"), assetJson(asset.copy(sha256 = "sha256:" + "0".repeat(64))))
            else listOf(assetJson(asset))
        }
        try {
            service.plan(published, root)
            fail("The name-matched asset must pass digest verification")
        } catch (e: IllegalArgumentException) {
            assertEquals("Resource asset digest differs from descriptor", e.message)
        }
    }

    @Test fun `label matched archive must pass descriptor digest verification`() = runTest(dispatcher) {
        val full = fixture.description("C", "full")
        val published = publishedReleases { asset ->
            listOf(if (asset.name == full.name) renamedAsset(asset.copy(sha256 = "sha256:" + "0".repeat(64))) else assetJson(asset))
        }
        try {
            service.plan(published, root)
            fail("The label-matched asset must pass digest verification")
        } catch (e: IllegalArgumentException) {
            assertEquals("Resource asset digest differs from descriptor", e.message)
        }
    }

    @Test fun `missing empty or different labels do not match a renamed archive`() = runTest(dispatcher) {
        val full = fixture.description("C", "full")
        for (label in listOf(null, "", "other.zip")) {
            val published = publishedReleases { asset ->
                listOf(if (asset.name == full.name) renamedAsset(asset, label) else assetJson(asset))
            }
            try {
                service.plan(published, root)
                fail("A missing name requires an exact matching label")
            } catch (e: IllegalStateException) {
                assertEquals("Resource asset is missing: ${full.name}", e.message)
            }
        }
    }

    @Test fun `full archive falls back to the last six digits with the matching package kind`() = runTest(dispatcher) {
        renamePackageDescriptors("C", mapOf("full" to "mah_res-full-新秩序·新天堂#261028.zip"))
        val full = fixture.description("C", "full").name
        val hotfix = fixture.description("C", "hotfix").name
        val published = publishedReleases { asset ->
            listOf(when (asset.name) {
                full -> renamedAsset(asset, label = null, name = "mah_res-full-.261028.zip")
                hotfix -> renamedAsset(asset, label = null, name = "mah_res-hotfix-.261028.zip")
                else -> assetJson(asset)
            })
        }
        val plan = service.plan(published, root)!!
        val prepared = service.prepare(plan, root) { _, _ -> }
        service.installPrepared(plan, prepared, root) { _, _ -> }

        assertEquals("C", ProjectPackageInstaller(root).state().resourceVersion)
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        assertEquals(listOf("/C/mah_res-full-.261028.zip"), requests.filter { it.endsWith(".zip") })
    }

    @Test fun `last six digit fallback resolves a complete hotfix chain`() = runTest(dispatcher) {
        baseline()
        val suffixes = mapOf("B" to "261027", "C" to "261028")
        suffixes.forEach { (version, suffix) ->
            renamePackageDescriptors(version, listOf("full", "hotfix").associateWith {
                "mah_res-$it-资源#$suffix.zip"
            })
        }
        val published = publishedReleases { asset ->
            val version = asset.downloadUrl.substringAfter("https://example.com/").substringBefore('/')
            val suffix = suffixes[version]
            listOf(if (suffix != null && asset.name.endsWith(".zip")) {
                val kind = if (asset.name.startsWith("mah_res-hotfix-")) "hotfix" else "full"
                renamedAsset(asset, label = null, name = "mah_res-$kind-.20$suffix.zip")
            } else assetJson(asset))
        }
        val plan = service.plan(published, root)!!
        assertEquals(listOf("B", "C"), plan.deltas.map { it.update.version })
        val prepared = service.prepare(plan, root) { _, _ -> }
        service.installPrepared(plan, prepared, root) { _, _ -> }

        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        assertEquals("C", ProjectPackageInstaller(root).state().resourceVersion)
        assertEquals(listOf("/B/mah_res-hotfix-.20261027.zip", "/C/mah_res-hotfix-.20261028.zip"),
            requests.filter { it.endsWith(".zip") })
    }

    @Test fun `exact name and label take priority over numeric suffix candidates`() = runTest(dispatcher) {
        baseline()
        val hotfix = fixture.description("C", "hotfix").name
        val expected = "mah_res-hotfix-261028.zip"
        renamePackageDescriptors("C", mapOf("hotfix" to expected))
        for (byLabel in listOf(false, true)) {
            val selectedName = if (byLabel) "mah_res-hotfix-published-261028.zip" else expected
            val published = publishedReleases { asset ->
                if (asset.name == hotfix) listOf(
                    renamedAsset(asset, label = null, name = "mah_res-hotfix-backup-261028.zip"),
                    renamedAsset(asset, label = if (byLabel) expected else null, name = selectedName),
                ) else listOf(assetJson(asset))
            }
            val plan = service.plan(published, root)!!
            assertEquals(listOf("B", "C"), plan.deltas.map { it.update.version })
            assertEquals("https://example.com/C/$selectedName", plan.deltas.last().update.downloadUrl)
        }
    }

    @Test fun `ambiguous hotfix labels or numeric suffixes fall back to full`() = runTest(dispatcher) {
        baseline()
        val hotfix = fixture.description("C", "hotfix").name
        val expected = "mah_res-hotfix-资源#261028.zip"
        renamePackageDescriptors("C", mapOf("hotfix" to expected))
        for (byLabel in listOf(false, true)) {
            val label = if (byLabel) expected else null
            val secondName = if (byLabel) "mah_res-hotfix-other.zip" else "mah_res-hotfix-backup-261028.zip"
            val published = publishedReleases { asset ->
                if (asset.name == hotfix) listOf(
                    renamedAsset(asset, label = label, name = "mah_res-hotfix-.261028.zip"),
                    renamedAsset(asset, label = label, name = secondName),
                ) else listOf(assetJson(asset))
            }
            val plan = service.plan(published, root)!!
            assertTrue(plan.deltas.isEmpty())
            assertEquals("C", plan.full.update.version)
        }
    }

    @Test fun `numeric suffix must be trailing and have six matching digits of the same kind`() = runTest(dispatcher) {
        val full = fixture.description("C", "full").name
        val hotfix = fixture.description("C", "hotfix").name
        val expected = "mah_res-full-资源#261028.zip"
        renamePackageDescriptors("C", mapOf("full" to expected))
        for (name in listOf("mah_res-full-.261027.zip", "mah_res-full-.61028.zip", "mah_res-full-261028-backup.zip")) {
            val published = publishedReleases { asset ->
                listOf(when (asset.name) {
                    full -> renamedAsset(asset, label = null, name = name)
                    hotfix -> renamedAsset(asset, label = null, name = "mah_res-hotfix-.261028.zip")
                    else -> assetJson(asset)
                })
            }
            try {
                service.plan(published, root)
                fail("The full archive must have a matching six digit suffix")
            } catch (e: IllegalStateException) {
                assertEquals("Resource asset is missing: $expected", e.message)
            }
        }
    }

    @Test fun `numeric suffix match must pass descriptor digest verification`() = runTest(dispatcher) {
        val full = fixture.description("C", "full").name
        renamePackageDescriptors("C", mapOf("full" to "mah_res-full-资源#261028.zip"))
        val published = publishedReleases { asset ->
            listOf(if (asset.name == full) {
                renamedAsset(asset.copy(sha256 = "sha256:" + "0".repeat(64)), label = null, name = "mah_res-full-.261028.zip")
            } else assetJson(asset))
        }
        try {
            service.plan(published, root)
            fail("The suffix-matched archive must pass digest verification")
        } catch (e: IllegalArgumentException) {
            assertEquals("Resource asset digest differs from descriptor", e.message)
        }
    }

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

    @Test fun `first use bootstrap accepts new published manifest and downloads full`() = runTest(dispatcher) {
        File(root, ProjectPackageInstaller.STATE).writeText("{}")
        val pi = File(root, "interface.json")
        pi.writeText(pi.readText().replace("\"name\":\"test\"", "\"name\":\"test\",\"resource_github\":\"https://github.com/owner/resources\""))
        val api = mockk<GitHubReleasesApi> {
            every { parseRepository(any()) } returns "owner/resources"
            coEvery { releases(any()) } returns UpdateSourceOutcome.Ok(this@ResourceUpdateServiceTest.releases)
        }
        GitHubResourceBootstrapper(api, downloader).ensure(root, false) { _, _ -> }
        assertEquals("C", ProjectPackageInstaller(root).state().resourceVersion)
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        assertTrue(requests.any { "full-C" in it })
        assertFalse(requests.any { "hotfix" in it })
    }
}
