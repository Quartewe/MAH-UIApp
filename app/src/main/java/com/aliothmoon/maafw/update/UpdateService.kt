package com.aliothmoon.maafw.update

import timber.log.Timber
import kotlinx.coroutines.CancellationException

/**
 * MAH 先比较 UIApp 公告的内部版本，再按选定来源检查主仓库 APK。
 * 其他项目直接使用选定来源；下载地址始终由选定来源解析。
 */
class UpdateService internal constructor(
    clients: Collection<UpdateSourceClient>,
    private val githubApi: GitHubReleasesApi? = null,
) {

    private val clientsBySource = clients.associateBy(UpdateSourceClient::source)

    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        val source = request.source
        Timber.tag("UpdateCheck").i(
            "source=%s currentVersion=%s channel=%s",
            source, request.currentVersion, request.channel,
        )
        if (request.uiappVersion != null) {
            val announcement = try {
                requireNotNull(githubApi).latestRelease("Quartewe/MAH-UIApp", request.channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("UpdateCheck").w(e, "UIApp release check failed")
                return UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.NETWORK)
            }
            when (announcement) {
                is UpdateSourceOutcome.Failed -> return UpdateCheckResult.SourceFailed(source, announcement.reason, announcement.detail)
                is UpdateSourceOutcome.Ok -> if (announcement.value.tag == request.uiappVersion) {
                    return UpdateCheckResult.UpToDate(source, request.currentVersion)
                }
            }
        }
        return clientsBySource.getValue(source).check(request)
    }

    suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult {
        Timber.tag("UpdateResolve").i("source=%s channel=%s", request.source, request.channel)
        return clientsBySource.getValue(request.source).resolve(request)
    }
}
