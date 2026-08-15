package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    SettingsGroup("GENERAL") {
        SettingsRow(
            icon = Icons.Outlined.Palette,
            title = "Appearance",
            subtitle = "Theme, language, density and display",
            onClick = { onOpenSection(SETTINGS_SECTION_APPEARANCE) },
        )
    }

    GroupSpacer()
    SettingsGroup("AI & CAPABILITIES") {
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = "Models & Providers",
            subtitle = "Providers, credentials and model call order",
            onClick = { onOpenSection(SETTINGS_SECTION_MODELS) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Apps,
            title = "Apps",
            subtitle = "Install and manage assistant apps",
            onClick = onOpenApps,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.AutoAwesome,
            title = "Skills",
            subtitle = "Manage assistant capabilities",
            onClick = onOpenSkills,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = "Image generation",
            subtitle = "Provider, model and output defaults",
            onClick = { onOpenSection(SETTINGS_SECTION_IMAGE) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.MicNone,
            title = "Voice",
            subtitle = "Audio transcription provider and model",
            onClick = { onOpenSection(SETTINGS_SECTION_VOICE) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Public,
            title = "Web search",
            subtitle = "Search provider and credentials",
            onClick = { onOpenSection(SETTINGS_SECTION_WEB) },
        )
    }

    GroupSpacer()
    SettingsGroup("INTEGRATIONS & AUTOMATION") {
        SettingsRow(
            icon = Icons.Outlined.Sync,
            title = "Channels",
            subtitle = "Connect messaging channels to your assistant",
            onClick = onOpenChannels,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Schedule,
            title = "Automations",
            subtitle = "Create and manage scheduled tasks",
            onClick = onOpenAutomations,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = "Workspaces",
            subtitle = "Default workspace and access mode",
            onClick = onOpenWorkspaces,
        )
    }

    GroupSpacer()
    SettingsGroup("SYSTEM & SECURITY") {
        SettingsRow(
            icon = Icons.Outlined.Api,
            title = "Gateway & System",
            subtitle = "Runtime, API service and connection details",
            onClick = { onOpenSection(SETTINGS_SECTION_SYSTEM) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.SettingsSuggest,
            title = "App safety",
            subtitle = "Local service and default access rules",
            onClick = { onOpenSection(SETTINGS_SECTION_SECURITY) },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Security,
            title = "Security & Pairing",
            subtitle = "Review device pairing requests",
            onClick = onOpenSecurityAndPairing,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = "About",
            subtitle = "Version information and update check",
            value = state.payload?.version?.let { versions ->
                versions["current"] ?: versions.values.firstOrNull()
            },
            onClick = onCheckVersion,
        )
    }

    GroupSpacer()
    SettingsGroup("ACCOUNT") {
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
                Text("Log out")
            }
        }
    }
}

/**
 * 统一清理 Gateway 展示值。这里只处理显示格式，不猜测或回退到服务端内部监听地址。
 */
internal fun gatewayEndpointLabel(gatewayEndpoint: String): String =
    gatewayEndpoint.trim().trimEnd('/').ifBlank { "Gateway endpoint unavailable" }

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
        ?: "Not configured"
    // runtime.gatewayHost/runtime.gatewayPort 描述的是服务端进程自己的监听端点，
    // 可能是 127.0.0.1 等 Android 无法访问的内部地址。首页只展示 app 组合根传入的
    // 真实客户端入口，确保连接摘要与 HTTP、Bootstrap、WebSocket 的实际配置一致。
    val gateway = gatewayEndpointLabel(gatewayEndpoint)
    val connected = connectionStatus.equals("Connected", ignoreCase = true)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                ) {}
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        text = "Gateway $connectionStatus",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = gateway,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinePillButton(
                    text = "Manage",
                    onClick = onManage,
                    icon = Icons.Outlined.SettingsSuggest,
                )
                Spacer(Modifier.size(8.dp))
                OutlinePillButton(
                    text = "Reconnect",
                    onClick = onReconnect,
                    icon = Icons.Outlined.Sync,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                onClick = onOpenModels,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Current model", fontSize = 12.sp)
                        Text(
                            text = model,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    }
                    if (!provider.isNullOrBlank()) {
                        ProviderMark(
                            provider = provider,
                            showBrandLogos = showBrandLogos,
                            size = ProviderMarkSize.PICKER,
                            fallbackIcon = Icons.Outlined.Language,
                        )
                    }
                }
            }
        }
    }
}
