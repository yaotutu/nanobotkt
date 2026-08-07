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
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ArrowCircleUp
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val languages = listOf(
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
    val currentName = languages.firstOrNull { it.first == languageTag }?.second ?: "English"

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
                        if (tag == languageTag || (languageTag == null && tag == "en")) {
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
                    onClick = { viewModel.update(SettingsUpdate(modelPreset = preset.name)) },
                )
                if (index != payload.modelPresets.lastIndex) CardDivider()
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
                    subtitle = if (provider.configured) "Configured" else "Not configured",
                    value = if (provider.configured) "Connected" else null,
                    onClick = { viewModel.providerModels(provider.name) },
                )
                if (index != payload.providers.lastIndex) CardDivider()
            }
        }
    }

    state.providerModels?.let { catalog ->
        GroupSpacer()
        SettingsGroup(catalog.label) {
            if (catalog.models.isEmpty()) {
                EmptySettingsRow(Icons.Outlined.Search, "No models found", "This provider returned no models.")
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

    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
        ?: settings.providers.firstOrNull()
    val providerConfigured = selectedProvider?.configured ?: settings.providerConfigured
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
    val settings = state.payload?.transcription ?: TranscriptionSettings()
    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var language by rememberSaveable(settings.language) { mutableStateOf(settings.language.orEmpty()) }
    var maxDuration by rememberSaveable(settings.maxDurationSec) { mutableStateOf(settings.maxDurationSec) }
    var maxUpload by rememberSaveable(settings.maxUploadMb) { mutableStateOf(settings.maxUploadMb) }

    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
        ?: settings.providers.firstOrNull()
    val providerConfigured = selectedProvider?.configured ?: settings.providerConfigured
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




