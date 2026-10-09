package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.project.*
import com.aliothmoon.maafw.util.HttpClientHelper
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubResourceBootstrapperTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private val archive = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            mapOf("image/character/a.png" to "A", "image/ar/b.png" to "B",
                "index/ui.json" to "{}", "index/characters.json" to "{}", "index/ar.json" to "{}").forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }

    @Before fun setup() {
        mockkObject(MaaDispatchers, AppPaths)
        every { MaaDispatchers.IO } returns dispatcher
        every { AppPaths.UPDATES_CACHE_DIR } returns temp.newFolder("cache")
    }
    @After fun cleanup() = unmockkObject(MaaDispatchers, AppPaths)

    private fun root() = temp.newFolder("pi").also {
        File(it, "interface.json").writeText("""{"interface_version":2,"name":"test","resource_github":"https://github.com/owner/resources","resource":[{"name":"base","path":["resource/base"]}]}""")
        File(it, ProjectPackageInstaller.STATE).writeText("{}")
        File(it, "data").mkdirs()
        File(it, "data/custom.json").writeText("user combat")
    }

    private fun release(tag: String, hash: String = digest) =
        """{"tag_name":"$tag","assets":[{"name":"mah_res-full-$tag.zip","browser_download_url":"https://example.com/$tag.zip","digest":"sha256:$hash"}]}"""

    private fun bootstrap(api: RecordingHttpClientHelper, bytes: ByteArray = archive): GitHubResourceBootstrapper {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        return GitHubResourceBootstrapper(GitHubReleasesApi(api.mock), OkHttpUpdateDownloader(HttpClientHelper(client)))
    }

    @Test fun `first initialization downloads verifies and installs then works offline`() = runTest(dispatcher) {
        val root = root()
        val api = RecordingHttpClientHelper(FakeHttpResponse(200, "[${release("A")}]"))
        val phases = mutableListOf<ResourcePreparationPhase>()
        bootstrap(api).ensure(root, false) { phase, _ -> phases += phase }
        assertEquals("A", ProjectPackageInstaller(root).state().resourceVersion)
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
        assertEquals("user combat", File(root, "data/custom.json").readText())
        assertEquals(ResourcePreparationPhase.Installing, phases.last())
        // There are no queued responses: either a release or download request would fail.
        val offline = RecordingHttpClientHelper()
        GitHubResourceBootstrapper(GitHubReleasesApi(offline.mock), mockk()).ensure(root, false) { _, _ -> }
        assertTrue(offline.requests.isEmpty())
    }

    @Test fun `missing resources repair the installed tag and preserve custom files`() = runTest(dispatcher) {
        val root = root()
        bootstrap(RecordingHttpClientHelper(FakeHttpResponse(200, "[${release("A")}]"))).ensure(root, false) { _, _ -> }
        File(root, "resource/base/image/character/a.png").delete()
        bootstrap(RecordingHttpClientHelper(FakeHttpResponse(200, "[${release("B")},${release("A")}]"))).ensure(root, false) { _, _ -> }
        assertEquals("A", ProjectPackageInstaller(root).state().resourceVersion)
        assertEquals("A", File(root, "resource/base/image/character/a.png").readText())
        assertEquals("user combat", File(root, "data/custom.json").readText())
    }

    @Test fun `digest failure leaves base project intact and retry can complete`() = runTest(dispatcher) {
        val root = root()
        try {
            bootstrap(RecordingHttpClientHelper(FakeHttpResponse(200, "[${release("A")}]")), "corrupt".toByteArray()).ensure(root, false) { _, _ -> }
            fail("Corrupt archive must fail")
        } catch (_: ResourcePreparationException) {
            assertEquals("", ProjectPackageInstaller(root).state().resourceVersion)
            assertFalse(File(root, "resource/index/ui.json").exists())
            assertEquals("user combat", File(root, "data/custom.json").readText())
        }
        bootstrap(RecordingHttpClientHelper(FakeHttpResponse(200, "[${release("A")}]"))).ensure(root, false) { _, _ -> }
        assertTrue(ProjectPackageInstaller(root).resourcesComplete(true))
    }

    @Test fun `offline first initialization fails without recording resources as installed`() = runTest(dispatcher) {
        val root = root()
        try {
            bootstrap(RecordingHttpClientHelper(FakeHttpResponse(503, "{}"))).ensure(root, false) { _, _ -> }
            fail("Offline initialization must fail")
        } catch (_: ResourcePreparationException) {
            assertFalse(ProjectPackageInstaller(root).resourcesComplete())
            assertEquals("user combat", File(root, "data/custom.json").readText())
        }
    }
}
