package com.nanobotkt.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val SETTINGS_SECTION_OVERVIEW = "Overview"
const val SETTINGS_SECTION_MODELS = "Models"
internal const val SETTINGS_SECTION_APPEARANCE = "Appearance"

internal val sections =
    listOf(
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

/** Settings 页面入口、分区导航和跨页面共享主题令牌。 */
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
    var section by
        rememberSaveable(initialSection) {
            mutableStateOf(initialSection.takeIf(sections::contains) ?: SETTINGS_SECTION_OVERVIEW)
        }
    val selectSection: (String) -> Unit = { requested ->
        val next = requested.takeIf(sections::contains) ?: SETTINGS_SECTION_OVERVIEW
        section = next
        onSectionChange(next)
    }
    LaunchedEffect(refreshKey) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PageBackground),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item { SettingsHeader(section = section, onBack = onBack, onSectionChange = selectSection) }
        item {
            when (section) {
                SETTINGS_SECTION_OVERVIEW ->
                    OverviewPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onSectionChange = selectSection,
                        onCheckVersion = viewModel::checkVersion,
                    )
                SETTINGS_SECTION_APPEARANCE -> AppearancePage(appearance, viewModel)
                SETTINGS_SECTION_MODELS -> ModelsPage(state, viewModel, appearance.showBrandLogos)
                "Image" ->
                    ImageGenerationPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onOpenProviders = { selectSection(SETTINGS_SECTION_MODELS) },
                        onSave = viewModel::updateImage,
                    )
                "Voice" ->
                    TranscriptionPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onOpenProviders = { selectSection(SETTINGS_SECTION_MODELS) },
                        onSave = viewModel::updateTranscription,
                    )
                "Web" ->
                    WebSearchPage(
                        state = state,
                        showBrandLogos = appearance.showBrandLogos,
                        onSave = viewModel::updateWebSearch,
                    )
                "Channels" ->
                    OpenSectionPage(
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
                colors =
                    ButtonDefaults.buttonColors(
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
internal fun SettingsHeader(
    section: String,
    onBack: () -> Unit,
    onSectionChange: (String) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Row(
        modifier =
            Modifier.clip(RoundedCornerShape(12.dp))
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
internal fun SettingsSectionPicker(section: String, onSectionChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(44.dp).clickable { expanded = true },
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
            modifier = Modifier.fillMaxWidth(0.91f).background(PageBackground),
        ) {
            sections.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item, fontSize = 14.sp) },
                    leadingIcon = { Icon(sectionIcon(item), null, Modifier.size(18.dp)) },
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

internal fun sectionIcon(section: String): ImageVector =
    when (section) {
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
