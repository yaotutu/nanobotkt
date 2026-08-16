package com.nanobotkt.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
            agent?.model?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_not_configured)
        } else {
            stringResource(R.string.settings_not_configured)
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
                .ifBlank { stringResource(R.string.settings_no_configured_providers) }
        }

    TokenUsageHeatmapCard(payload?.usage)

    GroupSpacer()
    SettingsGroup("AI") {
        SettingsRow(
            icon = Icons.Outlined.SmartToy,
            title = stringResource(R.string.settings_current_model),
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
            "none" -> stringResource(R.string.settings_no_key_required)
            "optional_api_key" ->
                if (webSearch?.apiKeyHint.isNullOrBlank()) {
                    stringResource(R.string.settings_no_key_required)
                } else {
                    stringResource(R.string.settings_configured)
                }
            "base_url" ->
                if (webSearch?.baseUrl.isNullOrBlank()) {
                    stringResource(R.string.settings_not_configured)
                } else {
                    stringResource(R.string.settings_configured)
                }
            else ->
                if (webSearch?.apiKeyHint.isNullOrBlank()) {
                    stringResource(R.string.settings_not_configured)
                } else {
                    stringResource(R.string.settings_configured)
                }
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
    SettingsGroup(stringResource(R.string.settings_group_ai_capabilities)) {
        SettingsRow(
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.settings_web_search),
            subtitle =
                listOf(webProviderLabel, webCredentialStatus)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            value =
                if (payload?.web?.enable == true) {
                    stringResource(R.string.settings_enabled)
                } else {
                    stringResource(R.string.settings_disabled)
                },
            valueLogoProvider = webSearch?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Web") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = stringResource(R.string.settings_image_generation),
            subtitle =
                listOf(
                        imageProviderLabel,
                        if (image?.providerConfigured == true) {
                            stringResource(R.string.settings_configured)
                        } else {
                            stringResource(R.string.settings_not_configured)
                        },
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            value =
                if (image?.enabled == true) {
                    stringResource(R.string.settings_enabled)
                } else {
                    stringResource(R.string.settings_disabled)
                },
            valueLogoProvider = image?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Image") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.MicNone,
            title = stringResource(R.string.settings_voice),
            subtitle =
                listOf(
                        voiceProviderLabel,
                        if (voice?.providerConfigured == true) {
                            stringResource(R.string.settings_configured)
                        } else {
                            stringResource(R.string.settings_not_configured)
                        },
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            value =
                if (voice?.enabled == true) {
                    stringResource(R.string.settings_enabled)
                } else {
                    stringResource(R.string.settings_disabled)
                },
            valueLogoProvider = voice?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Voice") },
        )
    }

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_group_system_security)) {
        val host = payload?.runtime?.gatewayHost.orEmpty()
        val port = payload?.runtime?.gatewayPort ?: 0
        SettingsRow(
            icon = Icons.Outlined.Dns,
            title = "Gateway",
            subtitle =
                if (payload?.requiresRestart == true) stringResource(R.string.settings_restart_pending)
                else if (payload != null) {
                    stringResource(R.string.settings_ready)
                } else {
                    stringResource(R.string.settings_not_connected)
                },
            value =
                if (host.isNotBlank() && port > 0) "$host:$port"
                else stringResource(R.string.settings_unavailable),
            onClick = { onSectionChange("System") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.settings_workspaces),
            subtitle =
                shortPath(
                    payload?.runtime?.workspacePath,
                    stringResource(R.string.settings_no_workspace_selected),
                ),
            value = stringResource(R.string.settings_default_workspace),
            onClick = { onSectionChange("System") },
        )
    }

    GroupSpacer()
    SettingsGroup(stringResource(R.string.settings_about)) {
        val checkedVersion = state.versionCheck?.updateAvailable
        val version =
            checkedVersion?.currentVersion
                ?: payload?.version?.get("current")
                ?: payload?.version?.values?.firstOrNull()
                ?: "nanobot"
        val updateText = checkedVersion?.latestVersion?.let {
                stringResource(R.string.settings_update_available_version, it)
            }
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
    val today = remember { LocalDate.now() }
    val end = remember(today) { today.plusDays((6 - (today.dayOfWeek.value % 7)).toLong()) }
    val dates = remember(end) { List(371) { index -> end.minusDays((370 - index).toLong()) } }
    val totals =
        remember(usage?.days) { usage?.days.orEmpty().associate { it.date to it.totalTokens } }
    val maxTokens = remember(totals) { totals.values.maxOrNull()?.coerceAtLeast(0L) ?: 0L }
    val configuration = LocalConfiguration.current
    val displayLocale = configuration.locales[0] ?: Locale.getDefault()
    val monthLabels =
        remember(dates, displayLocale) {
            val formatter = DateTimeFormatter.ofPattern("MMM", displayLocale)
            dates.filter { it.dayOfMonth == 1 }.map { it.format(formatter) }
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardBackground,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.settings_token_usage),
                modifier = Modifier.align(Alignment.End),
                color = SecondaryText.copy(alpha = 0.64f),
                fontSize = 11.sp,
                lineHeight = 11.sp,
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
                                month,
                                color = SecondaryText.copy(alpha = 0.62f),
                                fontSize = 10.sp,
                                lineHeight = 16.sp,
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
                            val date = dates[column * 7 + row]
                            val tokens = totals[date.toString()] ?: 0L
                            val level =
                                when {
                                    date > today -> -1
                                    tokens <= 0L || maxTokens <= 0L -> 0
                                    tokens.toDouble() / maxTokens >= 0.75 -> 4
                                    tokens.toDouble() / maxTokens >= 0.45 -> 3
                                    tokens.toDouble() / maxTokens >= 0.20 -> 2
                                    else -> 1
                                }
                            val color =
                                when (level) {
                                    -1 -> Color.Transparent
                                    4 -> Color(0xFF7DD3FC)
                                    3 -> Color(0xFF38BDF8).copy(alpha = 0.85f)
                                    2 -> Color(0xFF0EA5E9).copy(alpha = 0.60f)
                                    1 ->
                                        if (settingsDark) Color(0xFF0C4A6E).copy(alpha = 0.80f)
                                        else Color(0xFF0EA5E9).copy(alpha = 0.30f)
                                    else ->
                                        if (settingsDark) Color.White.copy(alpha = 0.08f)
                                        else Color(0xFFD4D4D4).copy(alpha = 0.70f)
                                }
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(2.dp))
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
    SettingsGroup(stringResource(R.string.settings_group_interface)) {
        PreferenceBlock(
            title = stringResource(R.string.settings_theme),
            description = stringResource(R.string.settings_theme_summary),
        ) {
            val darkSelected =
                when (preferences.theme) {
                    ThemePreference.DARK -> true
                    ThemePreference.LIGHT -> false
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                }
            SegmentedSetting(
                options =
                    listOf(
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                    ),
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
    SettingsGroup(stringResource(R.string.settings_group_local_preferences)) {
        PreferenceBlock(
            title = stringResource(R.string.settings_density),
            description = stringResource(R.string.settings_density_summary),
        ) {
            SegmentedSetting(
                options =
                    listOf(
                        stringResource(R.string.settings_density_comfortable),
                        stringResource(R.string.settings_density_compact),
                    ),
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
            title = stringResource(R.string.settings_activity_detail),
            description = stringResource(R.string.settings_activity_detail_summary),
        ) {
            SegmentedSetting(
                options =
                    listOf(
                        stringResource(R.string.settings_activity_auto),
                        stringResource(R.string.settings_activity_expanded),
                    ),
                selectedIndex = if (preferences.showActivityDetails) 1 else 0,
                onSelected = { viewModel.activity(it == 1) },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = stringResource(R.string.settings_file_edit_display),
            description = stringResource(R.string.settings_file_edit_display_summary),
        ) {
            SegmentedSetting(
                options =
                    listOf(
                        stringResource(R.string.settings_file_edit_summary),
                        stringResource(R.string.settings_file_edit_diff),
                        stringResource(R.string.settings_file_edit_collapsed_diff),
                    ),
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
            title = stringResource(R.string.settings_code_wrapping),
            description = stringResource(R.string.settings_code_wrapping_summary),
        ) {
            ToggleSetting(checked = preferences.wrapCode, onCheckedChange = viewModel::wrap)
        }
        CardDivider()
        PreferenceBlock(
            title = stringResource(R.string.settings_brand_logos),
            description = stringResource(R.string.settings_brand_logos_summary),
        ) {
            ToggleSetting(checked = preferences.showBrandLogos, onCheckedChange = viewModel::logos)
        }
    }
}

@Composable
internal fun LanguagePreference(languageTag: String?, onChange: (String?) -> Unit) {
    /**
     * null 代表跟随 Android 系统语言，而不是英文。 之前把 null 当成 English 展示并默认勾选英文，会让系统中文设备看起来像是
     * “设置页英文、聊天页其他语言”的混杂状态；这里把系统默认作为显式选项， 让持久化值、界面展示和实际 Locale 行为保持一致。
     */
    val languages =
        listOf(
            null to stringResource(R.string.settings_language_system_default),
            "en" to stringResource(R.string.settings_language_english),
            "zh-CN" to stringResource(R.string.settings_language_simplified_chinese),
            "zh-TW" to stringResource(R.string.settings_language_traditional_chinese),
            "ja" to stringResource(R.string.settings_language_japanese),
            "ko" to stringResource(R.string.settings_language_korean),
            "es" to stringResource(R.string.settings_language_spanish),
            "fr" to stringResource(R.string.settings_language_french),
            "pt-BR" to stringResource(R.string.settings_language_portuguese),
            "vi" to stringResource(R.string.settings_language_vietnamese),
            "id" to stringResource(R.string.settings_language_indonesian),
        )
    var expanded by remember { mutableStateOf(false) }
    val currentName =
        languages.firstOrNull { it.first == languageTag }?.second
            ?: stringResource(R.string.settings_language_system_default)

    Box {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 15.dp)
        ) {
            Text(stringResource(R.string.settings_language), color = PrimaryText, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_language_summary),
                color = SecondaryText,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Language,
                    null,
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(currentName, color = SecondaryText, fontSize = 13.sp)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        if (tag == languageTag) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(17.dp))
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
