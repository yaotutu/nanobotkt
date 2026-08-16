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
import androidx.compose.runtime.mutableIntStateOf
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

/** Provider 品牌标记与远程 Logo 渲染。 */
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
    var logoIndex by remember(brand.logoUrls) { mutableIntStateOf(0) }
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
