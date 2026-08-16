package com.nanobotkt.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val listState = rememberLazyListState()

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
    LaunchedEffect(section) {
        // Settings 首页和详情页复用同一个 LazyColumn。若不在 section 切换时重置位置，
        // 从已滚动到底部的首页进入详情页会继承旧 offset，首个分组甚至会被 TopAppBar 遮住。
        // 使用列表原生状态回到顶部，避免为每个页面额外维护一套滚动容器或自定义导航壳。
        listState.scrollToItem(0)
    }
    LaunchedEffect(viewModel) {
        viewModel.appUpdateEffects.collect { effect ->
            when (effect) {
                is AppUpdateEffect.RequestInstallPermission -> installPermissionLauncher.launch(effect.intent)
                is AppUpdateEffect.LaunchPackageInstaller -> packageInstallerLauncher.launch(effect.intent)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SettingsHeader(title = localizedSettingsSectionTitle(section), onBack = onBack)
        },
    ) { scaffoldPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Scaffold 统一处理状态栏和 TopAppBar inset；列表只负责内容边距，避免每个页面
            // 自己复制 system bar 规则并产生双重 padding。
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    top = scaffoldPadding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                    bottom = scaffoldPadding.calculateBottomPadding() + 32.dp,
                ),
        ) {
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

/** 使用 Material 3 TopAppBar 统一承载标题、返回语义和系统栏 inset。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                )
            }
        },
    )
}

/**
 * 将稳定的内部 section wire value 映射为字符串资源 ID。
 *
 * section 常量继续使用稳定英文值，避免破坏 SavedStateHandle 恢复和 app 组合根导航；映射函数只返回
 * 资源 ID，让 Compose 展示边界根据当前 Locale 读取文案。这样既保留纯函数可测试性，也避免同时维护
 * “英文标题表”和“资源标题表”两套容易漂移的实现。
 */
internal fun settingsSectionTitleResource(section: String): Int =
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

/** 在 Compose 边界读取当前 Locale 的标题；切换应用语言后会随重组立即刷新。 */
@Composable
private fun localizedSettingsSectionTitle(section: String): String =
    stringResource(settingsSectionTitleResource(section))
