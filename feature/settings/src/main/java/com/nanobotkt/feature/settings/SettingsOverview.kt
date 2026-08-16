package com.nanobotkt.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanobotkt.core.model.SettingsUsage
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.FileEditDisplay
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.persistence.UserPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Overview 与 Appearance 页面；保持展示偏好和概览信息聚合在一起。 */
@Composable
internal fun OverviewPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onSectionChange: (String) -> Unit,
    onCheckVersion: () -> Unit,
) {
    val payload = state.payload
    val agent = payload?.agent
    val activePresetName = agent?.modelPreset
    val activePreset =
        activePresetName
            ?.takeIf { it != "default" }
            ?.let { name -> payload.modelPresets.firstOrNull { it.name == name } }
    val activeProvider =
        agent?.resolvedProvider?.takeIf { it.isNotBlank() }
            ?: agent?.provider?.takeIf { it.isNotBlank() }
    val activeProviderRow = payload?.providers?.firstOrNull { it.name == activeProvider }
    val activeProviderConfigured = activeProviderRow?.configured == true
    val activeProviderLabel =
        activeProviderRow?.label?.takeIf { it.isNotBlank() } ?: activeProvider.orEmpty()
    val modelName =
        if (activeProviderConfigured) {
            agent?.model?.takeIf { it.isNotBlank() } ?: "Not configured"
        } else {
            "Not configured"
        }
    val modelCaption =
        if (activeProviderConfigured) {
            listOfNotNull(activeProvider, activePreset?.label?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
        } else {
            listOfNotNull(
                    activeProviderLabel.takeIf { it.isNotBlank() },
                    agent?.model?.takeIf { it.isNotBlank() },
                )
                .joinToString(" · ")
                .ifBlank { "No configured providers" }
        }

    TokenUsageHeatmapCard(payload?.usage)

    GroupSpacer()
    SettingsGroup("AI") {
        SettingsRow(
            icon = Icons.Outlined.SmartToy,
            title = "Current model",
            subtitle = modelCaption,
            value = modelName,
            valueLogoProvider = activeProvider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange(SETTINGS_SECTION_MODELS) },
        )
    }

    val webSearch = payload?.webSearch
    val webProvider =
        webSearch?.providers?.firstOrNull { it.name == webSearch.provider }
            ?: webSearch?.providers?.firstOrNull()
    val webProviderLabel =
        webProvider?.label?.takeIf { it.isNotBlank() } ?: webSearch?.provider.orEmpty()
    val webCredentialStatus =
        when (webProvider?.credential) {
            "none" -> "No key required"
            "optional_api_key" ->
                if (webSearch?.apiKeyHint.isNullOrBlank()) "No key required" else "Configured"
            "base_url" -> if (webSearch?.baseUrl.isNullOrBlank()) "Not configured" else "Configured"
            else -> if (webSearch?.apiKeyHint.isNullOrBlank()) "Not configured" else "Configured"
        }
    val image = payload?.imageGeneration
    val imageProviderLabel =
        image
            ?.providers
            ?.firstOrNull { it.name == image.provider }
            ?.label
            ?.takeIf { it.isNotBlank() } ?: image?.provider.orEmpty()
    val voice = payload?.transcription
    val voiceProviderLabel =
        voice
            ?.providers
            ?.firstOrNull { it.name == voice.provider }
            ?.label
            ?.takeIf { it.isNotBlank() } ?: voice?.provider.orEmpty()

    GroupSpacer()
    SettingsGroup("Capabilities") {
        SettingsRow(
            icon = Icons.Outlined.Public,
            title = "Web search",
            subtitle =
                listOf(webProviderLabel, webCredentialStatus)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            value = if (payload?.web?.enable == true) "Enabled" else "Disabled",
            valueLogoProvider = webSearch?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Web") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = "Image generation",
            subtitle =
                listOf(
                        imageProviderLabel,
                        if (image?.providerConfigured == true) "Configured" else "Not configured",
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            value = if (image?.enabled == true) "Enabled" else "Disabled",
            valueLogoProvider = image?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Image") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.MicNone,
            title = "Voice input",
            subtitle =
                listOf(
                        voiceProviderLabel,
                        if (voice?.providerConfigured == true) "Configured" else "Not configured",
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            value = if (voice?.enabled == true) "Enabled" else "Disabled",
            valueLogoProvider = voice?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Voice") },
        )
    }

    GroupSpacer()
    SettingsGroup("System") {
        val host = payload?.runtime?.gatewayHost.orEmpty()
        val port = payload?.runtime?.gatewayPort ?: 0
        SettingsRow(
            icon = Icons.Outlined.Dns,
            title = "Gateway",
            subtitle =
                if (payload?.requiresRestart == true) "Restart pending"
                else if (payload != null) "Ready" else "Not connected",
            value = if (host.isNotBlank() && port > 0) "$host:$port" else "Unavailable",
            onClick = { onSectionChange("System") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = "Workspace",
            subtitle = shortPath(payload?.runtime?.workspacePath),
            value = "Default workspace",
            onClick = { onSectionChange("System") },
        )
    }

    GroupSpacer()
    SettingsGroup("About") {
        val checkedVersion = state.versionCheck?.updateAvailable
        val version =
            checkedVersion?.currentVersion
                ?: payload?.version?.get("current")
                ?: payload?.version?.values?.firstOrNull()
                ?: "nanobot"
        val updateText = checkedVersion?.latestVersion?.let { "Update available v$it" }
        VersionCheckRow(
            version = version,
            updateText = updateText,
            checked = state.versionCheck != null,
            checking = "version" in state.pending,
            onCheckVersion = onCheckVersion,
        )
    }
}

@Composable
internal fun TokenUsageHeatmapCard(usage: SettingsUsage?) {
    // java.time 在 minSdk 24 上需要额外 desugaring；热力图只需要本地自然日，使用 Calendar
    // 可以直接覆盖所有受支持系统版本，并在生成 key 前统一归零时分秒，避免夏令时边界漂移。
    val todayStart =
        remember {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    val endStart =
        remember(todayStart) {
            Calendar.getInstance().apply {
                timeInMillis = todayStart
                val daysUntilSaturday = (Calendar.SATURDAY - get(Calendar.DAY_OF_WEEK) + 7) % 7
                add(Calendar.DAY_OF_YEAR, daysUntilSaturday)
            }.timeInMillis
        }
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val monthFormat = remember { SimpleDateFormat("MMM", Locale.ENGLISH) }
    val days =
        remember(endStart) {
            List(371) { index ->
                Calendar.getInstance().apply {
                    timeInMillis = endStart
                    add(Calendar.DAY_OF_YEAR, index - 370)
                }.timeInMillis
            }
        }
    val totals =
        remember(usage?.days) { usage?.days.orEmpty().associate { it.date to it.totalTokens } }
    val maxTokens = remember(totals) { totals.values.maxOrNull()?.coerceAtLeast(0L) ?: 0L }
    val monthLabels =
        remember(days) {
            days.mapNotNull { day ->
                Calendar.getInstance().apply { timeInMillis = day }
                    .takeIf { it.get(Calendar.DAY_OF_MONTH) == 1 }
                    ?.let { monthFormat.format(it.time) }
            }
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                text = "Token Usage",
                modifier = Modifier.align(Alignment.End),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            if (monthLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    monthLabels
                        .filterIndexed { index, _ -> index % 2 == 0 }
                        .forEach { month ->
                            Text(
                                text = month,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                }
                Spacer(Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(53) { column ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        repeat(7) { row ->
                            val day = days[column * 7 + row]
                            val tokens = totals[dayKeyFormat.format(day)] ?: 0L
                            val level =
                                when {
                                    day > todayStart -> -1
                                    tokens <= 0L || maxTokens <= 0L -> 0
                                    tokens.toDouble() / maxTokens >= 0.75 -> 4
                                    tokens.toDouble() / maxTokens >= 0.45 -> 3
                                    tokens.toDouble() / maxTokens >= 0.20 -> 2
                                    else -> 1
                                }
                            val color =
                                when (level) {
                                    -1 -> Color.Transparent
                                    4 -> MaterialTheme.colorScheme.primary
                                    3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                                    2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.52f)
                                    1 -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(color)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppearancePage(preferences: UserPreferences, viewModel: SettingsViewModel) {
    SettingsGroup("Interface") {
        PreferenceBlock(
            title = "Theme",
            description = "Switch between light and dark appearance.",
        ) {
            val darkSelected =
                when (preferences.theme) {
                    ThemePreference.DARK -> true
                    ThemePreference.LIGHT -> false
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                }
            SegmentedSetting(
                options = listOf("Light", "Dark"),
                selectedIndex = if (darkSelected) 1 else 0,
                onSelected = {
                    viewModel.setTheme(if (it == 0) ThemePreference.LIGHT else ThemePreference.DARK)
                },
            )
        }
        CardDivider()
        LanguagePreference(preferences.languageTag, viewModel::setLanguage)
    }

    GroupSpacer(27.dp)
    SettingsGroup("Local preferences") {
        PreferenceBlock(title = "Density", description = "Stored only in this browser.") {
            SegmentedSetting(
                options = listOf("Comfortable", "Compact"),
                selectedIndex = if (preferences.density == DensityPreference.COMPACT) 1 else 0,
                onSelected = {
                    viewModel.setDensity(
                        if (it == 0) DensityPreference.COMFORTABLE else DensityPreference.COMPACT
                    )
                },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = "Activity detail",
            description = "Choose how much agent activity chrome to show by default.",
        ) {
            SegmentedSetting(
                options = listOf("Auto", "Expanded"),
                selectedIndex = if (preferences.showActivityDetails) 1 else 0,
                onSelected = { viewModel.activity(it == 1) },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = "File edit display",
            description = "Choose whether file edit activity opens as line counts or a diff.",
        ) {
            SegmentedSetting(
                options = listOf("Summary", "Diff", "Collapsed diff"),
                selectedIndex =
                    when (preferences.fileEditDisplay) {
                        FileEditDisplay.SUMMARY -> 0
                        FileEditDisplay.DIFF -> 1
                        FileEditDisplay.HIDDEN -> 2
                    },
                onSelected = {
                    viewModel.fileEdits(
                        when (it) {
                            0 -> FileEditDisplay.SUMMARY
                            1 -> FileEditDisplay.DIFF
                            else -> FileEditDisplay.HIDDEN
                        }
                    )
                },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = "Code wrapping",
            description = "Wrap long code lines instead of scrolling horizontally.",
        ) {
            ToggleSetting(checked = preferences.wrapCode, onCheckedChange = viewModel::wrap)
        }
        CardDivider()
        PreferenceBlock(
            title = "Brand logos",
            description = "Show provider logos where they are available.",
        ) {
            ToggleSetting(checked = preferences.showBrandLogos, onCheckedChange = viewModel::logos)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagePreference(languageTag: String?, onChange: (String?) -> Unit) {
    /**
     * null 代表跟随 Android 系统语言，而不是英文。显式保留该选项，确保持久化值、界面展示
     * 和实际 Locale 行为一致。
     */
    val languages =
        listOf(
            null to "System default",
            "en" to "English",
            "zh-CN" to "简体中文",
            "zh-TW" to "繁體中文",
            "ja" to "日本語",
            "ko" to "한국어",
            "es" to "Español",
            "fr" to "Français",
            "pt-BR" to "Português",
            "vi" to "Tiếng Việt",
            "id" to "Indonesia",
        )
    var expanded by remember { mutableStateOf(false) }
    val currentName = languages.firstOrNull { it.first == languageTag }?.second ?: "System default"

    PreferenceBlock(
        title = "Language",
        description = "Choose the language used by the WebUI.",
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = currentName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier =
                    Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Language, contentDescription = null)
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                languages.forEach { (tag, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = {
                            if (tag == languageTag) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onChange(tag)
                        },
                    )
                }
            }
        }
    }
}
