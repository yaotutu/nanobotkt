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

internal enum class ProviderMarkSize {
    PICKER,
    LIST,
}

@Composable
internal fun ProviderMark(
    provider: String?,
    showBrandLogos: Boolean,
    size: ProviderMarkSize,
    fallbackIcon: ImageVector,
    unconfigured: Boolean = false,
    hideWhenUnavailable: Boolean = false,
) {
    val containerSize = if (size == ProviderMarkSize.LIST) 40.dp else 20.dp
    if (unconfigured) {
        Box(modifier = Modifier.size(containerSize), contentAlignment = Alignment.Center) {
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
internal fun RemoteProviderBrandMark(brand: ProviderBrand, size: ProviderMarkSize) {
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
                    modifier = Modifier.size(imageSize).alpha(if (loaded) 1f else 0f),
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
internal fun VersionCheckRow(
    version: String,
    updateText: String?,
    checked: Boolean,
    checking: Boolean,
    onCheckVersion: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
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
internal fun ModelIdPicker(
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
    val visibleModels =
        models
            .distinct()
            .filter { trimmedQuery.isBlank() || it.contains(trimmedQuery, ignoreCase = true) }
            .take(80)
    val showCustom =
        trimmedQuery.isNotBlank() && models.none { it == trimmedQuery } && trimmedQuery != value

    Box(Modifier.width(224.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(38.dp).clickable { expanded = true },
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
            modifier = Modifier.width(280.dp).heightIn(max = 330.dp).background(PageBackground),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.width(268.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                placeholder = {
                    Text("Search or type model ID", color = SecondaryText, fontSize = 12.sp)
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        null,
                        tint = SecondaryText,
                        modifier = Modifier.size(16.dp),
                    )
                },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(color = PrimaryText, fontSize = 12.sp),
                shape = RoundedCornerShape(18.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
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
                        Text(
                            modelId,
                            color = PrimaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        if (modelId == value) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                tint = PrimaryText,
                                modifier = Modifier.size(15.dp),
                            )
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
                        Icon(
                            Icons.Outlined.Edit,
                            null,
                            tint = SecondaryText,
                            modifier = Modifier.size(15.dp),
                        )
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
internal fun SecretPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.width(280.dp).defaultMinSize(minHeight = 42.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PrimaryText, fontSize = 13.sp),
        placeholder = {
            Text(
                placeholder,
                color = SecondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            Icon(
                imageVector =
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (visible) "Hide API key" else "Show API key",
                tint = SecondaryText,
                modifier =
                    Modifier.size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleVisibility)
                        .padding(6.dp),
            )
        },
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(21.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
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
internal fun StoredSecretField(hint: String, onEdit: () -> Unit) {
    Surface(
        modifier = Modifier.width(280.dp).height(38.dp),
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
                modifier =
                    Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onEdit).padding(7.dp),
            )
        }
    }
}

@Composable
internal fun PillTextField(
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
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp),
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
        trailingIcon =
            if (trailingLabel != null && onTrailingClick != null) {
                {
                    Text(
                        text = trailingLabel,
                        modifier =
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onTrailingClick)
                                .padding(horizontal = 7.dp, vertical = 5.dp),
                        color = SecondaryText,
                        fontSize = 11.sp,
                    )
                }
            } else {
                null
            },
        visualTransformation =
            if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        shape = RoundedCornerShape(21.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
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
internal fun NumberStepper(
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
        Row(modifier = Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            val canDecrease = value > range.first
            val canIncrease = value < range.last
            Box(
                modifier =
                    Modifier.size(38.dp).clickable(enabled = canDecrease) {
                        onValueChange((value - 1).coerceIn(range))
                    },
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
                modifier =
                    Modifier.size(38.dp).clickable(enabled = canIncrease) {
                        onValueChange((value + 1).coerceIn(range))
                    },
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
internal fun StatusPill(text: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color =
            if (positive) {
                if (settingsDark) Color(0xFF234333) else Color(0xFFE4F4E9)
            } else {
                SegmentBackground
            },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color =
                if (positive) {
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
internal fun OutlinePillButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Surface(
        modifier =
            Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick),
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
            Text(text = text, color = if (enabled) PrimaryText else SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun SettingsSaveFooter(
    dirty: Boolean,
    saving: Boolean,
    pendingRestart: Boolean = false,
    disabledMessage: String? = null,
    error: String? = null,
    onSave: () -> Unit,
) {
    CardDivider()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        val message =
            error
                ?: disabledMessage
                ?: when {
                    pendingRestart && !dirty -> "Saved. Restart when ready."
                    dirty -> "Save changes, then restart when ready."
                    else -> "Settings are up to date."
                }
        Text(
            text = message,
            color =
                if (error != null || disabledMessage != null) Color(0xFFB54848) else SecondaryText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(11.dp))
        Button(
            onClick = onSave,
            enabled = dirty && !saving && disabledMessage == null,
            modifier = Modifier.height(38.dp),
            shape = RoundedCornerShape(19.dp),
            colors =
                ButtonDefaults.buttonColors(
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

internal fun List<Pair<String, String>>.withCurrent(value: String): List<Pair<String, String>> =
    if (value.isBlank() || any { it.first == value }) this else listOf(value to value) + this

@Composable
internal fun SegmentedSetting(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier =
            Modifier.width(IntrinsicSize.Max)
                .clip(RoundedCornerShape(50))
                .background(SegmentBackground)
                .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onSelected(index) },
                shape = RoundedCornerShape(50),
                color =
                    if (selected) {
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
internal fun ToggleSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        modifier =
            Modifier.width(38.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).clickable {
                onCheckedChange(!checked)
            },
        shape = RoundedCornerShape(11.dp),
        color =
            if (checked) Color(0xFF2997FF)
            else if (settingsDark) Color(0xFF555555) else Color(0xFFD4D4D4),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            Surface(
                modifier =
                    Modifier.size(18.dp)
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 1.dp,
            ) {}
        }
    }
}

@Composable
internal fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = DividerColor,
    )
}

@Composable
internal fun GroupSpacer(height: androidx.compose.ui.unit.Dp = 27.dp) {
    Spacer(Modifier.height(height))
}

internal fun shortPath(path: String?): String {
    if (path.isNullOrBlank()) return "No workspace selected"
    if (path.length <= 30) return path
    return "…${path.takeLast(28)}"
}
