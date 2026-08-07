package com.nanobotkt.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.FileEditDisplay
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.persistence.UserPreferences

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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    var section by rememberSaveable(initialSection) {
        mutableStateOf(initialSection.takeIf(sections::contains) ?: SETTINGS_SECTION_OVERVIEW)
    }
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? ComponentActivity)?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

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
                onSectionChange = { section = it },
            )
        }
        item {
            when (section) {
                SETTINGS_SECTION_OVERVIEW -> OverviewPage(
                    state = state,
                    onSectionChange = { section = it },
                    onCheckVersion = viewModel::checkVersion,
                )
                SETTINGS_SECTION_APPEARANCE -> AppearancePage(appearance, viewModel)
                SETTINGS_SECTION_MODELS -> ModelsPage(state, viewModel)
                "Image" -> DataSectionPage("Image generation", state.payload?.imageGeneration?.toString())
                "Voice" -> DataSectionPage("Voice transcription", state.payload?.transcription?.toString())
                "Web" -> DataSectionPage("Web search & fetch", state.payload?.web?.toString())
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
        fontSize = 18.sp,
        lineHeight = 23.sp,
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
    onSectionChange: (String) -> Unit,
    onCheckVersion: () -> Unit,
) {
    val payload = state.payload
    val activePreset = payload?.modelPresets?.firstOrNull { it.active }
    val modelName = activePreset?.model?.takeIf { it.isNotBlank() }
        ?: payload?.agent?.model?.takeIf { it.isNotBlank() }
        ?: "Unavailable"
    val providerName = activePreset?.provider?.takeIf { it.isNotBlank() }
        ?: payload?.agent?.provider?.takeIf { it.isNotBlank() }
        ?: "Not connected"

    SettingsGroup("AI") {
        SettingsRow(
            icon = Icons.Outlined.SmartToy,
            title = "Current model",
            subtitle = "$providerName · ${if (activePreset?.isDefault == true) "Primary" else "Active"}",
            value = modelName,
            onClick = { onSectionChange(SETTINGS_SECTION_MODELS) },
        )
    }

    GroupSpacer()
    SettingsGroup("Capabilities") {
        SettingsRow(
            icon = Icons.Outlined.Public,
            title = "Web search",
            subtitle = if (payload?.webSearch != null || payload?.web != null) "Configured" else "Not configured",
            value = if (payload?.webSearch != null || payload?.web != null) "Enabled" else "Unavailable",
            onClick = { onSectionChange("Web") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = "Image generation",
            subtitle = if (payload?.imageGeneration != null) "Configured" else "Not configured",
            value = if (payload?.imageGeneration != null) "Enabled" else "Unavailable",
            onClick = { onSectionChange("Image") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.MicNone,
            title = "Voice input",
            subtitle = if (payload?.transcription != null) "Configured" else "Not configured",
            value = if (payload?.transcription != null) "Enabled" else "Unavailable",
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
            subtitle = if (payload != null) "Ready" else "Not connected",
            value = if (host.isNotBlank() && port > 0) "$host:$port" else "Unavailable",
            onClick = { onSectionChange("System") },
        )
        CardDivider()
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = "Workspace",
            subtitle = shortPath(payload?.runtime?.workspacePath),
            value = if (payload?.runtime?.workspacePath.isNullOrBlank()) "Unavailable" else "Default workspace",
            onClick = { onSectionChange("System") },
        )
    }

    GroupSpacer()
    SettingsGroup("About") {
        val checkedVersion = state.versionCheck?.updateAvailable
        val version = checkedVersion?.currentVersion
            ?: payload?.version?.values?.firstOrNull()
            ?: "Unknown"
        val updateText = checkedVersion?.latestVersion?.let { "Update: $it" }
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = "Version",
            subtitle = updateText ?: if (state.versionCheck != null) "Up to date" else "Check for updates",
            value = version,
            onClick = onCheckVersion,
        )
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
            title = "Wrap code",
            description = "Wrap long code lines instead of scrolling horizontally.",
        ) {
            SegmentedSetting(
                options = listOf("Off", "On"),
                selectedIndex = if (preferences.wrapCode) 1 else 0,
                onSelected = { viewModel.wrap(it == 1) },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = "Brand logos",
            description = "Show provider logos where they are available.",
        ) {
            SegmentedSetting(
                options = listOf("Hide", "Show"),
                selectedIndex = if (preferences.showBrandLogos) 1 else 0,
                onSelected = { viewModel.logos(it == 1) },
            )
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
private fun ModelsPage(state: SettingsUiState, viewModel: SettingsViewModel) {
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
    val advanced = state.payload?.advanced

    SettingsGroup("Web safety") {
        PreferenceBlock(
            title = "Local service access",
            description = "Allow the WebUI to connect to services on this device.",
        ) {
            SegmentedSetting(
                options = listOf("Block", "Allow"),
                selectedIndex = if (advanced?.webuiAllowLocalServiceAccess == true) 1 else 0,
                onSelected = {
                    viewModel.network(it == 1, advanced?.webuiDefaultAccessMode ?: "default")
                },
            )
        }
        CardDivider()
        PreferenceBlock(
            title = "Default access mode",
            description = "Choose the default access level used by WebUI requests.",
        ) {
            SegmentedSetting(
                options = listOf("Default", "Full"),
                selectedIndex = if (advanced?.webuiDefaultAccessMode == "full") 1 else 0,
                onSelected = {
                    viewModel.network(advanced?.webuiAllowLocalServiceAccess == true, if (it == 1) "full" else "default")
                },
            )
        }
    }
}

@Composable
private fun DataSectionPage(title: String, value: String?) {
    SettingsGroup(title) {
        PreferenceBlock(
            title = if (value == null) "Unavailable" else "Configuration",
            description = value ?: "This gateway did not expose this settings section.",
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
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        color = PrimaryText,
        fontSize = 14.sp,
        lineHeight = 18.sp,
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
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 69.dp)
            .then(clickModifier)
            .padding(start = 17.dp, end = 15.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(35.dp),
            shape = CircleShape,
            color = if (settingsDark) {
                if (selected) Color(0xFF3B3B3B) else Color(0xFF2E2E2E)
            } else {
                if (selected) Color.White else Color(0xFFF8F8F7)
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = PrimaryText, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = PrimaryText,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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




