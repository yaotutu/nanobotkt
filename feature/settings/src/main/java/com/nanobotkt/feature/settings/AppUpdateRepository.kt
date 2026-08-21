package com.nanobotkt.feature.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nanobotkt.core.network.GitHubReleaseAsset
import com.nanobotkt.core.network.GitHubReleaseChannel
import com.nanobotkt.core.network.GitHubReleaseException
import com.nanobotkt.core.network.GitHubReleaseService
import com.nanobotkt.core.persistence.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AppUpdateRepository {
    val state: StateFlow<AppUpdateUiState>

    suspend fun check(manual: Boolean)
    suspend fun download()
    suspend fun forceDownloadLatestDev()
    suspend fun requestInstall(): AppUpdateInstallRequest?
    fun onInstallerReturned()
}

/** 自动检查时间的最小持久化边界，测试无需构造 Android DataStore。 */
interface AppUpdateCheckStore {
    suspend fun readLastCheckAtMillis(): Long?
    suspend fun writeLastCheckAtMillis(value: Long)
}

@Singleton
class DataStoreAppUpdateCheckStore @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : AppUpdateCheckStore {
    override suspend fun readLastCheckAtMillis(): Long? = preferences.readLastAppUpdateCheckAtMillis()

    override suspend fun writeLastCheckAtMillis(value: Long) {
        preferences.writeLastAppUpdateCheckAtMillis(value)
    }
}

/** 时间来源独立出来，只为稳定验证“一天一次”的边界，不引入后台调度框架。 */
interface AppUpdateTimeSource {
    fun nowMillis(): Long
}

@Singleton
class SystemAppUpdateTimeSource @Inject constructor() : AppUpdateTimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** APK 缓存文件生命周期边界。临时文件和旧 APK 都只存在于应用缓存目录。 */
interface AppUpdateStorage {
    fun preparePartialFile(): File
    fun finishDownload(partialFile: File): File
    fun requireReadableFile(path: String): File?
}

@Singleton
class CacheAppUpdateStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppUpdateStorage {
    private val updateDirectory: File
        get() = File(context.externalCacheDir ?: context.cacheDir, UPDATE_DIRECTORY)

    override fun preparePartialFile(): File {
        val directory = updateDirectory.apply { mkdirs() }
        // 下一次下载前统一清理旧包和中断残留，既不污染仓库，也避免缓存无限增长。
        directory.listFiles()?.forEach(File::delete)
        return File(directory, PARTIAL_FILE_NAME)
    }

    override fun finishDownload(partialFile: File): File {
        val completed = File(updateDirectory, APK_FILE_NAME)
        completed.delete()
        if (!partialFile.renameTo(completed)) {
            partialFile.copyTo(completed, overwrite = true)
            partialFile.delete()
        }
        return completed.takeIf { it.isFile && it.length() > 0L }
            ?: throw GitHubReleaseException.EmptyDownload()
    }

    override fun requireReadableFile(path: String): File? =
        File(path).takeIf { it.isFile && it.length() > 0L }

    private companion object {
        const val UPDATE_DIRECTORY = "app-updates"
        const val PARTIAL_FILE_NAME = "nanobotkt-update.apk.part"
        const val APK_FILE_NAME = "nanobotkt-update.apk"
    }
}

/** Android 未知来源权限与 FileProvider Intent 的兼容封装，不执行静默安装。 */
interface AppUpdateInstallCoordinator {
    fun canInstallPackages(): Boolean
    fun permissionIntent(): Intent
    fun installerIntent(apk: File): Intent
}

@Singleton
class AndroidAppUpdateInstallCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppUpdateInstallCoordinator {
    override fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun permissionIntent(): Intent {
        val packagePermission = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        // 部分定制系统没有单应用未知来源设置页；退化到安全设置页仍由用户手动授权，
        // 不尝试绕过系统限制。Intent 最终由 Activity Result launcher 启动，因此不能加 NEW_TASK。
        return packagePermission.takeIf(::canResolve)
            ?: Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

    override fun installerIntent(apk: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, APK_MIME_TYPE)
            .apply {
                // ClipData 可让部分系统安装器正确继承 content URI 的临时读取权限。
                clipData = ClipData.newRawUri("NanobotKT update", contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        require(canResolve(intent)) { "package_installer_unavailable" }
        return intent
    }

    private fun canResolve(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

@Singleton
class DefaultAppUpdateRepository @Inject constructor(
    private val releaseService: GitHubReleaseService,
    private val checkStore: AppUpdateCheckStore,
    private val storage: AppUpdateStorage,
    private val timeSource: AppUpdateTimeSource,
    private val installer: AppUpdateInstallCoordinator,
    private val buildInfo: AppUpdateBuildInfo,
) : AppUpdateRepository {
    private val actionMutex = Mutex()
    private val mutable = MutableStateFlow(AppUpdateUiState(current = buildInfo))
    override val state: StateFlow<AppUpdateUiState> = mutable.asStateFlow()

    override suspend fun check(manual: Boolean) = actionMutex.withLock {
        if (mutable.value.status is AppUpdateStatus.Checking ||
            mutable.value.status is AppUpdateStatus.Downloading ||
            mutable.value.status is AppUpdateStatus.Downloaded ||
            mutable.value.status is AppUpdateStatus.Installing
        ) {
            return@withLock
        }

        val now = timeSource.nowMillis()
        if (!manual) {
            val lastCheck = runCatching { checkStore.readLastCheckAtMillis() }.getOrNull()
            if (lastCheck != null && (now <= lastCheck || now - lastCheck < AUTO_CHECK_INTERVAL_MILLIS)) {
                return@withLock
            }
        }

        val previousStable = mutable.value.status
        // 无论手动还是自动检查，都把本次尝试计入节流；持久化失败不应阻止真实检查。
        runCatching { checkStore.writeLastCheckAtMillis(now) }
        mutable.value = mutable.value.copy(status = AppUpdateStatus.Checking)
        try {
            val remoteChannel = buildInfo.channel.toGitHubChannel()
            val release = releaseService.fetchRelease(remoteChannel)
            val update = AppUpdateInfo(
                versionName = release.versionName.withChannelSuffix(buildInfo.channel),
                channel = buildInfo.channel,
                changelog = release.body,
                asset = release.asset,
            )
            mutable.value = mutable.value.copy(
                status = if (
                    isAppUpdateAvailable(
                        currentVersionName = buildInfo.versionName,
                        currentChannel = buildInfo.channel,
                        remoteVersionName = update.versionName,
                        remoteChannel = update.channel,
                    )
                ) {
                    AppUpdateStatus.UpdateAvailable(update)
                } else {
                    AppUpdateStatus.UpToDate
                },
            )
        } catch (error: CancellationException) {
            mutable.value = mutable.value.copy(status = previousStable)
            throw error
        } catch (error: Exception) {
            // 自动检查失败只恢复原状态，不弹窗、不覆盖已有“发现新版本”提示。
            mutable.value = mutable.value.copy(
                status = if (manual) {
                    AppUpdateStatus.Error(
                        message = error.toUserMessage(checking = true),
                        retryAction = AppUpdateRetryAction.CHECK,
                        // 只有 API 限流可以被固定 dev-latest 下载地址绕过；其他失败继续走正常重试。
                        canForceLatestDev = error is GitHubReleaseException.RateLimited,
                    )
                } else {
                    previousStable
                },
            )
        }
    }

    override suspend fun download() = actionMutex.withLock {
        val update = when (val status = mutable.value.status) {
            is AppUpdateStatus.UpdateAvailable -> status.update
            is AppUpdateStatus.Error -> status.update?.takeIf {
                status.retryAction == AppUpdateRetryAction.DOWNLOAD
            }
            else -> null
        } ?: return@withLock
        downloadLocked(update)
    }

    /**
     * GitHub API 被限流时，不再继续请求 Release JSON，而是直接下载滚动标签 dev-latest 下
     * 名称固定的 universal APK。该 URL 仍会经过 core:network 的仓库、HTTPS 和重定向域名校验，
     * 因此“强制”只跳过版本元数据比较，不会放宽 APK 来源，也不会尝试静默安装。
     */
    override suspend fun forceDownloadLatestDev() = actionMutex.withLock {
        val error = mutable.value.status as? AppUpdateStatus.Error ?: return@withLock
        if (!error.canForceLatestDev) return@withLock
        downloadLocked(latestDevFallbackUpdate())
    }

    /** actionMutex 已由调用方持有；下载、落盘和状态推进必须作为一个不可并发动作执行。 */
    private suspend fun downloadLocked(update: AppUpdateInfo) {
        val partialFile = storage.preparePartialFile()
        mutable.value = mutable.value.copy(
            status = AppUpdateStatus.Downloading(
                update = update,
                progress = AppUpdateProgress(downloadedBytes = 0L, totalBytes = update.asset.sizeBytes),
            ),
        )
        try {
            releaseService.downloadAsset(update.asset, partialFile) { downloaded, total ->
                // OkHttp 回调运行在 IO 线程，StateFlow 本身是线程安全的；只在仍下载同一版本时推进进度，
                // 避免取消或错误后的迟到回调覆盖终态。
                val current = mutable.value.status
                if (current is AppUpdateStatus.Downloading && current.update == update) {
                    mutable.value = mutable.value.copy(
                        status = current.copy(
                            progress = AppUpdateProgress(downloadedBytes = downloaded, totalBytes = total),
                        ),
                    )
                }
            }
            val completed = storage.finishDownload(partialFile)
            mutable.value = mutable.value.copy(
                status = AppUpdateStatus.Downloaded(update = update, filePath = completed.absolutePath),
            )
        } catch (error: CancellationException) {
            partialFile.delete()
            mutable.value = mutable.value.copy(status = AppUpdateStatus.UpdateAvailable(update))
            throw error
        } catch (error: Exception) {
            partialFile.delete()
            mutable.value = mutable.value.copy(
                status = AppUpdateStatus.Error(
                    message = error.toUserMessage(checking = false),
                    retryAction = AppUpdateRetryAction.DOWNLOAD,
                    update = update,
                ),
            )
        }
    }

    /**
     * dev-latest 是发布流程维护的唯一滚动 Dev 标签；固定资产名由 CI 生成。
     * 元数据不可用时无法可信获知实际版本号，因此明确展示标签名，禁止猜测远端版本。
     */
    private fun latestDevFallbackUpdate(): AppUpdateInfo = AppUpdateInfo(
        versionName = FORCED_DEV_VERSION_NAME,
        channel = AppReleaseChannel.DEV,
        changelog = "GitHub 版本接口受到限流，已改为直接下载 dev-latest 的 universal APK。",
        asset = GitHubReleaseAsset(
            name = FORCED_DEV_ASSET_NAME,
            downloadUrl = FORCED_DEV_DOWNLOAD_URL,
            sizeBytes = null,
        ),
    )

    override suspend fun requestInstall(): AppUpdateInstallRequest? = actionMutex.withLock {
        val (update, filePath) = when (val status = mutable.value.status) {
            is AppUpdateStatus.Downloaded -> status.update to status.filePath
            is AppUpdateStatus.Error -> {
                if (status.retryAction != AppUpdateRetryAction.INSTALL || status.update == null || status.filePath == null) {
                    return@withLock null
                }
                status.update to status.filePath
            }
            else -> return@withLock null
        }
        val apk = storage.requireReadableFile(filePath)
        if (apk == null) {
            mutable.value = mutable.value.copy(
                status = AppUpdateStatus.Error(
                    message = "已下载的安装包不存在，请重新下载。",
                    retryAction = AppUpdateRetryAction.DOWNLOAD,
                    update = update,
                ),
            )
            return@withLock null
        }

        if (!installer.canInstallPackages()) {
            return@withLock AppUpdateInstallRequest.RequestPermission(installer.permissionIntent())
        }

        return@withLock try {
            val intent = installer.installerIntent(apk)
            mutable.value = mutable.value.copy(
                status = AppUpdateStatus.Installing(update = update, filePath = apk.absolutePath),
            )
            AppUpdateInstallRequest.LaunchInstaller(intent)
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(
                status = AppUpdateStatus.Error(
                    message = "无法打开系统安装器，请重试。",
                    retryAction = AppUpdateRetryAction.INSTALL,
                    update = update,
                    filePath = apk.absolutePath,
                ),
            )
            null
        }
    }

    override fun onInstallerReturned() {
        val status = mutable.value.status
        if (status is AppUpdateStatus.Installing) {
            // 安装器返回不等于安装成功；若用户取消，恢复 Downloaded 允许再次确认安装。
            mutable.value = mutable.value.copy(
                status = AppUpdateStatus.Downloaded(status.update, status.filePath),
            )
        }
    }

    private fun AppReleaseChannel.toGitHubChannel(): GitHubReleaseChannel = when (this) {
        AppReleaseChannel.DEV -> GitHubReleaseChannel.DEV
        AppReleaseChannel.RELEASE -> GitHubReleaseChannel.RELEASE
    }

    private fun String.withChannelSuffix(channel: AppReleaseChannel): String = when (channel) {
        AppReleaseChannel.DEV -> "${this}-dev"
        AppReleaseChannel.RELEASE -> this
    }

    private fun Exception.toUserMessage(checking: Boolean): String = when (this) {
        is GitHubReleaseException.ReleaseNotFound -> "未找到当前版本类型对应的 Release，请稍后重试。"
        is GitHubReleaseException.RateLimited -> "GitHub 请求过于频繁，请稍后重试。"
        is GitHubReleaseException.Timeout -> "连接 GitHub 超时，请检查网络后重试。"
        is GitHubReleaseException.Network -> "无法连接网络，请检查网络后重试。"
        is GitHubReleaseException.ApkNotFound -> "Release 中找不到 universal APK。"
        is GitHubReleaseException.InvalidRelease -> "发布信息不完整，暂时无法更新。"
        is GitHubReleaseException.DownloadUnavailable,
        is GitHubReleaseException.EmptyDownload -> "APK 下载失败，请重试。"
        is GitHubReleaseException.Http -> "GitHub API 请求失败，请稍后重试。"
        else -> if (checking) "检查更新失败，请重试。" else "APK 下载失败，请重试。"
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
        const val FORCED_DEV_VERSION_NAME = "dev-latest"
        const val FORCED_DEV_ASSET_NAME = "app-universal-dev.apk"
        const val FORCED_DEV_DOWNLOAD_URL =
            "https://github.com/yaotutu/nanobotkt/releases/download/dev-latest/app-universal-dev.apk"
    }
}
