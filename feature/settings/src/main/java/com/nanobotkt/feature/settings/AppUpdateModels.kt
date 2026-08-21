package com.nanobotkt.feature.settings

import android.content.Intent
import com.nanobotkt.core.network.GitHubReleaseAsset

/** App 构建渠道决定唯一允许查询的 GitHub Release 类型。 */
enum class AppReleaseChannel(val displayName: String) {
    DEV("Dev"),
    RELEASE("正式版"),
}

/**
 * 由 app 模块使用真实 BuildConfig 提供，feature 不维护任何重复版本常量。
 *
 * versionCode 虽然远端 Release 暂无对应字段，仍作为当前安装包的真实版本信息展示并保留，
 * 不能用 Release tag 反推或编造远端 versionCode。
 */
data class AppUpdateBuildInfo(
    val versionName: String,
    val versionCode: Int,
    val channel: AppReleaseChannel,
)

/** 远端版本和已验证 universal APK 的 UI/下载模型。 */
data class AppUpdateInfo(
    val versionName: String,
    val channel: AppReleaseChannel,
    val changelog: String,
    val asset: GitHubReleaseAsset,
)

/** 下载进度同时保留字节数，未知 Content-Length 时 UI 可以退化为不定进度。 */
data class AppUpdateProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { (downloadedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

enum class AppUpdateRetryAction {
    CHECK,
    DOWNLOAD,
    INSTALL,
}

/** 用户要求的更新状态全集；所有状态均由 Repository 单向推进。 */
sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data object UpToDate : AppUpdateStatus
    data class UpdateAvailable(val update: AppUpdateInfo) : AppUpdateStatus
    data class Downloading(val update: AppUpdateInfo, val progress: AppUpdateProgress) : AppUpdateStatus
    data class Downloaded(val update: AppUpdateInfo, val filePath: String) : AppUpdateStatus
    data class Installing(val update: AppUpdateInfo, val filePath: String) : AppUpdateStatus
    data class Error(
        val message: String,
        val retryAction: AppUpdateRetryAction,
        val update: AppUpdateInfo? = null,
        val filePath: String? = null,
        /**
         * 仅当 GitHub Release 元数据接口明确返回 429/403 限流时开放兜底入口。
         * 该标记不能用于普通网络错误，否则用户可能在网络不可用时反复触发无意义下载。
         */
        val canForceLatestDev: Boolean = false,
    ) : AppUpdateStatus
}

data class AppUpdateUiState(
    val current: AppUpdateBuildInfo,
    val status: AppUpdateStatus = AppUpdateStatus.Idle,
)

/** ViewModel 交给 Compose 的一次性系统 Intent；Composable 只负责调用 Activity Result API。 */
sealed interface AppUpdateEffect {
    data class RequestInstallPermission(val intent: Intent) : AppUpdateEffect
    data class LaunchPackageInstaller(val intent: Intent) : AppUpdateEffect
}

/** Repository 根据 Android 版本和未知来源权限生成的下一步安装动作。 */
sealed interface AppUpdateInstallRequest {
    data class RequestPermission(val intent: Intent) : AppUpdateInstallRequest
    data class LaunchInstaller(val intent: Intent) : AppUpdateInstallRequest
}

/**
 * 严格的三段数字版本，避免字符串排序把 0.1.10 判断为小于 0.1.9。
 * 后缀只用于识别构建类型，不参与同渠道的数字版本先后顺序。
 */
internal data class NumericAppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<NumericAppVersion> {
    override fun compareTo(other: NumericAppVersion): Int =
        compareValuesBy(this, other, NumericAppVersion::major, NumericAppVersion::minor, NumericAppVersion::patch)

    companion object {
        private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-[0-9A-Za-z.-]+)?$")

        fun parse(value: String): NumericAppVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return NumericAppVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

/** 正式版与 Dev 版严格同渠道比较，杜绝正式版被 dev-latest 或低版本 Dev 覆盖。 */
internal fun isAppUpdateAvailable(
    currentVersionName: String,
    currentChannel: AppReleaseChannel,
    remoteVersionName: String,
    remoteChannel: AppReleaseChannel,
): Boolean {
    if (currentChannel != remoteChannel) return false
    val current = NumericAppVersion.parse(currentVersionName) ?: return false
    val remote = NumericAppVersion.parse(remoteVersionName) ?: return false
    return remote > current
}
