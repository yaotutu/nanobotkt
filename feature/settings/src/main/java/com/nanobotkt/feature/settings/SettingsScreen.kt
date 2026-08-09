package com.nanobotkt.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.model.RuntimeSurface
import com.nanobotkt.core.model.SettingsUsage
import coil.compose.AsyncImage
import com.nanobotkt.core.model.TranscriptionSettings
import com.nanobotkt.core.persistence.FileEditDisplay
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.persistence.UserPreferences
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

const val SETTINGS_SECTION_OVERVIEW = "Overview"
const val SETTINGS_SECTION_MODELS = "Models"
private const val SETTINGS_SECTION_APPEARANCE = "Appearance"

private val sections = listOf(
    SETTINGS_SECTION_OVERVIEW,
    SETTINGS_SECTION_APPEARANCE,
    SETTINGS_SECTION_MODELS,
    "Image",
    "Voice",
    "Web",
    "Channels",
    "System",
    "Security",
)

private val IMAGE_ASPECT_RATIOS = listOf("1:1", "3:4", "9:16", "4:3", "16:9", "3:2", "2:3", "21:9")
private val IMAGE_SIZES = listOf("1K", "2K", "4K", "1024x1024", "1536x1024", "1024x1536")

private fun SettingsUiState.restartPendingFor(vararg sections: String): Boolean {
    val settings = payload ?: return false
    if (!settings.requiresRestart) return false
    val required = settings.restartRequiredSections.orEmpty().map(String::lowercase)
    return required.isEmpty() || sections.any { it.lowercase() in required }
}

private val settingsDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
private val PageBackground: Color
    @Composable get() = if (settingsDark) Color(0xFF303030) else Color.White
private val CardBackground: Color
    @Composable get() = if (settingsDark) Color(0xFF383838) else Color(0xFFF7F7F6)
private val SegmentBackground: Color
    @Composable get() = if (settingsDark) Color(0xFF303030) else Color(0xFFF0F0EF)
private val PrimaryText: Color
    @Composable get() = if (settingsDark) Color(0xFFF5F5F6) else Color(0xFF1D1D1F)
private val SecondaryText: Color
    @Composable get() = if (settingsDark) Color(0xFFA6A6A6) else Color(0xFF737373)
private val DividerColor: Color
    @Composable get() = if (settingsDark) Color(0xFF474747) else Color(0xFFE8E7E5)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenChannels: () -> Unit,
    onLogout: () -> Unit = {},
    initialSection: String = SETTINGS_SECTION_OVERVIEW,
    onSectionChange: (String) -> Unit = {},
    refreshKey: Long = 0L,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    var section by rememberSaveable(initialSection) {
        mutableStateOf(initialSection.takeIf(sections::contains) ?: SETTINGS_SECTION_OVERVIEW)
    }
    val selectSection: (String) -> Unit = { requested ->
        val next = requested.takeIf(sections::contains) ?: SETTINGS_SECTION_OVERVIEW
        section = next
        onSectionChange(next)
    }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item {
            SettingsHeader(
                section = section,
                onBack = onBack,
                onSectionChange = selectSection,
            )
        }
        item {
            when (section) {
                SETTINGS_SECTION_OVERVIEW -> OverviewPage(
                    state = state,
                    showBrandLogos = appearance.showBrandLogos,
                    onSectionChange = selectSection,
                    onCheckVersion = viewModel::checkVersion,
                )
                SETTINGS_SECTION_APPEARANCE -> AppearancePage(appearance, viewModel)
                SETTINGS_SECTION_MODELS -> ModelsPage(state, viewModel, appearance.showBrandLogos)
                "Image" -> ImageGenerationPage(
                    state = state,
                    showBrandLogos = appearance.showBrandLogos,
                    onOpenProviders = { selectSection(SETTINGS_SECTION_MODELS) },
                    onSave = viewModel::updateImage,
                )
                "Voice" -> TranscriptionPage(
                    state = state,
                    showBrandLogos = appearance.showBrandLogos,
                    onOpenProviders = { selectSection(SETTINGS_SECTION_MODELS) },
                    onSave = viewModel::updateTranscription,
                )
                "Web" -> WebSearchPage(
                    state = state,
                    showBrandLogos = appearance.showBrandLogos,
                    onSave = viewModel::updateWebSearch,
                )
                "Channels" -> OpenSectionPage(
                    title = "Channels",
                    description = "Manage the channels connected to your assistant.",
                    icon = Icons.Outlined.Hub,
                    onOpen = onOpenChannels,
                )
                "System" -> SystemPage(state, viewModel)
                "Security" -> SecurityPage(state, viewModel)
            }
        }
        item {
            // Android 设置页采用单列布局，没有 Web 端固定在侧栏底部的账户操作区；
            // 因此把退出登录放在所有设置内容之后，确保用户始终能找到同一项账户操作。
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Log out")
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    section: String,
    onBack: () -> Unit,
    onSectionChange: (String) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = SecondaryText,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text("Back to chat", color = SecondaryText, fontSize = 12.sp)
    }
    Spacer(Modifier.height(15.dp))
    Text(
        text = "Settings",
        color = PrimaryText,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Normal,
    )
    Spacer(Modifier.height(13.dp))
    SettingsSectionPicker(section, onSectionChange)
    Spacer(Modifier.height(27.dp))
}

@Composable
private fun SettingsSectionPicker(
    section: String,
    onSectionChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable { expanded = true },
            shape = RoundedCornerShape(14.dp),
            color = CardBackground,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = sectionIcon(section),
                    contentDescription = null,
                    tint = PrimaryText,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    text = section,
                    color = PrimaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = "Choose settings section",
                    tint = SecondaryText,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.91f)
                .background(PageBackground),
        ) {
            sections.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item, fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(sectionIcon(item), null, Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (item == section) Icon(Icons.Rounded.Check, null, Modifier.size(17.dp))
                    },
                    onClick = {
                        expanded = false
                        onSectionChange(item)
                    },
                )
            }
        }
    }
}

private fun sectionIcon(section: String): ImageVector = when (section) {
    SETTINGS_SECTION_OVERVIEW -> Icons.Outlined.MonitorHeart
    SETTINGS_SECTION_APPEARANCE -> Icons.Outlined.Palette
    SETTINGS_SECTION_MODELS -> Icons.Outlined.Tune
    "Image" -> Icons.Outlined.Image
    "Voice" -> Icons.Outlined.MicNone
    "Web" -> Icons.Outlined.Public
    "Channels" -> Icons.Outlined.Hub
    "Security" -> Icons.Outlined.Security
    else -> Icons.Outlined.Settings
}

@Composable
private fun OverviewPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onSectionChange: (String) -> Unit,
    onCheckVersion: () -> Unit,
) {
    val payload = state.payload
    val agent = payload?.agent
    val activePresetName = agent?.modelPreset
    val activePreset = activePresetName
        ?.takeIf { it != "default" }
        ?.let { name -> payload.modelPresets.firstOrNull { it.name == name } }
    val activeProvider = agent?.resolvedProvider?.takeIf { it.isNotBlank() }
        ?: agent?.provider?.takeIf { it.isNotBlank() }
    val activeProviderRow = payload?.providers?.firstOrNull { it.name == activeProvider }
    val activeProviderConfigured = activeProviderRow?.configured == true
    val activeProviderLabel = activeProviderRow?.label?.takeIf { it.isNotBlank() }
        ?: activeProvider.orEmpty()
    val modelName = if (activeProviderConfigured) {
        agent?.model?.takeIf { it.isNotBlank() } ?: "Not configured"
    } else {
        "Not configured"
    }
    val modelCaption = if (activeProviderConfigured) {
        listOfNotNull(activeProvider, activePreset?.label?.takeIf { it.isNotBlank() }).joinToString(" · ")
    } else {
        listOfNotNull(
            activeProviderLabel.takeIf { it.isNotBlank() },
            agent?.model?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "No configured providers" }
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
    val webProvider = webSearch?.providers?.firstOrNull { it.name == webSearch.provider }
        ?: webSearch?.providers?.firstOrNull()
    val webProviderLabel = webProvider?.label?.takeIf { it.isNotBlank() }
        ?: webSearch?.provider.orEmpty()
    val webCredentialStatus = when (webProvider?.credential) {
        "none" -> "No key required"
        "optional_api_key" -> if (webSearch?.apiKeyHint.isNullOrBlank()) "No key required" else "Configured"
        "base_url" -> if (webSearch?.baseUrl.isNullOrBlank()) "Not configured" else "Configured"
        else -> if (webSearch?.apiKeyHint.isNullOrBlank()) "Not configured" else "Configured"
    }
    val image = payload?.imageGeneration
    val imageProviderLabel = image?.providers?.firstOrNull { it.name == image.provider }?.label
        ?.takeIf { it.isNotBlank() }
        ?: image?.provider.orEmpty()
    val voice = payload?.transcription
    val voiceProviderLabel = voice?.providers?.firstOrNull { it.name == voice.provider }?.label
        ?.takeIf { it.isNotBlank() }
        ?: voice?.provider.orEmpty()

    GroupSpacer()
    SettingsGroup("Capabilities") {
        SettingsRow(
            icon = Icons.Outlined.Public,
            title = "Web search",
            subtitle = listOf(webProviderLabel, webCredentialStatus).filter { it.isNotBlank() }.joinToString(" · "),
            value = if (payload?.web?.enable == true) "Enabled" else "Disabled",
            valueLogoProvider = webSearch?.provider,
            showBrandLogos = showBrandLogos,
            onClick = { onSectionChange("Web") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = "Image generation",
            subtitle = listOf(imageProviderLabel, if (image?.providerConfigured == true) "Configured" else "Not configured")
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
            subtitle = listOf(voiceProviderLabel, if (voice?.providerConfigured == true) "Configured" else "Not configured")
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
            subtitle = if (payload?.requiresRestart == true) "Restart pending" else if (payload != null) "Ready" else "Not connected",
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
        val version = checkedVersion?.currentVersion
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
private fun TokenUsageHeatmapCard(usage: SettingsUsage?) {
    val today = remember { LocalDate.now() }
    val end = remember(today) { today.plusDays((6 - (today.dayOfWeek.value % 7)).toLong()) }
    val dates = remember(end) { List(371) { index -> end.minusDays((370 - index).toLong()) } }
    val totals = remember(usage?.days) { usage?.days.orEmpty().associate { it.date to it.totalTokens } }
    val maxTokens = remember(totals) { totals.values.maxOrNull()?.coerceAtLeast(0L) ?: 0L }
    val monthLabels = remember(dates) {
        dates.filter { it.dayOfMonth == 1 }.map { it.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardBackground,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                text = "Token Usage",
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
                    monthLabels.filterIndexed { index, _ -> index % 2 == 0 }.forEach { month ->
                        Text(month, color = SecondaryText.copy(alpha = 0.62f), fontSize = 10.sp, lineHeight = 16.sp)
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
                            val level = when {
                                date > today -> -1
                                tokens <= 0L || maxTokens <= 0L -> 0
                                tokens.toDouble() / maxTokens >= 0.75 -> 4
                                tokens.toDouble() / maxTokens >= 0.45 -> 3
                                tokens.toDouble() / maxTokens >= 0.20 -> 2
                                else -> 1
                            }
                            val color = when (level) {
                                -1 -> Color.Transparent
                                4 -> Color(0xFF7DD3FC)
                                3 -> Color(0xFF38BDF8).copy(alpha = 0.85f)
                                2 -> Color(0xFF0EA5E9).copy(alpha = 0.60f)
                                1 -> if (settingsDark) Color(0xFF0C4A6E).copy(alpha = 0.80f) else Color(0xFF0EA5E9).copy(alpha = 0.30f)
                                else -> if (settingsDark) Color.White.copy(alpha = 0.08f) else Color(0xFFD4D4D4).copy(alpha = 0.70f)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(color),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearancePage(preferences: UserPreferences, viewModel: SettingsViewModel) {
    SettingsGroup("Interface") {
        PreferenceBlock(
            title = "Theme",
            description = "Switch between light and dark appearance.",
        ) {
            val darkSelected = when (preferences.theme) {
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
        PreferenceBlock(
            title = "Density",
            description = "Stored only in this browser.",
        ) {
            SegmentedSetting(
                options = listOf("Comfortable", "Compact"),
                selectedIndex = if (preferences.density == DensityPreference.COMPACT) 1 else 0,
                onSelected = {
                    viewModel.setDensity(if (it == 0) DensityPreference.COMFORTABLE else DensityPreference.COMPACT)
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
                selectedIndex = when (preferences.fileEditDisplay) {
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
                        },
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

@Composable
private fun LanguagePreference(languageTag: String?, onChange: (String?) -> Unit) {
    /**
     * null 代表跟随 Android 系统语言，而不是英文。
     * 之前把 null 当成 English 展示并默认勾选英文，会让系统中文设备看起来像是
     * “设置页英文、聊天页其他语言”的混杂状态；这里把系统默认作为显式选项，
     * 让持久化值、界面展示和实际 Locale 行为保持一致。
     */
    val languages = listOf(
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

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Text("Language", color = PrimaryText, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("Choose the language used by the WebUI.", color = SecondaryText, fontSize = 13.sp)
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Language, null, tint = SecondaryText, modifier = Modifier.size(16.dp))
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

@Composable
private fun ModelsPage(state: SettingsUiState, viewModel: SettingsViewModel, showBrandLogos: Boolean) {
    val payload = state.payload
    var showCreateModel by rememberSaveable { mutableStateOf(false) }
    var editingModelName by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingModelName by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateProvider by rememberSaveable { mutableStateOf(false) }
    var editingProviderName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingModel = payload?.modelPresets?.firstOrNull { it.name == editingModelName }
    val editingProvider = payload?.providers?.firstOrNull { it.name == editingProviderName }

    SettingsGroup("Models") {
        if (payload?.modelPresets.isNullOrEmpty()) {
            EmptySettingsRow(
                icon = Icons.Outlined.SmartToy,
                title = "No models available",
                subtitle = "Connect to the gateway to load model presets.",
                action = "Refresh",
                onClick = viewModel::refresh,
            )
        } else {
            payload?.modelPresets.orEmpty().forEachIndexed { index, preset ->
                SettingsRow(
                    icon = Icons.Outlined.SmartToy,
                    leadingProvider = preset.resolvedProvider ?: preset.provider,
                    showBrandLogos = showBrandLogos,
                    title = preset.label,
                    subtitle = "${preset.provider} · ${preset.model}",
                    value = if (preset.active) "Active" else null,
                    selected = preset.active,
                    // 保留原有语义：点击整行仍然切换当前活动模型。
                    onClick = { viewModel.update(SettingsUpdate(modelPreset = preset.name)) },
                    trailingContent = if (!preset.isDefault) {
                        {
                            TextButton(onClick = { editingModelName = preset.name }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit model", modifier = Modifier.size(16.dp))
                            }
                            TextButton(onClick = { deletingModelName = preset.name }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete model", modifier = Modifier.size(16.dp))
                            }
                        }
                    } else null,
                )
                if (index != payload.modelPresets.lastIndex) CardDivider()
            }
        }
        // 创建动作放在列表底部，保持原有“点击模型即切换默认模型”的行为不变。
        Button(
            onClick = { showCreateModel = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            enabled = !state.pending.contains("model-configuration"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add model configuration")
        }
    }

    if (payload?.modelCallOrderEditable == false && payload.modelPresets.isNotEmpty()) {
        GroupSpacer()
        SettingsGroup("Legacy model configuration") {
            PreferenceBlock(
                title = "Migrate model configurations",
                description = "Convert the legacy primary and fallback settings into named model presets.",
            )
            TextButton(
                onClick = viewModel::migrateModelConfigurations,
                enabled = "model-configuration-migration" !in state.pending,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(if ("model-configuration-migration" in state.pending) "Migrating…" else "Migrate")
            }
        }
    }

    if (payload?.modelCallOrderEditable == true) {
        val order = payload.modelCallOrder.ifEmpty {
            payload.modelPresets.filterNot { it.isDefault }.map { it.name }
        }
        GroupSpacer()
        SettingsGroup("Model call order") {
            PreferenceBlock(
                title = "Primary and fallback models",
                description = "The first model is used first; following entries are tried when a request fails.",
            )
            order.forEachIndexed { index, name ->
                val preset = payload.modelPresets.firstOrNull { it.name == name }
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    leadingProvider = preset?.resolvedProvider ?: preset?.provider,
                    showBrandLogos = showBrandLogos,
                    title = preset?.label ?: name,
                    subtitle = if (preset == null) "Unknown preset: $name" else preset.model,
                    value = if (index == 0) "Primary" else "Fallback ${index}",
                    showChevron = false,
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val moved = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    viewModel.updateModelCallOrder(ModelCallOrderUpdate(moved))
                                },
                                enabled = index > 0 && "model-call-order" !in state.pending,
                            ) {
                                Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = {
                                    val moved = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                    viewModel.updateModelCallOrder(ModelCallOrderUpdate(moved))
                                },
                                enabled = index < order.lastIndex && "model-call-order" !in state.pending,
                            ) {
                                Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                )
                if (index != order.lastIndex) CardDivider()
            }
        }
    }

    GroupSpacer()
    SettingsGroup("Providers") {
        if (payload?.providers.isNullOrEmpty()) {
            EmptySettingsRow(
                icon = Icons.Outlined.Dns,
                title = "Providers unavailable",
                subtitle = "Provider settings could not be loaded.",
                action = "Refresh",
                onClick = viewModel::refresh,
            )
        } else {
            payload?.providers.orEmpty().forEachIndexed { index, provider ->
                SettingsRow(
                    icon = Icons.Outlined.Dns,
                    leadingProvider = provider.name,
                    showBrandLogos = showBrandLogos,
                    title = provider.label,
                    subtitle = listOfNotNull(
                        if (provider.configured) "Configured" else "Not configured",
                        provider.oauthAccount?.let { "OAuth: $it" },
                    ).joinToString(" · "),
                    value = if (provider.configured) "Connected" else null,
                    // 点击 Provider 仍然加载模型目录；编辑入口单独放在尾部。
                    onClick = { viewModel.providerModels(provider.name) },
                    trailingContent = {
                        TextButton(onClick = { editingProviderName = provider.name }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit provider", modifier = Modifier.size(16.dp))
                        }
                    },
                )
                if (index != payload.providers.lastIndex) CardDivider()
            }
        }
        Button(
            onClick = { showCreateProvider = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            enabled = !state.pending.contains("provider:create"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add custom provider")
        }
    }

    state.providerModels?.let { catalog ->
        GroupSpacer()
        SettingsGroup(catalog.label) {
            if (catalog.models.isEmpty()) {
                EmptySettingsRow(Icons.Outlined.Search, "No models found", catalog.message ?: "This provider returned no models.")
            } else {
                catalog.models.forEachIndexed { index, model ->
                    SettingsRow(
                        icon = Icons.Outlined.SmartToy,
                        leadingProvider = catalog.provider,
                        showBrandLogos = showBrandLogos,
                        title = model.label ?: model.id,
                        subtitle = model.id,
                        showChevron = false,
                    )
                    if (index != catalog.models.lastIndex) CardDivider()
                }
            }
        }
    }

    if (showCreateModel) {
        ModelConfigurationDialog(
            providers = payload?.providers.orEmpty().map { it.name to it.label },
            initial = null,
            saving = state.pending.contains("model-configuration"),
            error = state.error,
            onDismiss = { if (!state.pending.contains("model-configuration")) showCreateModel = false },
            onConfirm = { form ->
                viewModel.createModelConfiguration(
                    ModelConfigurationCreate(
                        label = form.label,
                        name = form.name,
                        model = form.model,
                        provider = form.provider,
                        maxTokens = form.maxTokens,
                        contextWindowTokens = form.contextWindowTokens,
                        temperature = form.temperature,
                        reasoningEffort = form.reasoningEffort,
                    ),
                )
                showCreateModel = false
            },
        )
    }
    editingModel?.let { preset ->
        ModelConfigurationDialog(
            providers = payload?.providers.orEmpty().map { it.name to it.label },
            initial = preset,
            saving = state.pending.contains("model-configuration"),
            error = state.error,
            onDismiss = { if (!state.pending.contains("model-configuration")) editingModelName = null },
            onConfirm = { form ->
                viewModel.updateModelConfiguration(
                    ModelConfigurationUpdate(
                        name = preset.name,
                        label = form.label,
                        model = form.model,
                        provider = form.provider,
                        maxTokens = form.maxTokens,
                        contextWindowTokens = form.contextWindowTokens,
                        temperature = form.temperature,
                        // 空字符串是服务端约定的“清除 reasoning effort”。
                        reasoningEffort = form.reasoningEffort.orEmpty(),
                    ),
                )
                editingModelName = null
            },
        )
    }
    editingProvider?.let { provider ->
        ProviderEditDialog(
            provider = provider,
            state = state,
            saving = state.pending.contains("provider:${provider.name}"),
            onDismiss = { if (!state.pending.contains("provider:${provider.name}")) editingProviderName = null },
            onSave = { update ->
                viewModel.provider(update)
                editingProviderName = null
            },
            onOAuthLogin = { viewModel.oauth(provider.name) },
            onOAuthComplete = { flowId, code -> viewModel.oauthComplete(provider.name, flowId, code) },
            onOAuthLogout = { viewModel.oauthLogout(provider.name) },
        )
    }
    if (showCreateProvider) {
        CustomProviderDialog(
            saving = state.pending.contains("provider:create"),
            error = state.error,
            onDismiss = { if (!state.pending.contains("provider:create")) showCreateProvider = false },
            onConfirm = { create ->
                viewModel.createProvider(create)
                showCreateProvider = false
            },
        )
    }
    deletingModelName?.let { name ->
        val preset = payload?.modelPresets?.firstOrNull { it.name == name }
        AlertDialog(
            onDismissRequest = { if (!state.pending.contains("model-configuration")) deletingModelName = null },
            title = { Text("Delete model configuration?") },
            text = { Text("${preset?.label ?: name} will be removed from the gateway. A model in the call order must be moved first.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModelConfiguration(name)
                        deletingModelName = null
                    },
                    enabled = !state.pending.contains("model-configuration"),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingModelName = null }) { Text("Cancel") } },
        )
    }
}

private data class ModelConfigurationForm(
    val label: String,
    val name: String?,
    val model: String,
    val provider: String,
    val maxTokens: Int?,
    val contextWindowTokens: Int?,
    val temperature: Double?,
    val reasoningEffort: String?,
)
@Composable
private fun ModelConfigurationDialog(
    providers: List<Pair<String, String>>,
    initial: com.nanobotkt.core.model.ModelPresetInfo?,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (ModelConfigurationForm) -> Unit,
) {
    val editing = initial != null
    var label by rememberSaveable(initial?.name) { mutableStateOf(initial?.label.orEmpty()) }
    var name by rememberSaveable(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }
    var model by rememberSaveable(initial?.name) { mutableStateOf(initial?.model.orEmpty()) }
    var provider by rememberSaveable(initial?.name) {
        mutableStateOf(initial?.provider ?: providers.firstOrNull()?.first.orEmpty())
    }
    var maxTokens by rememberSaveable(initial?.name) { mutableStateOf(initial?.maxTokens?.takeIf { it > 0 }?.toString().orEmpty()) }
    var contextWindow by rememberSaveable(initial?.name) {
        mutableStateOf(initial?.contextWindowTokens?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var temperature by rememberSaveable(initial?.name) {
        // 0.0 是服务端允许的合法值，不能和“未设置”混为一谈。
        mutableStateOf(initial?.temperature?.toString().orEmpty())
    }
    var reasoningEffort by rememberSaveable(initial?.name) { mutableStateOf(initial?.reasoningEffort.orEmpty()) }

    val maxTokensValue = maxTokens.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
    val contextWindowValue = contextWindow.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
    val temperatureValue = temperature.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val numericValuesValid = (maxTokensValue == null || maxTokensValue > 0) &&
        (contextWindowValue == null || contextWindowValue > 0) &&
        (temperatureValue == null || temperatureValue in 0.0..2.0)
    val valid = label.isNotBlank() && model.isNotBlank() && provider.isNotBlank() && numericValuesValid
    val reasoningOptions = initial?.reasoningEffortValues.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Edit model configuration" else "Add model configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                if (!editing) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                } else {
                    OutlinedTextField(name, {}, label = { Text("Name") }, singleLine = true, readOnly = true)
                }
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true)
                PillPicker(
                    value = provider,
                    options = (listOf("auto" to "Auto") + providers).withCurrent(provider),
                    onSelected = { provider = it },
                )
                OutlinedTextField(
                    maxTokens,
                    { maxTokens = it },
                    label = { Text("Max tokens (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    contextWindow,
                    { contextWindow = it },
                    label = { Text("Context window (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    temperature,
                    { temperature = it },
                    label = { Text("Temperature (0–2, optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (reasoningOptions.isNotEmpty()) {
                    PillPicker(
                        value = reasoningEffort,
                        options = reasoningOptions.map { it to if (it.isBlank()) "Default" else it },
                        onSelected = { reasoningEffort = it },
                    )
                } else {
                    OutlinedTextField(
                        reasoningEffort,
                        { reasoningEffort = it },
                        label = { Text("Reasoning effort (optional)") },
                        singleLine = true,
                    )
                }
                if (!numericValuesValid) {
                    Text("Numeric values are invalid. Check the allowed ranges.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ModelConfigurationForm(
                            label = label.trim(),
                            name = name.trim().takeIf(String::isNotBlank),
                            model = model.trim(),
                            provider = provider,
                            maxTokens = maxTokensValue,
                            contextWindowTokens = contextWindowValue,
                            temperature = temperatureValue,
                            reasoningEffort = reasoningEffort.trim().takeIf(String::isNotBlank),
                        ),
                    )
                },
                enabled = valid && !saving,
            ) { Text(if (saving) "Saving…" else if (editing) "Save" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

/**
 * 将服务端返回的结构化 Provider 字段重新编码为合法 JSON 文本。
 *
 * 不能使用 `Map.toString()`：它生成的是 `{key=value}`，而服务端按 JSON
 * 解析 extra_headers/extra_query；用户只修改其他字段时也会把这些旧值一并提交。
 */
private fun Map<String, String>.toJsonObjectString(): String = Json.encodeToString(this)

/**
 * Provider 编辑器同时覆盖标准 Provider 与自定义 Provider。
 * 服务端只接受 provider 声明的 advanced_fields，因此未知字段仍显示为可编辑文本，
 * 但保存时只提交协议允许的字段，避免客户端构造出服务端无法解析的配置。
 */
@Composable
private fun ProviderEditDialog(
    provider: com.nanobotkt.core.model.ProviderSettingsInfo,
    state: SettingsUiState,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderUpdate) -> Unit,
    onOAuthLogin: () -> Unit,
    onOAuthComplete: (String, String?) -> Unit,
    onOAuthLogout: () -> Unit,
) {
    val isCustom = provider.isCustom == true
    var displayName by rememberSaveable(provider.name) { mutableStateOf(provider.label) }
    var apiBase by rememberSaveable(provider.name) { mutableStateOf(provider.apiBase.orEmpty()) }
    var apiKey by rememberSaveable(provider.name) { mutableStateOf("") }
    var apiType by rememberSaveable(provider.name) { mutableStateOf(provider.apiType.orEmpty()) }
    var proxy by rememberSaveable(provider.name) { mutableStateOf(provider.proxy.orEmpty()) }
    var thinkingStyle by rememberSaveable(provider.name) { mutableStateOf(provider.thinkingStyle.orEmpty()) }
    var region by rememberSaveable(provider.name) { mutableStateOf(provider.region.orEmpty()) }
    var profile by rememberSaveable(provider.name) { mutableStateOf(provider.profile.orEmpty()) }
    var extraHeaders by rememberSaveable(provider.name) { mutableStateOf(provider.extraHeaders?.toJsonObjectString().orEmpty()) }
    var extraBody by rememberSaveable(provider.name) { mutableStateOf(provider.extraBody?.toString().orEmpty()) }
    var extraQuery by rememberSaveable(provider.name) { mutableStateOf(provider.extraQuery?.toJsonObjectString().orEmpty()) }
    var editingApiKey by rememberSaveable(provider.name) { mutableStateOf(false) }
    var clearApiKey by rememberSaveable(provider.name) { mutableStateOf(false) }
    var oauthCode by rememberSaveable(provider.name) { mutableStateOf("") }

    val advanced = provider.advancedFields.orEmpty()
    val oauth = state.oauth?.takeIf { it.provider == provider.name }
    val oauthPending = "oauth:${provider.name}" in state.pending
    val dirty = (isCustom && displayName != provider.label) ||
        apiBase != provider.apiBase.orEmpty() ||
        apiType != provider.apiType.orEmpty() ||
        proxy != provider.proxy.orEmpty() ||
        thinkingStyle != provider.thinkingStyle.orEmpty() ||
        region != provider.region.orEmpty() ||
        profile != provider.profile.orEmpty() ||
        extraHeaders != provider.extraHeaders?.toJsonObjectString().orEmpty() ||
        extraBody != provider.extraBody?.toString().orEmpty() ||
        extraQuery != provider.extraQuery?.toJsonObjectString().orEmpty() ||
        editingApiKey && apiKey.isNotBlank() || clearApiKey

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${provider.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCustom) {
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("Provider name") }, singleLine = true)
                }
                if (provider.apiKeyRequired != false && provider.authType != "oauth") {
                    if (editingApiKey || provider.apiKeyHint.isNullOrBlank()) {
                        SecretPillTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; clearApiKey = false },
                            placeholder = if (provider.apiKeyHint.isNullOrBlank()) "Enter API key" else "Replacement API key",
                            visible = false,
                            onToggleVisibility = {},
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingApiKey = false; apiKey = "" }) { Text("Keep existing") }
                            if (!provider.apiKeyHint.isNullOrBlank()) {
                                TextButton(onClick = { clearApiKey = true; editingApiKey = false; apiKey = "" }) {
                                    Text("Clear", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    } else {
                        StoredSecretField(hint = provider.apiKeyHint ?: "Configured", onEdit = { editingApiKey = true })
                        TextButton(onClick = { clearApiKey = true }) {
                            Text("Clear stored key", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (provider.authType != "oauth") {
                    OutlinedTextField(
                        apiBase,
                        { apiBase = it },
                        label = { Text("API base") },
                        placeholder = { Text(provider.defaultApiBase.orEmpty()) },
                        singleLine = true,
                    )
                }
                advanced.forEach { field ->
                    when (field) {
                        "api_type" -> OutlinedTextField(apiType, { apiType = it }, label = { Text("API type") }, singleLine = true)
                        "proxy" -> OutlinedTextField(proxy, { proxy = it }, label = { Text("Proxy") }, singleLine = true)
                        "thinking_style" -> OutlinedTextField(thinkingStyle, { thinkingStyle = it }, label = { Text("Thinking style") }, singleLine = true)
                        "region" -> OutlinedTextField(region, { region = it }, label = { Text("Region") }, singleLine = true)
                        "profile" -> OutlinedTextField(profile, { profile = it }, label = { Text("Profile") }, singleLine = true)
                        "extra_headers" -> OutlinedTextField(extraHeaders, { extraHeaders = it }, label = { Text("Extra headers (JSON)") }, minLines = 2)
                        "extra_body" -> OutlinedTextField(extraBody, { extraBody = it }, label = { Text("Extra body (JSON)") }, minLines = 2)
                        "extra_query" -> OutlinedTextField(extraQuery, { extraQuery = it }, label = { Text("Extra query (JSON)") }, minLines = 2)
                        else -> Text("Unsupported advanced field: $field", color = SecondaryText, fontSize = 12.sp)
                    }
                }
                if (provider.oauthLoginSupported == true) {
                    HorizontalDivider()
                    Text("OAuth", fontWeight = FontWeight.SemiBold, color = PrimaryText)
                    if (!provider.oauthAccount.isNullOrBlank()) {
                        Text("Signed in as ${provider.oauthAccount}", color = SecondaryText, fontSize = 12.sp)
                        TextButton(onClick = onOAuthLogout, enabled = !oauthPending) {
                            Text(if (oauthPending) "Signing out…" else "Sign out")
                        }
                    } else {
                        Text("This provider uses an interactive OAuth flow.", color = SecondaryText, fontSize = 12.sp)
                        TextButton(onClick = onOAuthLogin, enabled = !oauthPending) {
                            Text(if (oauthPending) "Starting…" else "Start OAuth login")
                        }
                    }
                    if (oauth?.authorizationUrl != null) {
                        Text("Open this URL in a browser:", color = SecondaryText, fontSize = 12.sp)
                        Text(oauth.authorizationUrl.orEmpty(), color = PrimaryText, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(oauthCode, { oauthCode = it }, label = { Text("Authorization code (if requested)") }, singleLine = true)
                        TextButton(
                            onClick = { onOAuthComplete(oauth.flowId.orEmpty(), oauthCode.trim().takeIf(String::isNotBlank)) },
                            enabled = !oauthPending && !oauth.flowId.isNullOrBlank(),
                        ) { Text(if (oauthPending) "Completing…" else "Complete OAuth login") }
                    }
                }
                if (!state.error.isNullOrBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ProviderUpdate(
                            provider = provider.name,
                            displayName = displayName.trim().takeIf { isCustom },
                            apiKey = when {
                                clearApiKey -> ""
                                editingApiKey && apiKey.isNotBlank() -> apiKey.trim()
                                else -> null
                            },
                            apiBase = apiBase.trim().takeIf { provider.authType != "oauth" },
                            apiType = apiType.trim().takeIf { "api_type" in advanced },
                            proxy = proxy.trim().takeIf { "proxy" in advanced || provider.proxy != null || provider.authType == "oauth" },
                            thinkingStyle = thinkingStyle.trim().takeIf { "thinking_style" in advanced },
                            region = region.trim().takeIf { "region" in advanced },
                            profile = profile.trim().takeIf { "profile" in advanced },
                            extraHeaders = extraHeaders.trim().takeIf { "extra_headers" in advanced },
                            extraBody = extraBody.trim().takeIf { "extra_body" in advanced || provider.authType == "oauth" },
                            extraQuery = extraQuery.trim().takeIf { "extra_query" in advanced },
                        ),
                    )
                },
                enabled = dirty && !saving && displayName.isNotBlank(),
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun CustomProviderDialog(
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (CustomProviderCreate) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var apiBase by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var proxy by rememberSaveable { mutableStateOf("") }
    var thinkingStyle by rememberSaveable { mutableStateOf("") }
    var extraHeaders by rememberSaveable { mutableStateOf("") }
    var extraBody by rememberSaveable { mutableStateOf("") }
    var extraQuery by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && apiBase.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Provider name") }, singleLine = true)
                OutlinedTextField(apiBase, { apiBase = it }, label = { Text("API base") }, singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (optional)") }, singleLine = true)
                OutlinedTextField(proxy, { proxy = it }, label = { Text("Proxy (optional)") }, singleLine = true)
                OutlinedTextField(thinkingStyle, { thinkingStyle = it }, label = { Text("Thinking style (optional)") }, singleLine = true)
                OutlinedTextField(extraHeaders, { extraHeaders = it }, label = { Text("Extra headers (JSON object, optional)") }, minLines = 2)
                OutlinedTextField(extraBody, { extraBody = it }, label = { Text("Extra body (JSON object, optional)") }, minLines = 2)
                OutlinedTextField(extraQuery, { extraQuery = it }, label = { Text("Extra query (JSON object, optional)") }, minLines = 2)
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        CustomProviderCreate(
                            displayName = name.trim(),
                            apiBase = apiBase.trim(),
                            apiKey = apiKey.trim().takeIf(String::isNotBlank),
                            proxy = proxy.trim().takeIf(String::isNotBlank),
                            thinkingStyle = thinkingStyle.trim().takeIf(String::isNotBlank),
                            extraHeaders = extraHeaders.trim().takeIf(String::isNotBlank),
                            extraBody = extraBody.trim().takeIf(String::isNotBlank),
                            extraQuery = extraQuery.trim().takeIf(String::isNotBlank),
                        ),
                    )
                },
                enabled = valid && !saving,
            ) { Text(if (saving) "Saving…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun SystemPage(state: SettingsUiState, viewModel: SettingsViewModel) {
    val payload = state.payload
    val service = state.apiService

    SettingsGroup("Runtime") {
        SettingsRow(
            icon = Icons.Outlined.Dns,
            title = "Gateway",
            subtitle = if (payload == null) "Not connected" else "Ready",
            value = payload?.runtime?.let {
                if (it.gatewayHost.isNotBlank() && it.gatewayPort > 0) "${it.gatewayHost}:${it.gatewayPort}" else "Unavailable"
            } ?: "Unavailable",
            showChevron = false,
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = "Workspace",
            subtitle = shortPath(payload?.runtime?.workspacePath),
            value = "Default workspace",
            showChevron = false,
        )
    }

    GroupSpacer()
    SettingsGroup("API service") {
        PreferenceBlock(
            title = if (service?.running == true) "Running" else "Stopped",
            description = service?.endpoint?.takeIf { it.isNotBlank() } ?: "Local API service is unavailable.",
        ) {
            SegmentedSetting(
                options = listOf("Stop", "Start"),
                selectedIndex = if (service?.running == true) 1 else 0,
                onSelected = { index ->
                    if (index == 1 && service?.running != true) viewModel.apiService(true)
                    if (index == 0 && service?.running == true) viewModel.apiService(false)
                },
            )
        }
    }

}

@Composable
private fun SecurityPage(state: SettingsUiState, viewModel: SettingsViewModel) {
    val payload = state.payload
    val advanced = payload?.advanced
    val currentLocalAccess = advanced?.webuiAllowLocalServiceAccess == true
    val currentAccessMode = advanced?.webuiDefaultAccessMode ?: "default"
    var localAccess by rememberSaveable(currentLocalAccess) { mutableStateOf(currentLocalAccess) }
    var accessMode by rememberSaveable(currentAccessMode) { mutableStateOf(currentAccessMode) }
    val dirty = localAccess != currentLocalAccess || accessMode != currentAccessMode
    val saving = "network" in state.pending
    val nativeSurface = payload?.runtimeSurface == RuntimeSurface.NATIVE

    SettingsGroup(if (nativeSurface) "App safety" else "Web safety") {
        PreferenceBlock(
            title = "Local Service Access",
            description = if (nativeSurface) {
                "Allow Full Access shell commands to reach services on this device."
            } else {
                "Allow Full Access shell commands to reach localhost services."
            },
        ) {
            ToggleSetting(
                checked = localAccess,
                onCheckedChange = { localAccess = it },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = "Default access",
            description = if (nativeSurface) {
                "Used by native chats without a project-specific permission."
            } else {
                "Used by web chats without a project-specific permission."
            },
        ) {
            SegmentedSetting(
                options = listOf("Default Permission", "Full Access"),
                selectedIndex = if (accessMode == "full") 1 else 0,
                onSelected = { accessMode = if (it == 1) "full" else "default" },
            )
        }
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("runtime", "security"),
            error = state.error,
            onSave = { viewModel.network(localAccess, accessMode) },
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text = "Web fetches always protect local, private, and metadata services. Core channel safety stays in config.json.",
        modifier = Modifier.padding(horizontal = 4.dp),
        color = SecondaryText,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
}

@Composable
private fun ImageGenerationPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onOpenProviders: () -> Unit,
    onSave: (ImageGenerationSettingsUpdate) -> Unit,
) {
    val settings = state.payload?.imageGeneration
    if (settings == null) {
        UnavailableSettingsPage("Image generation")
        return
    }

    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var aspect by rememberSaveable(settings.defaultAspectRatio) { mutableStateOf(settings.defaultAspectRatio) }
    var imageSize by rememberSaveable(settings.defaultImageSize) { mutableStateOf(settings.defaultImageSize) }
    var maxImages by rememberSaveable(settings.maxImagesPerTurn) { mutableStateOf(settings.maxImagesPerTurn) }

    // 服务端返回 provider 列表时，未知 provider 不能静默回退到第一项，
    // 否则会把另一个 provider 的凭据状态和模型误显示到当前配置上。
    // provider 列表非空时严格按当前 provider 匹配；列表为空才使用服务端
    // 给出的整体 configured 状态，避免未知 provider 借用第一项的状态。
    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
    val providerConfigured = if (settings.providers.isEmpty()) {
        settings.providerConfigured
    } else {
        selectedProvider?.configured == true
    }
    val dirty = enabled != settings.enabled ||
        provider != settings.provider ||
        model != settings.model ||
        aspect != settings.defaultAspectRatio ||
        imageSize != settings.defaultImageSize ||
        maxImages != settings.maxImagesPerTurn
    val saving = "image" in state.pending

    SettingsGroup("Image generation") {
        FormSettingRow("Image generation") {
            ToggleSetting(checked = enabled, onCheckedChange = { enabled = it })
        }
        CardDivider()
        FormSettingRow("Image provider") {
            PillPicker(
                value = provider,
                options = settings.providers.map { it.name to it.label }.withCurrent(provider),
                showProviderLogos = showBrandLogos,
                onSelected = { next ->
                    provider = next
                    settings.providers.firstOrNull { it.name == next }?.let { row ->
                        model = row.defaultModel ?: row.models.firstOrNull() ?: model
                    }
                },
            )
        }
        CardDivider()
        FormSettingRow(
            title = "Provider status",
            description = "Image generation reuses provider credentials from Providers.",
        ) {
            StatusPill(
                text = if (providerConfigured) "Configured" else "Not configured",
                positive = providerConfigured,
            )
            if (!providerConfigured) {
                Spacer(Modifier.height(9.dp))
                OutlinePillButton("Configure provider", onOpenProviders)
            }
        }
        CardDivider()
        ReadOnlyFormRow(
            title = "Provider base",
            value = selectedProvider?.apiBase
                ?: selectedProvider?.defaultApiBase
                ?: selectedProvider?.name
                ?: "Not available",
        )
    }

    GroupSpacer()
    SettingsGroup("Defaults") {
        FormSettingRow("Image model") {
            ModelIdPicker(
                provider = provider,
                providerConfigured = providerConfigured,
                showProviderLogos = showBrandLogos,
                value = model,
                models = selectedProvider?.models.orEmpty(),
                onSelected = { model = it },
            )
        }
        CardDivider()
        FormSettingRow("Default aspect") {
            PillPicker(
                value = aspect,
                options = IMAGE_ASPECT_RATIOS.map { it to it }.withCurrent(aspect),
                onSelected = { aspect = it },
            )
        }
        CardDivider()
        FormSettingRow("Default size") {
            PillPicker(
                value = imageSize,
                options = IMAGE_SIZES.map { it to it }.withCurrent(imageSize),
                onSelected = { imageSize = it },
            )
        }
        CardDivider()
        FormSettingRow("Max images per turn") {
            NumberStepper(value = maxImages, range = 1..8, onValueChange = { maxImages = it })
        }
        CardDivider()
        ReadOnlyFormRow("Save directory", settings.saveDir.ifBlank { "Not available" })
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("image", "runtime"),
            disabledMessage = if (enabled && !providerConfigured) {
                "Configure this provider before enabling image generation."
            } else {
                null
            },
            error = state.error,
            onSave = {
                onSave(
                    ImageGenerationSettingsUpdate(
                        enabled = enabled,
                        provider = provider,
                        model = model.trim(),
                        defaultAspectRatio = aspect,
                        defaultImageSize = imageSize,
                        maxImagesPerTurn = maxImages,
                    ),
                )
            },
        )
    }
}

@Composable
private fun TranscriptionPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onOpenProviders: () -> Unit,
    onSave: (TranscriptionSettingsUpdate) -> Unit,
) {
    val settings = state.payload?.transcription
    if (settings == null) {
        // 与 Image/Web section 保持一致：网关没有暴露 transcription 配置时，
        // 不展示可编辑的本地默认值，避免用户误保存一份虚构配置。
        UnavailableSettingsPage("Voice input")
        return
    }

    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var language by rememberSaveable(settings.language) { mutableStateOf(settings.language.orEmpty()) }
    var maxDuration by rememberSaveable(settings.maxDurationSec) { mutableStateOf(settings.maxDurationSec) }
    var maxUpload by rememberSaveable(settings.maxUploadMb) { mutableStateOf(settings.maxUploadMb) }

    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
    val providerConfigured = if (settings.providers.isEmpty()) {
        settings.providerConfigured
    } else {
        selectedProvider?.configured == true
    }
    val dirty = enabled != settings.enabled ||
        provider != settings.provider ||
        model != settings.model ||
        language != settings.language.orEmpty() ||
        maxDuration != settings.maxDurationSec ||
        maxUpload != settings.maxUploadMb
    val saving = "voice" in state.pending

    SettingsGroup("Voice input") {
        FormSettingRow(
            title = "Transcription",
            description = "Transcribe microphone input before sending it. Chat channel voice messages use the same settings.",
        ) {
            ToggleSetting(checked = enabled, onCheckedChange = { enabled = it })
        }
        CardDivider()
        FormSettingRow("Provider") {
            PillPicker(
                value = provider,
                options = settings.providers.map { it.name to it.label }.withCurrent(provider),
                showProviderLogos = showBrandLogos,
                onSelected = { provider = it },
            )
        }
        CardDivider()
        FormSettingRow(
            title = "Provider status",
            description = "API keys stay under providers, not in transcription settings.",
        ) {
            StatusPill(
                text = if (providerConfigured) "Configured" else "Not configured",
                positive = providerConfigured,
            )
            if (!providerConfigured) {
                Spacer(Modifier.height(9.dp))
                OutlinePillButton("Configure provider", onOpenProviders)
            }
        }
        CardDivider()
        FormSettingRow(
            title = "Model",
            description = "Leave as the resolved default unless your provider needs a custom model id.",
        ) {
            PillTextField(value = model, onValueChange = { model = it })
        }
        CardDivider()
        FormSettingRow(
            title = "Language",
            description = "Optional ISO-639 hint such as en, zh, ja, or ko.",
        ) {
            PillTextField(value = language, onValueChange = { language = it }, placeholder = "Auto")
        }
        CardDivider()
        FormSettingRow("Limits") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberStepper(value = maxDuration, range = 1..600, suffix = "s", onValueChange = { maxDuration = it })
                NumberStepper(value = maxUpload, range = 1..100, suffix = "MB", onValueChange = { maxUpload = it })
            }
        }
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("runtime", "voice", "transcription"),
            error = state.error,
            onSave = {
                onSave(
                    TranscriptionSettingsUpdate(
                        enabled = enabled,
                        provider = provider,
                        model = model.trim(),
                        language = language.trim(),
                        maxDurationSec = maxDuration,
                        maxUploadMb = maxUpload,
                    ),
                )
            },
        )
    }
}

@Composable
private fun WebSearchPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onSave: (WebSearchSettingsUpdate) -> Unit,
) {
    val settings = state.payload?.webSearch
    val web = state.payload?.web
    if (settings == null || web == null) {
        UnavailableSettingsPage("Web search")
        return
    }

    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var apiKey by rememberSaveable(settings.provider) { mutableStateOf("") }
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl.orEmpty()) }
    var maxResults by rememberSaveable(settings.maxResults) { mutableStateOf(settings.maxResults) }
    var timeout by rememberSaveable(settings.timeout) { mutableStateOf(settings.timeout) }
    var useJinaReader by rememberSaveable(web.fetch.useJinaReader) { mutableStateOf(web.fetch.useJinaReader) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var keyEditing by rememberSaveable { mutableStateOf(false) }
    var wasSaving by remember { mutableStateOf(false) }

    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
        ?: settings.providers.firstOrNull()
    val acceptsApiKey = selectedProvider?.credential == "api_key" || selectedProvider?.credential == "optional_api_key"
    val requiresApiKey = selectedProvider?.credential == "api_key"
    val hasExistingSecret = acceptsApiKey && provider == settings.provider && !settings.apiKeyHint.isNullOrBlank()
    val showKeyInput = acceptsApiKey && (!hasExistingSecret || keyEditing)
    val missingCredential = (requiresApiKey && apiKey.isBlank() && !hasExistingSecret) ||
        (selectedProvider?.credential == "base_url" && baseUrl.isBlank())
    val dirty = provider != settings.provider ||
        apiKey.isNotBlank() ||
        baseUrl != settings.baseUrl.orEmpty() ||
        maxResults != settings.maxResults ||
        timeout != settings.timeout ||
        useJinaReader != web.fetch.useJinaReader
    val saving = "web" in state.pending

    LaunchedEffect(saving) {
        if (wasSaving && !saving && state.error == null) {
            apiKey = ""
            keyEditing = false
            showApiKey = false
        }
        wasSaving = saving
    }

    SettingsGroup("Web search") {
        FormSettingRow("Provider") {
            PillPicker(
                value = provider,
                options = settings.providers.map { it.name to it.label }.withCurrent(provider),
                showProviderLogos = showBrandLogos,
                onSelected = {
                    provider = it
                    apiKey = ""
                    keyEditing = false
                    showApiKey = false
                    baseUrl = if (it == settings.provider) settings.baseUrl.orEmpty() else ""
                },
            )
        }
        if (selectedProvider?.credential == "none") {
            CardDivider()
            FormSettingRow("Credentials") {
                StatusPill("No credential required", positive = true)
            }
        }
        if (acceptsApiKey) {
            CardDivider()
            FormSettingRow(
                title = "API key",
                description = "Stored by the gateway and never shown in full again.",
            ) {
                if (showKeyInput) {
                    SecretPillTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = if (hasExistingSecret) "Enter a replacement key" else "Enter API key",
                        visible = showApiKey,
                        onToggleVisibility = { showApiKey = !showApiKey },
                    )
                } else {
                    StoredSecretField(
                        hint = settings.apiKeyHint ?: "Configured",
                        onEdit = { keyEditing = true },
                    )
                }
            }
        }
        if (selectedProvider?.credential == "base_url") {
            CardDivider()
            FormSettingRow(
                title = "Base URL",
                description = "Endpoint used by this search provider.",
            ) {
                PillTextField(value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://…")
            }
        }
    }

    GroupSpacer()
    SettingsGroup("Behavior") {
        FormSettingRow("Max results") {
            NumberStepper(value = maxResults, range = 1..10, onValueChange = { maxResults = it })
        }
        CardDivider()
        FormSettingRow("Timeout") {
            NumberStepper(value = timeout, range = 1..120, suffix = "s", onValueChange = { timeout = it })
        }
        CardDivider()
        FormSettingRow(
            title = "Jina reader",
            description = "Use Jina Reader for web_fetch when available.",
        ) {
            ToggleSetting(checked = useJinaReader, onCheckedChange = { useJinaReader = it })
        }
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("runtime", "browser", "web"),
            disabledMessage = if (missingCredential) "Enter the credential required by this provider." else null,
            error = state.error,
            onSave = {
                onSave(
                    WebSearchSettingsUpdate(
                        provider = provider,
                        apiKey = apiKey.trim().let { key ->
                            when {
                                key.isNotEmpty() -> key
                                selectedProvider?.credential == "optional_api_key" && keyEditing -> ""
                                else -> null
                            }
                        },
                        baseUrl = baseUrl.trim().takeIf { selectedProvider?.credential == "base_url" },
                        maxResults = maxResults,
                        timeout = timeout,
                        useJinaReader = useJinaReader,
                    ),
                )
            },
        )
    }
}

@Composable
private fun UnavailableSettingsPage(title: String) {
    SettingsGroup(title) {
        PreferenceBlock(
            title = "Unavailable",
            description = "This gateway did not expose this settings section.",
        )
    }
}


@Composable
private fun OpenSectionPage(
    title: String,
    description: String,
    icon: ImageVector,
    onOpen: () -> Unit,
) {
    SettingsGroup(title) {
        EmptySettingsRow(
            icon = icon,
            title = "Open $title",
            subtitle = description,
            action = "Open",
            onClick = onOpen,
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        color = PrimaryText.copy(alpha = 0.85f),
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        color = CardBackground,
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    selected: Boolean = false,
    showChevron: Boolean = true,
    leadingProvider: String? = null,
    valueLogoProvider: String? = null,
    showBrandLogos: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** 可选的尾部操作，避免为了增加编辑/排序按钮而改变整行点击语义。 */
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!leadingProvider.isNullOrBlank()) {
            ProviderMark(
                provider = leadingProvider,
                showBrandLogos = showBrandLogos,
                size = ProviderMarkSize.LIST,
                fallbackIcon = icon,
            )
        } else {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (settingsDark) Color(0xFF383838) else Color(0xFFF0F0EF),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = PrimaryText.copy(alpha = 0.82f), modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = PrimaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!valueLogoProvider.isNullOrBlank() && showBrandLogos && providerBrand(valueLogoProvider) != null) {
            Spacer(Modifier.width(8.dp))
            ProviderMark(
                provider = valueLogoProvider,
                showBrandLogos = true,
                size = ProviderMarkSize.PICKER,
                fallbackIcon = icon,
                hideWhenUnavailable = true,
            )
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = SecondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent?.let {
            Spacer(Modifier.width(4.dp))
            it()
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFFA8A8A8),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptySettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        value = action,
        showChevron = onClick != null,
        onClick = onClick,
    )
}

@Composable
private fun PreferenceBlock(
    title: String,
    description: String,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Text(title, color = PrimaryText, fontSize = 15.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(4.dp))
        Text(description, color = SecondaryText, fontSize = 13.sp, lineHeight = 19.sp)
        if (content != null) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun FormSettingRow(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = PrimaryText,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(11.dp))
        content()
    }
}

@Composable
private fun ReadOnlyFormRow(title: String, value: String) {
    FormSettingRow(title) {
        Text(
            text = value,
            color = SecondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PillPicker(
    value: String,
    options: List<Pair<String, String>>,
    showProviderLogos: Boolean = false,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "Select" }
    Box(Modifier.width(210.dp)) {
        Surface(
            modifier = Modifier
                .width(210.dp)
                .height(38.dp)
                .clickable(enabled = options.isNotEmpty()) { expanded = true },
            shape = RoundedCornerShape(19.dp),
            color = PageBackground,
            border = BorderStroke(1.dp, DividerColor),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showProviderLogos && value.isNotBlank()) {
                    ProviderMark(
                        provider = value,
                        showBrandLogos = true,
                        size = ProviderMarkSize.PICKER,
                        fallbackIcon = Icons.Outlined.Dns,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = currentLabel,
                    modifier = Modifier.weight(1f),
                    color = PrimaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = SecondaryText,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(240.dp)
                .heightIn(max = 288.dp)
                .background(PageBackground),
        ) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    leadingIcon = if (showProviderLogos) {
                        {
                            ProviderMark(
                                provider = optionValue,
                                showBrandLogos = true,
                                size = ProviderMarkSize.PICKER,
                                fallbackIcon = Icons.Outlined.Dns,
                            )
                        }
                    } else {
                        null
                    },
                    text = { Text(label, color = PrimaryText, fontSize = 13.sp) },
                    trailingIcon = {
                        if (optionValue == value) {
                            Icon(Icons.Rounded.Check, null, tint = PrimaryText, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(optionValue)
                    },
                )
            }
        }
    }
}

private enum class ProviderMarkSize {
    PICKER,
    LIST,
}

@Composable
private fun ProviderMark(
    provider: String?,
    showBrandLogos: Boolean,
    size: ProviderMarkSize,
    fallbackIcon: ImageVector,
    unconfigured: Boolean = false,
    hideWhenUnavailable: Boolean = false,
) {
    val containerSize = if (size == ProviderMarkSize.LIST) 40.dp else 20.dp
    if (unconfigured) {
        Box(
            modifier = Modifier.size(containerSize),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (settingsDark) Color(0xFFFDE68A) else Color(0xFFB45309),
                modifier = Modifier.size(if (size == ProviderMarkSize.LIST) 20.dp else 16.dp),
            )
        }
        return
    }

    val brand = providerBrand(provider)
    if (showBrandLogos && brand != null) {
        RemoteProviderBrandMark(brand = brand, size = size)
        return
    }
    if (hideWhenUnavailable) return

    Surface(
        modifier = Modifier.size(containerSize),
        shape = RoundedCornerShape(if (size == ProviderMarkSize.LIST) 14.dp else 6.dp),
        color = SegmentBackground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = PrimaryText.copy(alpha = 0.82f),
                modifier = Modifier.size(if (size == ProviderMarkSize.LIST) 20.dp else 12.dp),
            )
        }
    }
}

@Composable
private fun RemoteProviderBrandMark(
    brand: ProviderBrand,
    size: ProviderMarkSize,
) {
    var logoIndex by remember(brand.logoUrls) { mutableStateOf(0) }
    var loaded by remember(brand.logoUrls) { mutableStateOf(false) }
    val logoUrl = brand.logoUrls.getOrNull(logoIndex)
    val containerSize = if (size == ProviderMarkSize.LIST) 40.dp else 20.dp
    val imageSize = if (size == ProviderMarkSize.LIST) 24.dp else 14.dp
    val cornerRadius = if (size == ProviderMarkSize.LIST) 14.dp else 6.dp

    LaunchedEffect(logoUrl) { loaded = false }

    Surface(
        modifier = Modifier.size(containerSize),
        shape = RoundedCornerShape(cornerRadius),
        color = if (loaded) PageBackground else Color(brand.color),
        border = if (loaded) BorderStroke(1.dp, DividerColor.copy(alpha = 0.45f)) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!loaded) {
                Text(
                    text = brand.initials,
                    color = Color.White,
                    fontSize = if (size == ProviderMarkSize.LIST) 11.sp else 7.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(imageSize)
                        .alpha(if (loaded) 1f else 0f),
                    onSuccess = { loaded = true },
                    onError = {
                        if (logoIndex < brand.logoUrls.lastIndex) {
                            logoIndex += 1
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VersionCheckRow(
    version: String,
    updateText: String?,
    checked: Boolean,
    checking: Boolean,
    onCheckVersion: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Version",
            color = PrimaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (version == "nanobot") version else "v$version",
            color = SecondaryText,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(11.dp))
        OutlinePillButton(
            text = if (checking) "Checking..." else "Check for updates",
            onClick = onCheckVersion,
            enabled = !checking,
            icon = Icons.Outlined.ArrowCircleUp,
        )
        val status = updateText ?: if (checked) "You're up to date" else null
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                color = if (updateText != null) Color(0xFF2997FF) else Color(0xFF2E9B59),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun ModelIdPicker(
    provider: String,
    providerConfigured: Boolean,
    showProviderLogos: Boolean,
    value: String,
    models: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by rememberSaveable(value, expanded) { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val visibleModels = models
        .distinct()
        .filter { trimmedQuery.isBlank() || it.contains(trimmedQuery, ignoreCase = true) }
        .take(80)
    val showCustom = trimmedQuery.isNotBlank() && models.none { it == trimmedQuery } && trimmedQuery != value

    Box(Modifier.width(224.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clickable { expanded = true },
            shape = RoundedCornerShape(19.dp),
            color = PageBackground,
            border = BorderStroke(1.dp, DividerColor),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderMark(
                    provider = provider,
                    showBrandLogos = showProviderLogos,
                    size = ProviderMarkSize.PICKER,
                    fallbackIcon = Icons.Outlined.SmartToy,
                    unconfigured = !providerConfigured,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = value.ifBlank { "Select image model" },
                    modifier = Modifier.weight(1f),
                    color = if (value.isBlank()) SecondaryText else PrimaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(280.dp)
                .heightIn(max = 330.dp)
                .background(PageBackground),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .width(268.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                placeholder = { Text("Search or type model ID", color = SecondaryText, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, null, tint = SecondaryText, modifier = Modifier.size(16.dp))
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText, fontSize = 12.sp),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PrimaryText,
                    unfocusedTextColor = PrimaryText,
                    focusedContainerColor = PageBackground,
                    unfocusedContainerColor = PageBackground,
                    focusedBorderColor = PrimaryText.copy(alpha = 0.28f),
                    unfocusedBorderColor = DividerColor,
                    cursorColor = PrimaryText,
                ),
            )
            if (models.isEmpty() && trimmedQuery.isBlank()) {
                Text(
                    text = "Type the model ID supported by this provider.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = SecondaryText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            visibleModels.forEach { modelId ->
                DropdownMenuItem(
                    leadingIcon = {
                        ProviderMark(
                            provider = provider,
                            showBrandLogos = showProviderLogos,
                            size = ProviderMarkSize.PICKER,
                            fallbackIcon = Icons.Outlined.SmartToy,
                            unconfigured = !providerConfigured,
                        )
                    },
                    text = {
                        Text(modelId, color = PrimaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = {
                        if (modelId == value) {
                            Icon(Icons.Rounded.Check, null, tint = PrimaryText, modifier = Modifier.size(15.dp))
                        }
                    },
                    onClick = {
                        onSelected(modelId)
                        expanded = false
                    },
                )
            }
            if (showCustom) {
                if (visibleModels.isNotEmpty()) CardDivider()
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(Icons.Outlined.Edit, null, tint = SecondaryText, modifier = Modifier.size(15.dp))
                    },
                    text = {
                        Text(
                            text = "Use “$trimmedQuery”",
                            color = PrimaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelected(trimmedQuery)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SecretPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .width(280.dp)
            .defaultMinSize(minHeight = 42.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText, fontSize = 13.sp),
        placeholder = {
            Text(placeholder, color = SecondaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingIcon = {
            Icon(
                imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (visible) "Hide API key" else "Show API key",
                tint = SecondaryText,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleVisibility)
                    .padding(6.dp),
            )
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(21.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PrimaryText,
            unfocusedTextColor = PrimaryText,
            focusedContainerColor = PageBackground,
            unfocusedContainerColor = PageBackground,
            focusedBorderColor = PrimaryText.copy(alpha = 0.28f),
            unfocusedBorderColor = DividerColor,
            cursorColor = PrimaryText,
        ),
    )
}

@Composable
private fun StoredSecretField(
    hint: String,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(280.dp)
            .height(38.dp),
        shape = RoundedCornerShape(19.dp),
        color = PageBackground,
        border = BorderStroke(1.dp, DividerColor),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = hint,
                modifier = Modifier.weight(1f),
                color = SecondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit API key",
                tint = SecondaryText,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit)
                    .padding(7.dp),
            )
        }
    }
}

@Composable
private fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isSecret: Boolean = false,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 42.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText, fontSize = 13.sp),
        placeholder = {
            Text(
                text = placeholder,
                color = SecondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = if (trailingLabel != null && onTrailingClick != null) {
            {
                Text(
                    text = trailingLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onTrailingClick)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    color = SecondaryText,
                    fontSize = 11.sp,
                )
            }
        } else {
            null
        },
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        shape = RoundedCornerShape(21.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PrimaryText,
            unfocusedTextColor = PrimaryText,
            focusedContainerColor = PageBackground,
            unfocusedContainerColor = PageBackground,
            focusedBorderColor = PrimaryText.copy(alpha = 0.28f),
            unfocusedBorderColor = DividerColor,
            cursorColor = PrimaryText,
        ),
    )
}

@Composable
private fun NumberStepper(
    value: Int,
    range: IntRange,
    suffix: String = "",
    onValueChange: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = PageBackground,
        border = BorderStroke(1.dp, DividerColor),
    ) {
        Row(
            modifier = Modifier.height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canDecrease = value > range.first
            val canIncrease = value < range.last
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clickable(enabled = canDecrease) { onValueChange((value - 1).coerceIn(range)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Remove,
                    contentDescription = "Decrease",
                    tint = if (canDecrease) PrimaryText else SecondaryText.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = if (suffix.isBlank()) value.toString() else "$value $suffix",
                modifier = Modifier.defaultMinSize(minWidth = 68.dp),
                color = PrimaryText,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clickable(enabled = canIncrease) { onValueChange((value + 1).coerceIn(range)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Increase",
                    tint = if (canIncrease) PrimaryText else SecondaryText.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (positive) {
            if (settingsDark) Color(0xFF234333) else Color(0xFFE4F4E9)
        } else {
            SegmentBackground
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = if (positive) {
                if (settingsDark) Color(0xFF8FD3A8) else Color(0xFF287A45)
            } else {
                SecondaryText
            },
            fontSize = 12.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun OutlinePillButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, DividerColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) PrimaryText else SecondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (enabled) PrimaryText else SecondaryText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingsSaveFooter(
    dirty: Boolean,
    saving: Boolean,
    pendingRestart: Boolean = false,
    disabledMessage: String? = null,
    error: String? = null,
    onSave: () -> Unit,
) {
    CardDivider()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val message = error ?: disabledMessage ?: when {
            pendingRestart && !dirty -> "Saved. Restart when ready."
            dirty -> "Save changes, then restart when ready."
            else -> "Settings are up to date."
        }
        Text(
            text = message,
            color = if (error != null || disabledMessage != null) Color(0xFFB54848) else SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(11.dp))
        Button(
            onClick = onSave,
            enabled = dirty && !saving && disabledMessage == null,
            modifier = Modifier.height(38.dp),
            shape = RoundedCornerShape(19.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryText,
                contentColor = PageBackground,
                disabledContainerColor = DividerColor,
                disabledContentColor = SecondaryText,
            ),
            contentPadding = PaddingValues(horizontal = 17.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = PageBackground,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (saving) "Saving" else "Save", fontSize = 12.sp)
        }
    }
}

private fun List<Pair<String, String>>.withCurrent(value: String): List<Pair<String, String>> =
    if (value.isBlank() || any { it.first == value }) this else listOf(value to value) + this

@Composable
private fun SegmentedSetting(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(50))
            .background(SegmentBackground)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelected(index) },
                shape = RoundedCornerShape(50),
                color = if (selected) {
                    if (settingsDark) Color(0xFF484848) else Color.White
                } else {
                    Color.Transparent
                },
                shadowElevation = if (selected) 1.dp else 0.dp,
            ) {
                Text(
                    text = option,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    color = if (selected) PrimaryText else SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(11.dp),
        color = if (checked) Color(0xFF2997FF) else if (settingsDark) Color(0xFF555555) else Color(0xFFD4D4D4),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            Surface(
                modifier = Modifier
                    .size(18.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 1.dp,
            ) {}
        }
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = DividerColor,
    )
}

@Composable
private fun GroupSpacer(height: androidx.compose.ui.unit.Dp = 27.dp) {
    Spacer(Modifier.height(height))
}

private fun shortPath(path: String?): String {
    if (path.isNullOrBlank()) return "No workspace selected"
    if (path.length <= 30) return path
    return "…${path.takeLast(28)}"
}




