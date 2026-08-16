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

/** 版本检查、模型选择、密钥和数值输入组件。 */
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
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Surface(
        modifier =
            modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick),
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
