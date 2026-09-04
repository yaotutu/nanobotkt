package com.nanobotkt.feature.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定附件 Popup 相对“+”按钮的定位契约。
 *
 * 仪器测试中的主界面和 Popup 分属不同窗口，二者的 boundsInRoot 不能直接比较；因此把不依赖
 * Android Window 的坐标计算放到 JVM 测试中验证，避免用错误坐标系制造视觉回归或假失败。
 */
class AttachmentMenuPositionProviderTest {
    private val provider = AttachmentMenuPositionProvider(edgeMarginPx = 8, anchorGapPx = 4)

    @Test
    fun `上方空间足够时菜单紧贴锚点上方`() {
        val anchor = IntRect(left = 24, top = 300, right = 72, bottom = 348)
        val popup = IntSize(width = 160, height = 112)

        val position = calculate(anchor = anchor, popup = popup)

        // 底部 Composer 的菜单优先向上展开，并严格保留约定的 4px 锚点间距。
        assertEquals(anchor.top - 4, position.y + popup.height)
        assertEquals(anchor.left, position.x)
    }

    @Test
    fun `上方空间不足且下方可用时菜单改放锚点下方`() {
        val anchor = IntRect(left = 24, top = 32, right = 72, bottom = 80)
        val popup = IntSize(width = 160, height = 112)

        val position = calculate(anchor = anchor, popup = popup)

        assertEquals(anchor.bottom + 4, position.y)
    }

    @Test
    fun `LTR 靠近右侧时水平位置不会越过窗口安全边距`() {
        val popup = IntSize(width = 160, height = 112)
        val position =
            calculate(
                anchor = IntRect(left = 340, top = 300, right = 388, bottom = 348),
                popup = popup,
            )

        assertEquals(windowSize.width - 8 - popup.width, position.x)
    }

    @Test
    fun `RTL 优先让菜单右边缘与锚点右边缘对齐`() {
        val anchor = IntRect(left = 220, top = 300, right = 268, bottom = 348)
        val popup = IntSize(width = 160, height = 112)

        val position =
            calculate(
                anchor = anchor,
                popup = popup,
                layoutDirection = LayoutDirection.Rtl,
            )

        assertEquals(anchor.right - popup.width, position.x)
    }

    @Test
    fun `菜单尺寸超过安全区域时起点仍被限制在窗口内`() {
        val popup = IntSize(width = 500, height = 900)
        val position =
            calculate(
                anchor = IntRect(left = 180, top = 360, right = 228, bottom = 408),
                popup = popup,
            )

        // PopupProperties 默认启用窗口裁切；定位器只需保证超大内容的左上角不落到屏幕外，
        // 其余超出部分交由窗口系统裁切，避免 coerceIn 收到反向范围或产生负坐标。
        assertEquals(IntOffset(8, 8), position)
        assertTrue(position.x >= 0)
        assertTrue(position.y >= 0)
    }

    /** 使用稳定窗口尺寸调用生产定位器，减少每个测试与坐标策略无关的样板参数。 */
    private fun calculate(
        anchor: IntRect,
        popup: IntSize,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): IntOffset =
        provider.calculatePosition(
            anchorBounds = anchor,
            windowSize = windowSize,
            layoutDirection = layoutDirection,
            popupContentSize = popup,
        )

    private companion object {
        val windowSize = IntSize(width = 400, height = 800)
    }
}
