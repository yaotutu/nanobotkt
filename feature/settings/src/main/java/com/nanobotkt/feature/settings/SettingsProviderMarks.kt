package com.nanobotkt.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nanobotkt.core.designsystem.NanobotThemeDefaults

/** Provider 品牌标记与远程 Logo 渲染。 */
internal enum class ProviderMarkSize {
    PICKER,
    LIST,
}

/**
 * 渲染 Provider 图标，并按“未配置、远程品牌图、通用图标”的顺序降级。
 *
 * 外层容器全部使用 MaterialTheme 的颜色与形状 token；Provider 自身的品牌色仅用于远程图片尚未
 * 加载时的品牌占位，这是领域资产而不是页面色板，不能强行替换成应用主色。
 */
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
                // “未配置”是需要关注但不等同于保存失败的 Warning。统一读取产品状态色，
                // 不再由 Settings 私自借用 tertiary 品牌角色。
                tint = NanobotThemeDefaults.statusColors.warning,
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
        shape =
            if (size == ProviderMarkSize.LIST) {
                MaterialTheme.shapes.medium
            } else {
                MaterialTheme.shapes.small
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(if (size == ProviderMarkSize.LIST) 20.dp else 12.dp),
            )
        }
    }
}

/**
 * 加载 Provider 远程 Logo，并在网络图片就绪前显示稳定的品牌首字母占位。
 *
 * 每次 URL 切换都先清除 loaded，避免上一张图片成功状态泄漏到下一候选 URL；加载失败时只前进到
 * 服务端声明的下一候选地址，候选耗尽后继续保留品牌占位，不制造空白或无限重试。
 */
@Composable
internal fun RemoteProviderBrandMark(brand: ProviderBrand, size: ProviderMarkSize) {
    var logoIndex by remember(brand.logoUrls) { mutableIntStateOf(0) }
    var loaded by remember(brand.logoUrls) { mutableStateOf(false) }
    val logoUrl = brand.logoUrls.getOrNull(logoIndex)
    val containerSize = if (size == ProviderMarkSize.LIST) 40.dp else 20.dp
    val imageSize = if (size == ProviderMarkSize.LIST) 24.dp else 14.dp
    val shape =
        if (size == ProviderMarkSize.LIST) {
            MaterialTheme.shapes.medium
        } else {
            MaterialTheme.shapes.small
        }

    LaunchedEffect(logoUrl) { loaded = false }

    Surface(
        modifier = Modifier.size(containerSize),
        shape = shape,
        color = if (loaded) MaterialTheme.colorScheme.surface else Color(brand.color),
        border =
            if (loaded) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            } else {
                null
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!loaded) {
                Text(
                    text = brand.initials,
                    // 品牌色来自外部资产，首字母使用固定白色以延续现有品牌徽记约定。
                    color = Color.White,
                    style =
                        if (size == ProviderMarkSize.LIST) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelSmall
                        },
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
