package com.nanobotkt.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 的五级圆角契约。
 *
 * 组件不再各自决定“看起来差不多”的圆角；后续页面只应从 MaterialTheme.shapes 取值。
 * full/pill 形状仍由具体组件按语义使用 CircleShape，不把它混入五级系统形状。
 */
val NanobotShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun material3TextStyle(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

/**
 * 完整 Material 3 type scale。
 *
 * 显式填满 15 个层级，避免 Compose 默认 Typography 与局部 copy() 混用后出现标题、正文、标签
 * 的字重/行高漂移。FontFamily.SansSerif 在 Android 上映射到系统无衬线字体（默认通常为 Roboto）。
 */
val NanobotTypography = Typography(
    displayLarge = material3TextStyle(57.sp, 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = material3TextStyle(45.sp, 52.sp),
    displaySmall = material3TextStyle(36.sp, 44.sp),
    headlineLarge = material3TextStyle(32.sp, 40.sp),
    headlineMedium = material3TextStyle(28.sp, 36.sp),
    headlineSmall = material3TextStyle(24.sp, 32.sp),
    titleLarge = material3TextStyle(22.sp, 28.sp),
    titleMedium = material3TextStyle(16.sp, 24.sp, FontWeight.Medium, 0.15.sp),
    titleSmall = material3TextStyle(14.sp, 20.sp, FontWeight.Medium, 0.1.sp),
    bodyLarge = material3TextStyle(16.sp, 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = material3TextStyle(14.sp, 20.sp, letterSpacing = 0.25.sp),
    bodySmall = material3TextStyle(12.sp, 16.sp, letterSpacing = 0.4.sp),
    labelLarge = material3TextStyle(14.sp, 20.sp, FontWeight.Medium, 0.1.sp),
    labelMedium = material3TextStyle(12.sp, 16.sp, FontWeight.Medium, 0.5.sp),
    labelSmall = material3TextStyle(11.sp, 16.sp, FontWeight.Medium, 0.5.sp),
)
