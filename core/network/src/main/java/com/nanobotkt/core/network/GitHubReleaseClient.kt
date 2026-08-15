package com.nanobotkt.core.network

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** App 构建渠道与 GitHub Release 查询端点的一一映射。 */
enum class GitHubReleaseChannel {
    DEV,
    RELEASE,
}

/**
 * 已通过仓库、域名与路径校验的 GitHub Release 资产。
 *
 * `downloadUrl` 只能来自 yaotutu/nanobotkt 的 Release 下载路径，feature 层不能把远端 JSON
 * 中的任意 URL 直接交给下载器，避免更新功能被利用去访问未知站点。
 */
data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
)

/** 网络层完成字段校验与 universal APK 选择后暴露给更新 Repository 的稳定模型。 */
data class GitHubRelease(
    val tagName: String,
    val releaseName: String,
    val body: String,
    val prerelease: Boolean,
    val versionName: String,
    val asset: GitHubReleaseAsset,
)

/** GitHub Release 查询与 APK 下载的最小网络契约，便于 feature 测试覆盖状态转换。 */
interface GitHubReleaseService {
    suspend fun fetchRelease(channel: GitHubReleaseChannel): GitHubRelease

    suspend fun downloadAsset(
        asset: GitHubReleaseAsset,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    )
}

/**
 * 更新网络错误的稳定分类。
 *
 * feature 层只根据类型生成用户可理解的提示，不依赖 GitHub 返回的英文或 HTML 错误正文。
 */
sealed class GitHubReleaseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ReleaseNotFound : GitHubReleaseException("release_not_found")
    class RateLimited : GitHubReleaseException("github_rate_limited")
    class Http(val status: Int) : GitHubReleaseException("github_http_$status")
    class InvalidRelease(message: String, cause: Throwable? = null) : GitHubReleaseException(message, cause)
    class ApkNotFound : GitHubReleaseException("universal_apk_not_found")
    class DownloadUnavailable(val status: Int) : GitHubReleaseException("apk_download_http_$status")
    class EmptyDownload : GitHubReleaseException("apk_download_empty")
    class Timeout(cause: Throwable) : GitHubReleaseException("github_timeout", cause)
    class Network(cause: Throwable) : GitHubReleaseException("github_network_unavailable", cause)
}

@Serializable
internal data class GitHubReleasePayload(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAssetPayload> = emptyList(),
)

@Serializable
internal data class GitHubReleaseAssetPayload(
    val name: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    val size: Long? = null,
)

/**
 * 只负责把 GitHub JSON 转换为经过安全校验的 Release。
 *
 * Dev 的 tag 固定为 dev-latest，因此版本号优先从 CHANGELOG 标题解析；正式版则严格使用
 * `v0.1.x` tag。这里不推断远端 versionCode，因为现有 Release 并未发布该字段。
 */
internal object GitHubReleaseParser {
    private val formalTag = Regex("^v(\\d+\\.\\d+\\.\\d+)$")
    private val changelogVersion = Regex(
        pattern = "(?im)^#{1,6}\\s*NanobotKT\\s+v?(\\d+\\.\\d+\\.\\d+)(?:-dev)?\\s*$",
    )
    private val devNameVersion = Regex("(?i)NanobotKT\\s+dev\\s+latest\\s*\\(v?(\\d+\\.\\d+\\.\\d+)-dev\\)")

    fun parse(json: Json, raw: String, channel: GitHubReleaseChannel): GitHubRelease {
        val payload = try {
            json.decodeFromString(GitHubReleasePayload.serializer(), raw)
        } catch (error: SerializationException) {
            throw GitHubReleaseException.InvalidRelease("invalid_release_json", error)
        } catch (error: IllegalArgumentException) {
            throw GitHubReleaseException.InvalidRelease("invalid_release_json", error)
        }

        val tag = payload.tagName?.trim().orEmpty()
        if (tag.isBlank()) throw GitHubReleaseException.InvalidRelease("missing_release_tag")

        val versionName = when (channel) {
            GitHubReleaseChannel.RELEASE -> {
                formalTag.matchEntire(tag)?.groupValues?.get(1)
                    ?: throw GitHubReleaseException.InvalidRelease("invalid_formal_release_tag")
            }
            GitHubReleaseChannel.DEV -> {
                if (tag != DEV_TAG) throw GitHubReleaseException.InvalidRelease("invalid_dev_release_tag")
                changelogVersion.find(payload.body.orEmpty())?.groupValues?.get(1)
                    ?: devNameVersion.find(payload.name.orEmpty())?.groupValues?.get(1)
                    ?: throw GitHubReleaseException.InvalidRelease("missing_dev_release_version")
            }
        }

        val asset = payload.assets
            .asSequence()
            .filter { it.name.orEmpty().endsWith(".apk", ignoreCase = true) }
            // universal APK 不依赖设备 ABI；下载错误架构 APK 会在系统安装器阶段才失败，
            // 因此首版宁可明确报错，也不静默回退到任意 ABI 资产。
            .filter { it.name.orEmpty().contains("universal", ignoreCase = true) }
            .mapNotNull(::validatedAsset)
            .firstOrNull()
            ?: throw GitHubReleaseException.ApkNotFound()

        return GitHubRelease(
            tagName = tag,
            releaseName = payload.name.orEmpty(),
            body = payload.body.orEmpty(),
            prerelease = payload.prerelease,
            versionName = versionName,
            asset = asset,
        )
    }

    private fun validatedAsset(payload: GitHubReleaseAssetPayload): GitHubReleaseAsset? {
        val name = payload.name?.trim().orEmpty()
        val rawUrl = payload.browserDownloadUrl?.trim().orEmpty()
        if (name.isBlank() || rawUrl.isBlank()) return null
        val url = runCatching { rawUrl.toHttpUrl() }.getOrNull() ?: return null
        if (!isExpectedReleaseAssetUrl(url)) return null
        return GitHubReleaseAsset(
            name = name,
            downloadUrl = url.toString(),
            sizeBytes = payload.size?.takeIf { it > 0L },
        )
    }

    private fun isExpectedReleaseAssetUrl(url: HttpUrl): Boolean =
        url.isHttps &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.encodedPath.startsWith(RELEASE_DOWNLOAD_PREFIX) &&
            url.encodedPath.removePrefix(RELEASE_DOWNLOAD_PREFIX).contains('/')

    const val DEV_TAG = "dev-latest"
    private const val RELEASE_DOWNLOAD_PREFIX = "/yaotutu/nanobotkt/releases/download/"
}

/**
 * 固定访问 yaotutu/nanobotkt 的 GitHub Release API。
 *
 * 构造函数不暴露可配置仓库或 URL，防止远端配置改变更新来源。下载阶段仍允许 GitHub
 * 官方 Release CDN 的最终重定向域名，但初始资产 URL 必须先通过严格仓库路径校验。
 */
@Singleton
class GitHubReleaseClient @Inject constructor(
    @param:RestClient private val client: OkHttpClient,
    private val json: Json,
) : GitHubReleaseService {
    /**
     * Release API 继续使用全局 20 秒总超时；APK 下载则取消整次调用的总时限，只保留
     * 单次读取超时。否则体积较大的 universal APK 在正常移动网络下也会固定于 20 秒失败。
     */
    private val downloadClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchRelease(channel: GitHubReleaseChannel): GitHubRelease = withContext(Dispatchers.IO) {
        val url = when (channel) {
            GitHubReleaseChannel.DEV -> "$API_ROOT/releases/tags/${GitHubReleaseParser.DEV_TAG}"
            GitHubReleaseChannel.RELEASE -> "$API_ROOT/releases/latest"
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NanobotKT-App-Updater")
            .build()

        execute(request) { responseCode, responseBody ->
            when (responseCode) {
                200 -> GitHubReleaseParser.parse(json, responseBody, channel)
                404 -> throw GitHubReleaseException.ReleaseNotFound()
                403, 429 -> throw GitHubReleaseException.RateLimited()
                else -> throw GitHubReleaseException.Http(responseCode)
            }
        }
    }

    override suspend fun downloadAsset(
        asset: GitHubReleaseAsset,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val initialUrl = runCatching { asset.downloadUrl.toHttpUrl() }.getOrNull()
            ?: throw GitHubReleaseException.InvalidRelease("invalid_apk_url")
        if (!isInitialAssetUrl(initialUrl)) {
            throw GitHubReleaseException.InvalidRelease("untrusted_apk_url")
        }

        destination.parentFile?.mkdirs()
        destination.delete()
        val request = Request.Builder()
            .url(initialUrl)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
            .header("User-Agent", "NanobotKT-App-Updater")
            .build()

        try {
            downloadClient.newCall(request).await().use { response ->
                when (response.code) {
                    403, 429 -> throw GitHubReleaseException.RateLimited()
                    404 -> throw GitHubReleaseException.DownloadUnavailable(response.code)
                }
                if (!response.isSuccessful) throw GitHubReleaseException.DownloadUnavailable(response.code)
                if (!isTrustedFinalDownloadUrl(response.request.url)) {
                    throw GitHubReleaseException.InvalidRelease("untrusted_apk_redirect")
                }
                val body = response.body ?: throw GitHubReleaseException.EmptyDownload()
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: asset.sizeBytes
                body.byteStream().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
                if (!destination.isFile || destination.length() <= 0L) {
                    destination.delete()
                    throw GitHubReleaseException.EmptyDownload()
                }
            }
        } catch (error: CancellationException) {
            destination.delete()
            throw error
        } catch (error: GitHubReleaseException) {
            destination.delete()
            throw error
        } catch (error: InterruptedIOException) {
            destination.delete()
            throw GitHubReleaseException.Timeout(error)
        } catch (error: IOException) {
            destination.delete()
            throw GitHubReleaseException.Network(error)
        }
    }

    private suspend fun <T> execute(request: Request, transform: (Int, String) -> T): T {
        try {
            client.newCall(request).await().use { response ->
                return transform(response.code, response.body?.string().orEmpty())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: GitHubReleaseException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw GitHubReleaseException.Timeout(error)
        } catch (error: IOException) {
            throw GitHubReleaseException.Network(error)
        }
    }

    private fun isInitialAssetUrl(url: HttpUrl): Boolean =
        url.isHttps &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.encodedPath.startsWith(RELEASE_DOWNLOAD_PREFIX)

    private fun isTrustedFinalDownloadUrl(url: HttpUrl): Boolean {
        if (!url.isHttps) return false
        if (isInitialAssetUrl(url)) return true
        // GitHub Release 下载会重定向到官方对象存储；只允许明确列出的 GitHub CDN，
        // 不接受资产 JSON 或重定向链提供的任意第三方主机。
        return url.host.lowercase() in GITHUB_ASSET_CDN_HOSTS
    }

    private companion object {
        const val API_ROOT = "https://api.github.com/repos/yaotutu/nanobotkt"
        const val RELEASE_DOWNLOAD_PREFIX = "/yaotutu/nanobotkt/releases/download/"
        val GITHUB_ASSET_CDN_HOSTS = setOf(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )
    }
}
