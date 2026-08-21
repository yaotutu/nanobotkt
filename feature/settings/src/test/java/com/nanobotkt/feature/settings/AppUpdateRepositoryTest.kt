package com.nanobotkt.feature.settings

import android.content.Intent
import com.nanobotkt.core.network.GitHubRelease
import com.nanobotkt.core.network.GitHubReleaseAsset
import com.nanobotkt.core.network.GitHubReleaseChannel
import com.nanobotkt.core.network.GitHubReleaseException
import com.nanobotkt.core.network.GitHubReleaseService
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun `manual check exposes checking before up to date`() = runBlocking {
        val releaseGate = CompletableDeferred<Unit>()
        val service = FakeReleaseService().apply {
            fetch = {
                releaseGate.await()
                release(version = "0.1.5")
            }
        }
        val repository = repository(service = service)

        val check = async { repository.check(manual = true) }
        yield()
        assertSame(AppUpdateStatus.Checking, repository.state.value.status)

        releaseGate.complete(Unit)
        check.await()
        assertSame(AppUpdateStatus.UpToDate, repository.state.value.status)
    }

    @Test
    fun `newer same channel release becomes update available`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { release(version = "0.1.6") }
        }
        val repository = repository(service = service)

        repository.check(manual = true)

        val status = repository.state.value.status
        assertTrue(status is AppUpdateStatus.UpdateAvailable)
        assertEquals("0.1.6", (status as AppUpdateStatus.UpdateAvailable).update.versionName)
    }

    @Test
    fun `request failure becomes retryable check error`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { throw GitHubReleaseException.RateLimited() }
        }
        val repository = repository(service = service)

        repository.check(manual = true)

        val status = repository.state.value.status
        assertTrue(status is AppUpdateStatus.Error)
        status as AppUpdateStatus.Error
        assertEquals(AppUpdateRetryAction.CHECK, status.retryAction)
        assertTrue(status.message.contains("GitHub"))
        assertTrue(status.canForceLatestDev)
    }

    @Test
    fun `ordinary network failure does not expose forced dev download`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { throw GitHubReleaseException.Network(IOException("offline")) }
        }
        val repository = repository(service = service)

        repository.check(manual = true)

        val status = repository.state.value.status
        assertTrue(status is AppUpdateStatus.Error)
        status as AppUpdateStatus.Error
        // 普通断网时固定 Dev 地址同样不可达，因此不能把所有检查错误都伪装成可强制更新。
        assertEquals(AppUpdateRetryAction.CHECK, status.retryAction)
        assertEquals(false, status.canForceLatestDev)
    }

    @Test
    fun `rate limited check can directly download rolling dev asset without another metadata request`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { throw GitHubReleaseException.RateLimited() }
        }
        val repository = repository(service = service)
        repository.check(manual = true)

        repository.forceDownloadLatestDev()

        val status = repository.state.value.status
        assertTrue(status is AppUpdateStatus.Downloaded)
        status as AppUpdateStatus.Downloaded
        assertEquals(1, service.fetchCount)
        assertEquals(1, service.downloadCount)
        assertEquals("dev-latest", status.update.versionName)
        assertEquals(AppReleaseChannel.DEV, status.update.channel)
        assertEquals("app-universal-dev.apk", service.downloadedAsset?.name)
        assertEquals(FORCED_DEV_DOWNLOAD_URL, service.downloadedAsset?.downloadUrl)
    }

    @Test
    fun `forced dev download is ignored outside a rate limited check error`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { throw GitHubReleaseException.Network(IOException("offline")) }
        }
        val repository = repository(service = service)
        repository.check(manual = true)
        val previousStatus = repository.state.value.status

        repository.forceDownloadLatestDev()

        // Repository 自身再次校验限流标记，避免未来其他 UI 入口误调用时绕过正常版本检查。
        assertSame(previousStatus, repository.state.value.status)
        assertEquals(0, service.downloadCount)
    }

    @Test
    fun `forced dev download failure preserves rolling asset for normal download retry`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { throw GitHubReleaseException.RateLimited() }
            download = { _, _, _ ->
                throw GitHubReleaseException.Network(IOException("offline"))
            }
        }
        val repository = repository(service = service)
        repository.check(manual = true)

        repository.forceDownloadLatestDev()

        val status = repository.state.value.status
        assertTrue(status is AppUpdateStatus.Error)
        status as AppUpdateStatus.Error
        assertEquals(AppUpdateRetryAction.DOWNLOAD, status.retryAction)
        assertEquals("dev-latest", status.update?.versionName)
        assertEquals(AppReleaseChannel.DEV, status.update?.channel)
        assertEquals("app-universal-dev.apk", status.update?.asset?.name)
    }

    @Test
    fun `download failure preserves update and becomes retryable download error`() = runBlocking {
        val service = FakeReleaseService().apply {
            fetch = { release(version = "0.1.6") }
            download = { _, _, _ ->
                throw GitHubReleaseException.Network(IOException("offline"))
            }
        }
        val repository = repository(service = service)
        repository.check(manual = true)

        repository.download()

        val status = repository.state.value.status
        assertTrue(status is AppUpdateStatus.Error)
        status as AppUpdateStatus.Error
        assertEquals(AppUpdateRetryAction.DOWNLOAD, status.retryAction)
        assertEquals("0.1.6", status.update?.versionName)
        assertTrue(status.message.contains("网络"))
    }

    @Test
    fun `automatic check is throttled for twenty four hours even after silent failure`() = runBlocking {
        val store = FakeCheckStore()
        val service = FakeReleaseService().apply {
            fetch = { throw GitHubReleaseException.Network(IOException("offline")) }
        }
        val repository = repository(service = service, checkStore = store)

        repository.check(manual = false)
        repository.check(manual = false)

        // 自动失败不覆盖正常页面状态，也不会在同一天反复请求 GitHub。
        assertSame(AppUpdateStatus.Idle, repository.state.value.status)
        assertEquals(1, service.fetchCount)
        assertEquals(NOW_MILLIS, store.lastCheckAtMillis)
    }

    private fun repository(
        service: FakeReleaseService,
        checkStore: FakeCheckStore = FakeCheckStore(),
    ): DefaultAppUpdateRepository = DefaultAppUpdateRepository(
        releaseService = service,
        checkStore = checkStore,
        storage = TemporaryStorage(),
        timeSource = object : AppUpdateTimeSource {
            override fun nowMillis(): Long = NOW_MILLIS
        },
        installer = object : AppUpdateInstallCoordinator {
            override fun canInstallPackages(): Boolean = true
            override fun permissionIntent(): Intent = error("本测试不进入安装权限流程")
            override fun installerIntent(apk: File): Intent = error("本测试不启动系统安装器")
        },
        buildInfo = AppUpdateBuildInfo(
            versionName = "0.1.5",
            versionCode = 6,
            channel = AppReleaseChannel.RELEASE,
        ),
    )

    private class FakeReleaseService : GitHubReleaseService {
        var fetchCount: Int = 0
        var downloadCount: Int = 0
        var downloadedAsset: GitHubReleaseAsset? = null
        var fetch: suspend (GitHubReleaseChannel) -> GitHubRelease = { release("0.1.5") }
        var download: suspend (GitHubReleaseAsset, File, (Long, Long?) -> Unit) -> Unit =
            { asset, destination, onProgress ->
                destination.parentFile?.mkdirs()
                destination.writeBytes(byteArrayOf(1, 2, 3))
                onProgress(destination.length(), asset.sizeBytes)
            }

        override suspend fun fetchRelease(channel: GitHubReleaseChannel): GitHubRelease {
            fetchCount += 1
            return fetch(channel)
        }

        override suspend fun downloadAsset(
            asset: GitHubReleaseAsset,
            destination: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        ) {
            // 记录真实交给网络层的资产，测试才能证明强制路径没有再次依赖 Release 元数据。
            downloadCount += 1
            downloadedAsset = asset
            download(asset, destination, onProgress)
        }
    }

    private class FakeCheckStore : AppUpdateCheckStore {
        var lastCheckAtMillis: Long? = null

        override suspend fun readLastCheckAtMillis(): Long? = lastCheckAtMillis

        override suspend fun writeLastCheckAtMillis(value: Long) {
            lastCheckAtMillis = value
        }
    }

    private class TemporaryStorage : AppUpdateStorage {
        private val directory = Files.createTempDirectory("nanobotkt-app-update-test").toFile()

        override fun preparePartialFile(): File = File(directory, "update.apk.part").also {
            directory.listFiles()?.forEach(File::delete)
        }

        override fun finishDownload(partialFile: File): File {
            val completed = File(directory, "update.apk")
            partialFile.copyTo(completed, overwrite = true)
            return completed
        }

        override fun requireReadableFile(path: String): File? =
            File(path).takeIf { it.isFile && it.length() > 0L }
    }

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
        const val FORCED_DEV_DOWNLOAD_URL =
            "https://github.com/yaotutu/nanobotkt/releases/download/dev-latest/app-universal-dev.apk"

        fun release(version: String): GitHubRelease = GitHubRelease(
            tagName = "v$version",
            releaseName = "NanobotKT $version",
            body = "# NanobotKT $version",
            prerelease = false,
            versionName = version,
            asset = GitHubReleaseAsset(
                name = "app-universal-release.apk",
                downloadUrl = "https://github.com/yaotutu/nanobotkt/releases/download/v$version/app-universal-release.apk",
                sizeBytes = 3L,
            ),
        )
    }
}
