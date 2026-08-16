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

/** 保存状态、分段选择、开关与通用间距组件。 */
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

/**
 * 缩短工作区路径时由调用方提供空值文案，避免这个纯格式化函数把英文写死到所有 Locale。
 * 默认参数保留既有单元测试和非 Compose 调用的兼容性，设置界面会传入当前语言的资源值。
 */
internal fun shortPath(
    path: String?,
    emptyLabel: String = "No workspace selected",
): String {
    if (path.isNullOrBlank()) return emptyLabel
    if (path.length <= 30) return path
    return "…${path.takeLast(28)}"
}
