package com.nanobotkt.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 保留旧 Overview wire value，确保已保存的 Root 状态升级后仍能恢复到新的 Settings Home。 */
const val SETTINGS_SECTION_OVERVIEW = "Overview"
const val SETTINGS_SECTION_MODELS = "Models"
internal const val SETTINGS_SECTION_APPEARANCE = "Appearance"
internal const val SETTINGS_SECTION_IMAGE = "Image"
internal const val SETTINGS_SECTION_VOICE = "Voice"
internal const val SETTINGS_SECTION_WEB = "Web"
const val SETTINGS_SECTION_SYSTEM = "System"
internal const val SETTINGS_SECTION_SECURITY = "Security"

/** Settings 内部详情白名单；独立 Apps、Skills 等页面仍由 app 组合根负责。 */
internal val settingsSections =
    setOf(
        SETTINGS_SECTION_OVERVIEW,
        SETTINGS_SECTION_APPEARANCE,
        SETTINGS_SECTION_MODELS,
        SETTINGS_SECTION_IMAGE,
        SETTINGS_SECTION_VOICE,
        SETTINGS_SECTION_WEB,
        SETTINGS_SECTION_SYSTEM,
        SETTINGS_SECTION_SECURITY,
    )

/**
 * Gateway 当前接受的图片宽高比和尺寸枚举。
 *
 * 这些值属于 ImageGenerationPage 的接口边界而不是首页导航状态；统一设置页重构只移动入口，
 * 不能删除原有表单依赖的合法值集合，否则已保存值将无法继续编辑。
 */
internal val IMAGE_ASPECT_RATIOS = listOf("1:1", "3:4", "9:16", "4:3", "16:9", "3:2", "2:3", "21:9")
internal val IMAGE_SIZES = listOf("1K", "2K", "4K", "1024x1024", "1536x1024", "1024x1536")

internal fun SettingsUiState.restartPendingFor(vararg sections: String): Boolean {
    val settings = payload ?: return false
    if (!settings.requiresRestart) return false
    val required = settings.restartRequiredSections.orEmpty().map(String::lowercase)
    return required.isEmpty() || sections.any { it.lowercase() in required }
}

internal val settingsDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
internal val PageBackground: Color
    @Composable get() = if (settingsDark) Color(0xFF303030) else Color.White
internal val CardBackground: Color
    @Composable get() = if (settingsDark) Color(0xFF383838) else Color(0xFFF7F7F6)
internal val SegmentBackground: Color
    @Composable get() = if (settingsDark) Color(0xFF303030) else Color(0xFFF0F0EF)
internal val PrimaryText: Color
    @Composable get() = if (settingsDark) Color(0xFFF5F5F6) else Color(0xFF1D1D1F)
internal val SecondaryText: Color
    @Composable get() = if (settingsDark) Color(0xFFA6A6A6) else Color(0xFF737373)
internal val DividerColor: Color
    @Composable get() = if (settingsDark) Color(0xFF474747) else Color(0xFFE8E7E5)

/**
 * Settings 统一控制中心。
 *
 * Settings feature 只渲染首页和自身拥有的配置页面；Apps、Skills、Automations 等兄弟
 * feature 通过事件交给 app 组合根打开，避免形成 feature 之间的反向依赖。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenSecurityAndPairing: () -> Unit,
    onLogout: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onReconfigureGateway: (serverUrl: String, bootstrapSecret: String) -> Unit = { _, _ -> },
    gatewayReconfigurationInProgress: Boolean = false,
    gatewayReconfigurationError: String? = null,
    gatewayReconfigurationSuccessGeneration: Long = 0L,
    connectionStatus: String = "Unknown",
    gatewayEndpoint: String = "",
    initialSection: String = SETTINGS_SECTION_OVERVIEW,
    onOpenSection: (String) -> Unit = {},
    onSectionChange: (String) -> Unit = {},
    refreshKey: Long = 0L,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val appUpdateDialogVisible by viewModel.appUpdateDialogVisible.collectAsStateWithLifecycle()
    val section = initialSection.takeIf(settingsSections::contains) ?: SETTINGS_SECTION_OVERVIEW

    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // 系统设置页没有可靠的 resultCode；返回后必须重新查询 PackageManager 的真实授权状态。
        viewModel.onInstallPermissionReturned()
    }
    val packageInstallerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // 安装器返回可能是完成、失败或用户取消，不能在客户端把 resultCode 当成安装成功凭据。
        viewModel.onPackageInstallerReturned()
    }

    LaunchedEffect(refreshKey) {
        viewModel.refresh()
        viewModel.autoCheckAppUpdate()
    }
    LaunchedEffect(viewModel) {
        viewModel.appUpdateEffects.collect { effect ->
            when (effect) {
                is AppUpdateEffect.RequestInstallPermission -> installPermissionLauncher.launch(effect.intent)
                is AppUpdateEffect.LaunchPackageInstaller -> packageInstallerLauncher.launch(effect.intent)
            }
        }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PageBackground)
                // Settings 顶栏和滚动内容共享同一列表；对整个滚动视口应用状态栏安全区，
                // 可避免标题滚出屏幕后，下一行设置项继续绘制到系统时间和图标下方。
                .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item { SettingsHeader(title = localizedSettingsSectionTitle(section), onBack = onBack) }
        item {
            when (section) {
                SETTINGS_SECTION_OVERVIEW ->
                    SettingsHomePage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        connectionStatus = connectionStatus,
                        gatewayEndpoint = gatewayEndpoint,
                        onReconnect = onReconnect,
                        onManageGateway = { onOpenSection(SETTINGS_SECTION_SYSTEM) },
                        onOpenSection = onOpenSection,
                        onOpenApps = onOpenApps,
                        onOpenSkills = onOpenSkills,
                        onOpenAutomations = onOpenAutomations,
                        onOpenChannels = onOpenChannels,
                        onOpenWorkspaces = onOpenWorkspaces,
                        onOpenSecurityAndPairing = onOpenSecurityAndPairing,
                        onCheckVersion = viewModel::checkVersion,
                        appUpdateState = appUpdateState,
                        onOpenAppUpdate = viewModel::openAppUpdate,
                        onLogout = onLogout,
                    )
                SETTINGS_SECTION_APPEARANCE -> AppearancePage(appearance, viewModel)
                SETTINGS_SECTION_MODELS -> ModelsPage(state, viewModel, appearance.showBrandLogos)
                SETTINGS_SECTION_IMAGE ->
                    ImageGenerationPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onOpenProviders = { onSectionChange(SETTINGS_SECTION_MODELS) },
                        onSave = viewModel::updateImage,
                    )
                SETTINGS_SECTION_VOICE ->
                    TranscriptionPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onOpenProviders = { onSectionChange(SETTINGS_SECTION_MODELS) },
                        onSave = viewModel::updateTranscription,
                    )
                SETTINGS_SECTION_WEB ->
                    WebSearchPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onSave = viewModel::updateWebSearch,
                    )
                SETTINGS_SECTION_SYSTEM -> SystemPage(
                    state = state,
                    viewModel = viewModel,
                    connectionStatus = connectionStatus,
                    gatewayEndpoint = gatewayEndpoint,
                    onReconnect = onReconnect,
                    onReconfigureGateway = onReconfigureGateway,
                    reconfigurationInProgress = gatewayReconfigurationInProgress,
                    reconfigurationError = gatewayReconfigurationError,
                    reconfigurationSuccessGeneration = gatewayReconfigurationSuccessGeneration,
                )
                SETTINGS_SECTION_SECURITY -> SecurityPage(state, viewModel)
            }
        }
    }

    if (appUpdateDialogVisible) {
        AppUpdateDialog(
            state = appUpdateState,
            onDismiss = viewModel::dismissAppUpdateDialog,
            onCheck = viewModel::checkAppUpdate,
            onDownload = viewModel::downloadAppUpdate,
            onInstall = viewModel::installAppUpdate,
            onRetry = viewModel::retryAppUpdate,
        )
    }
}

/** 统一 Settings 顶栏；不再用下拉菜单承载整棵信息架构。 */
@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = PrimaryText,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            color = PrimaryText,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * 将稳定的内部 section wire value 映射为当前应用 Locale 下的标题。
 *
 * section 常量仍保持英文 wire value，避免破坏 SavedStateHandle 恢复和 app 组合根导航；
 * 只有进入 Compose 展示边界后才读取字符串资源，因此切换应用语言会触发重组并立即刷新标题。
 */
@Composable
private fun localizedSettingsSectionTitle(section: String): String =
    stringResource(
        when (section) {
            SETTINGS_SECTION_OVERVIEW -> R.string.settings_title
            SETTINGS_SECTION_APPEARANCE -> R.string.settings_appearance
            SETTINGS_SECTION_MODELS -> R.string.settings_models_providers
            SETTINGS_SECTION_IMAGE -> R.string.settings_image_generation
            SETTINGS_SECTION_VOICE -> R.string.settings_voice
            SETTINGS_SECTION_WEB -> R.string.settings_web_search
            SETTINGS_SECTION_SYSTEM -> R.string.settings_gateway_system
            SETTINGS_SECTION_SECURITY -> R.string.settings_app_safety
            else -> R.string.settings_title
        }
    )

internal fun settingsSectionTitle(section: String): String =
    when (section) {
        SETTINGS_SECTION_OVERVIEW -> "Settings"
        SETTINGS_SECTION_APPEARANCE -> "Appearance"
        SETTINGS_SECTION_MODELS -> "Models & Providers"
        SETTINGS_SECTION_IMAGE -> "Image generation"
        SETTINGS_SECTION_VOICE -> "Voice"
        SETTINGS_SECTION_WEB -> "Web search"
        SETTINGS_SECTION_SYSTEM -> "Gateway & System"
        SETTINGS_SECTION_SECURITY -> "App safety"
        else -> "Settings"
    }
