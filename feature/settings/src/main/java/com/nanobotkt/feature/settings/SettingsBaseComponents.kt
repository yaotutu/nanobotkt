package com.nanobotkt.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Settings 页面分组、行、说明块与基础表单布局。 */
/** Settings 页面共享表单组件，避免各能力页重复视觉和输入规则。 */
@Composable
internal fun OpenSectionPage(
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
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun SettingsRow(
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
        modifier =
            Modifier.fillMaxWidth()
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
                    Icon(
                        icon,
                        null,
                        tint = PrimaryText.copy(alpha = 0.82f),
                        modifier = Modifier.size(16.dp),
                    )
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
        if (
            !valueLogoProvider.isNullOrBlank() &&
                showBrandLogos &&
                providerBrand(valueLogoProvider) != null
        ) {
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
internal fun EmptySettingsRow(
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
internal fun PreferenceBlock(
    title: String,
    description: String,
    content: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
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
internal fun FormSettingRow(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = title,
            color = PrimaryText,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(text = description, color = SecondaryText, fontSize = 12.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(11.dp))
        content()
    }
}

@Composable
internal fun ReadOnlyFormRow(title: String, value: String) {
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
internal fun PillPicker(
    value: String,
    options: List<Pair<String, String>>,
    showProviderLogos: Boolean = false,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel =
        options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "Select" }
    Box(Modifier.width(210.dp)) {
        Surface(
            modifier =
                Modifier.width(210.dp).height(38.dp).clickable(enabled = options.isNotEmpty()) {
                    expanded = true
                },
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
            modifier = Modifier.width(240.dp).heightIn(max = 288.dp).background(PageBackground),
        ) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    leadingIcon =
                        if (showProviderLogos) {
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
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                tint = PrimaryText,
                                modifier = Modifier.size(16.dp),
                            )
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
