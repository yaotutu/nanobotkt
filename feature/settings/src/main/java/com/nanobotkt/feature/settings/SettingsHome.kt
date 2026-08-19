package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotNavigationRow
import com.nanobotkt.core.designsystem.NanobotStatusLabel
import com.nanobotkt.core.designsystem.NanobotStatusTone
import com.nanobotkt.core.designsystem.NanobotSummarySurface

/**
 * Settings Home 只承担状态摘要和分组导航，不直接复制各详情页的复杂表单。
 *
 * 这种边界让用户先建立“偏好、能力、集成、系统”的稳定心智模型，也避免首页随着
 * Provider 或 Gateway 表单增长而再次变成不可扫描的长表单。
 */
@Composable
internal fun SettingsHomePage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    connectionStatus: String,
    gatewayEndpoint: String,
    onReconnect: () -> Unit,
    onManageGateway: () -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenApps: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenSecurityAndPairing: () -> Unit,
    onCheckVersion: () -> Unit,
    appUpdateState: AppUpdateUiState,
    onOpenAppUpdate: () -> Unit,
    onLogout: () -> Unit,
) {
    GatewaySummaryCard(
        state = state,
        showBrandLogos = showBrandLogos,
        connectionStatus = connectionStatus,
        gatewayEndpoint = gatewayEndpoint,
        onReconnect = onReconnect,
        onManage = onManageGateway,
        onOpenModels = { onOpenSection(SETTINGS_SECTION_MODELS) },
    )

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_group_general)) {
        SettingsRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.settings_appearance),
            subtitle = stringResource(R.string.settings_appearance_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_APPEARANCE) },
        )
    }

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_group_ai_capabilities)) {
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.settings_models_providers),
            subtitle = stringResource(R.string.settings_models_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_MODELS) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.settings_apps),
            subtitle = stringResource(R.string.settings_apps_summary),
            onClick = onOpenApps,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(R.string.settings_skills),
            subtitle = stringResource(R.string.settings_skills_summary),
            onClick = onOpenSkills,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = stringResource(R.string.settings_image_generation),
            subtitle = stringResource(R.string.settings_image_generation_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_IMAGE) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.MicNone,
            title = stringResource(R.string.settings_voice),
            subtitle = stringResource(R.string.settings_voice_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_VOICE) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.settings_web_search),
            subtitle = stringResource(R.string.settings_web_search_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_WEB) },
        )
    }

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_group_integrations_automation)) {
        SettingsRow(
            icon = Icons.Outlined.Sync,
            title = stringResource(R.string.settings_channels),
            subtitle = stringResource(R.string.settings_channels_summary),
            onClick = onOpenChannels,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Schedule,
            title = stringResource(R.string.settings_automations),
            subtitle = stringResource(R.string.settings_automations_summary),
            onClick = onOpenAutomations,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.settings_workspaces),
            subtitle = stringResource(R.string.settings_workspaces_summary),
            onClick = onOpenWorkspaces,
        )
    }

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_group_system_security)) {
        SettingsRow(
            icon = Icons.Outlined.Api,
            title = stringResource(R.string.settings_gateway_system),
            subtitle = stringResource(R.string.settings_gateway_system_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_SYSTEM) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.SettingsSuggest,
            title = stringResource(R.string.settings_app_safety),
            subtitle = stringResource(R.string.settings_app_safety_summary),
            onClick = { onOpenSection(SETTINGS_SECTION_SECURITY) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Security,
            title = stringResource(R.string.settings_security_pairing),
            subtitle = stringResource(R.string.settings_security_pairing_summary),
            onClick = onOpenSecurityAndPairing,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.settings_about),
            subtitle = stringResource(R.string.settings_about_summary),
            value = state.payload?.version?.let { versions ->
                versions["current"] ?: versions.values.firstOrNull()
            },
            onClick = onCheckVersion,
        )
        CardDivider()
        // App 更新是客户端自身能力，必须与既有 Gateway 版本检查分开，避免新增入口时
        // 意外移除服务端版本信息和更新检查功能。
        SettingsRow(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.settings_check_app_update),
            subtitle =
                stringResource(
                    R.string.settings_current_app_version,
                    appUpdateState.current.versionName,
                    appUpdateState.current.channel.displayName,
                ),
            value = appUpdateStatusLabel(appUpdateState.status),
            onClick = onOpenAppUpdate,
        )
    }

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_group_account)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.settings_log_out))
            }
        }
    }
}

/**
 * Settings 首页只展示简短更新摘要，完整版本和日志统一放在更新对话框中。
 *
 * 更新状态对象属于稳定业务模型，不能把某一种语言写进模型；这里在 Compose 展示边界
 * 根据当前 Locale 解析文案，语言切换后首页状态会与其他设置项一起立即重组。
 */
@Composable
internal fun appUpdateStatusLabel(status: AppUpdateStatus): String? =
    when (status) {
        AppUpdateStatus.Idle -> null
        AppUpdateStatus.Checking -> stringResource(R.string.settings_update_checking)
        AppUpdateStatus.UpToDate -> stringResource(R.string.settings_update_up_to_date)
        is AppUpdateStatus.UpdateAvailable ->
            stringResource(R.string.settings_update_found, status.update.versionName)
        is AppUpdateStatus.Downloading ->
            status.progress.fraction?.let { fraction ->
                stringResource(
                    R.string.settings_update_downloading_percent,
                    (fraction * 100).toInt(),
                )
            } ?: stringResource(R.string.settings_update_downloading)
        is AppUpdateStatus.Downloaded -> stringResource(R.string.settings_update_waiting_install)
        is AppUpdateStatus.Installing -> stringResource(R.string.settings_update_installing)
        is AppUpdateStatus.Error -> stringResource(R.string.settings_update_retry_available)
    }

/**
 * 统一清理 Gateway 展示值，并强制调用方提供当前 Locale 对应的空值文案。
 *
 * 这里刻意不提供英文默认参数：所有 Compose 调用点都必须显式传入字符串资源，避免后续新增调用时
 * 在中文界面静默泄漏英文；同时不猜测或回退到服务端内部监听地址。
 */
internal fun gatewayEndpointLabel(
    gatewayEndpoint: String,
    emptyLabel: String,
): String = gatewayEndpoint.trim().trimEnd('/').ifBlank { emptyLabel }

/** Settings 首页顶部只展示用户做判断所需的连接与模型摘要。 */
@Composable
private fun GatewaySummaryCard(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    connectionStatus: String,
    gatewayEndpoint: String,
    onReconnect: () -> Unit,
    onManage: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val payload = state.payload
    val agent = payload?.agent
    val provider = agent?.resolvedProvider?.takeIf(String::isNotBlank)
        ?: agent?.provider?.takeIf(String::isNotBlank)
    val providerConfigured = payload?.providers?.firstOrNull { it.name == provider }?.configured == true
    val model = agent?.model?.takeIf(String::isNotBlank)?.takeIf { providerConfigured }
        ?: stringResource(R.string.settings_not_configured)
    val gateway = gatewayEndpointLabel(
        gatewayEndpoint = gatewayEndpoint,
        emptyLabel = stringResource(R.string.settings_gateway_endpoint_unavailable),
    )
    // connectionStatus 是 app 组合根传入的稳定英文状态值。这里一次完成显示文案与产品语义映射，
    // 避免状态点、文字和容器色分别判断后出现“显示连接中但使用错误色”的分叉。
    val statusPresentation = when {
        connectionStatus.equals("Connected", ignoreCase = true) ->
            stringResource(R.string.settings_connection_connected) to NanobotStatusTone.Success
        connectionStatus.equals("Connecting", ignoreCase = true) ->
            stringResource(R.string.settings_connection_connecting) to NanobotStatusTone.Warning
        connectionStatus.equals("Disconnected", ignoreCase = true) ->
            stringResource(R.string.settings_connection_disconnected) to NanobotStatusTone.Error
        else ->
            stringResource(R.string.settings_connection_unknown) to NanobotStatusTone.Neutral
    }

    NanobotSummarySurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_gateway_system),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = gateway,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            NanobotStatusLabel(
                label = statusPresentation.first,
                tone = statusPresentation.second,
            )
        }

        // “管理”是稳定主路径；“重新连接”只在恢复连接时使用，因此保持较低视觉权重。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onReconnect) {
                Icon(imageVector = Icons.Outlined.Sync, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_reconnect))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(onClick = onManage) {
                Icon(imageVector = Icons.Outlined.SettingsSuggest, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_manage))
            }
        }

        // 当前模型属于 Gateway 摘要的一部分，但不再嵌套 Card；整行点击仍保留原有模型入口语义。
        NanobotNavigationRow(
            headline = model,
            supportingText = stringResource(R.string.settings_current_model),
            modifier = Modifier.clip(MaterialTheme.shapes.medium),
            onClick = onOpenModels,
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                if (!provider.isNullOrBlank()) {
                    ProviderMark(
                        provider = provider,
                        showBrandLogos = showBrandLogos,
                        size = ProviderMarkSize.PICKER,
                        fallbackIcon = Icons.Outlined.Language,
                    )
                }
            },
        )
    }
}
