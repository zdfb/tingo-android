package io.github.tancheng33.tingo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 视觉令牌：与桌面版 `app/src/style.css` 的 `:root` 一一对应。
 *
 * 界面里不许出现字面颜色，一律从 [TingoColors] 取——两端同一套值，
 * 改一处两端一起变，也避免"这个蓝色好像和桌面版不太一样"这种返工。
 */
object TingoColors {
    // 浅色：近白底、细描边白卡片（桌面版同样的取值）
    val bg = Color(0xFFF6F8FC)
    val card = Color(0xFFFFFFFF)
    val line = Color(0xFFE3E8F1)
    val lineSoft = Color(0xFFEDF0F5)
    val seg = Color(0xFFEDF1F7)
    val hover = Color(0xFFF0F3F8)
    val text = Color(0xFF101828)
    val text2 = Color(0xFF344054)
    val muted = Color(0xFF667085)
    val faint = Color(0xFF98A2B3)
    val black = Color(0xFF000000)
    /** 黑底上写字用的颜色（深色模式下黑底其实是近白，所以不能写死白） */
    val onBlack = Color(0xFFFFFFFF)

    val blue = Color(0xFF2956E8)
    val blueSoft = Color(0xFFE8EEFF)
    val cyan = Color(0xFF34C6F4)
    val red = Color(0xFFD92D20)
    val green = Color(0xFF12B76A)
    val amber = Color(0xFFF79009)
}

/** 深色：桌面版只给了深色背景与描边，这里补齐前后景对比。 */
object TingoColorsDark {
    val bg = Color(0xFF0D1324)
    val card = Color(0xFF151E34)
    val line = Color(0xFF263451)
    val lineSoft = Color(0xFF1E2A43)
    val seg = Color(0xFF19243B)
    val hover = Color(0xFF1D2A44)
    val text = Color(0xFFF2F4FA)
    val text2 = Color(0xFFD5DCEE)
    val muted = Color(0xFFA5B0C7)
    val faint = Color(0xFF7886A3)
    val black = Color(0xFFEEF1FB)
    val onBlack = Color(0xFF0B0F1E)

    val blue = Color(0xFFA9BCFF)
    val blueSoft = Color(0xFF23356B)
    val cyan = Color(0xFF67D8F8)
    val red = Color(0xFFF97066)
    val green = Color(0xFF47CD89)
    val amber = Color(0xFFFDB022)
}

/** 尺寸与间距：桌面版是 240px 侧栏 + 16px 圆角卡片，手机端按同一节奏缩放。 */
object TingoDimens {
    val radiusCard = 16.dp
    val radiusControl = 14.dp

    /** 品牌区图标边长：与标题一行的视觉重量匹配 */
    val brandMark = 28.dp
    val radiusPill = 999.dp
    val hairline = 1.dp
    /** 最小触控高度：Material 建议 48dp，自绘控件必须自己保证 */
    val touchTarget = 48.dp
    val gutter = 20.dp
    val gap = 12.dp
    val gapTight = 8.dp

    /** 浮层（下拉、对话框）的投影高度：桌面版 --shadow-pop 的对应物 */
    val elevationPop = 12.dp
    /** 卡片贴地时的那一点影子：桌面版 --shadow-pill */
    val elevationPill = 1.dp

    val cardShape = RoundedCornerShape(radiusCard)
    val controlShape = RoundedCornerShape(radiusControl)
    val pillShape = RoundedCornerShape(radiusPill)
}

/** 字阶：正文 14sp（与桌面版 body 一致），标题只加粗不换字体。 */
object TingoType {
    val body = 14.sp
    val bodySmall = 13.sp
    val label = 12.sp
    val caption = 11.sp

    /** 品牌名：桌面版 .brand-name 是 17px/600，字距略放大 */
    val brand = 17.sp
    val display = 30.sp
    val title = 20.sp
    val section = 15.sp
}
