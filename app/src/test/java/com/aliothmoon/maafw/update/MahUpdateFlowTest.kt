package com.aliothmoon.maafw.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MahUpdateFlowTest {
    private fun request(channel: UpdateChannel = UpdateChannel.STABLE) = UpdateCheckRequest(
        source = UpdateSource.GITHUB, currentVersion = "v9.0.0", abi = AndroidAbi.UNIVERSAL,
        githubRepository = "Quartewe/MAH", uiappVersion = "v1.0.0", channel = channel,
    )

    private fun release(tag: String, apk: Boolean = false): String =
        """{"tag_name":"$tag","assets":${if (apk) """[{"name":"MAH-android-universal-$tag-debug.apk","browser_download_url":"https://example.com/$tag.apk"}]""" else "[]"}}"""

    private fun service(gateway: RecordingHttpClientHelper): UpdateService {
        val api = GitHubReleasesApi(gateway.mock)
        return UpdateService(listOf(GitHubUpdateClient(api)), api)
    }

    @Test
    fun `announcement without assets compares internally then finds APK in MAH latest`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, release("v1.1.0")),
            FakeHttpResponse(200, release("v2.0.0", apk = true)),
            FakeHttpResponse(200, release("v2.0.0", apk = true)),
        )
        val service = service(gateway)
        val result = service.check(request()) as UpdateCheckResult.UpdateAvailable
        assertEquals("v2.0.0", result.info.version) // Lower than the local tag is still a different Latest.
        assertEquals(listOf(
            "https://api.github.com/repos/Quartewe/MAH-UIApp/releases/latest",
            "https://api.github.com/repos/Quartewe/MAH/releases/latest",
        ), gateway.requests.map { it.first })
        val resolved = service.resolve(UpdateResolveRequest(
            source = UpdateSource.GITHUB, abi = AndroidAbi.UNIVERSAL, currentVersion = "v9.0.0",
            githubRepository = "Quartewe/MAH", useLatestRelease = true,
        )) as UpdateResolveResult.Resolved
        assertEquals("https://example.com/v2.0.0.apk", resolved.update.downloadUrl)
        assertTrue(gateway.requests.last().first.contains("/Quartewe/MAH/"))
    }

    @Test
    fun `same UIApp tag skips APK lookup so the caller can check project ZIP`() = runBlocking {
        val gateway = RecordingHttpClientHelper(FakeHttpResponse(200, release("v1.0.0")))
        assertTrue(service(gateway).check(request()) is UpdateCheckResult.UpToDate)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `new announcement with unchanged MAH APK does not repeat installation`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, release("v1.1.0")),
            FakeHttpResponse(200, release("v9.0.0", apk = true)),
        )
        assertTrue(service(gateway).check(request()) is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `beta follows latest release order instead of maximum version or APK in UIApp`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, "[${release("v1.0.1-alpha4")},${release("v8.0.0")}]"),
            FakeHttpResponse(200, "[${release("v2.0.1-alpha4", true)},${release("v9.0.0", true)}]"),
        )
        val result = service(gateway).check(request(UpdateChannel.BETA)) as UpdateCheckResult.UpdateAvailable
        assertEquals("v2.0.1-alpha4", result.info.version)
        assertTrue(gateway.requests.all { it.first.substringBefore('?').endsWith("/releases") })
    }

    @Test
    fun `announcement error returns a check failure for continuation`() = runBlocking {
        val gateway = RecordingHttpClientHelper(FakeHttpResponse(503, "{}"))
        assertTrue(service(gateway).check(request()) is UpdateCheckResult.SourceFailed)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `latest MAH release without APK reports missing asset instead of an older release`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, release("v1.1.0")),
            FakeHttpResponse(200, release("v2.0.0")),
        )
        val result = service(gateway).check(request()) as UpdateCheckResult.SourceFailed
        assertEquals(UpdateCheckFailure.NO_MATCHING_ASSET, result.reason)
        assertEquals(2, gateway.requests.size)
    }
}
