package io.github.tancheng33.tingo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 一屏需要的全部颜色。
 *
 * 用 CompositionLocal 而不是只靠 MaterialTheme：Material 的 colorScheme 名字（secondaryContainer
 * 之类）和桌面版的语义（card / line / muted / seg）对不上，硬套会把设计意图翻译丢。
 */
data class TingoPalette(
    val bg: androidx.compose.ui.graphics.Color,
    val card: androidx.compose.ui.graphics.Color,
    val line: androidx.compose.ui.graphics.Color,
    val lineSoft: androidx.compose.ui.graphics.Color,
    val seg: androidx.compose.ui.graphics.Color,
    val hover: androidx.compose.ui.graphics.Color,
    val text: androidx.compose.ui.graphics.Color,
    val text2: androidx.compose.ui.graphics.Color,
    val muted: androidx.compose.ui.graphics.Color,
    val faint: androidx.compose.ui.graphics.Color,
    val black: androidx.compose.ui.graphics.Color,
    val blue: androidx.compose.ui.graphics.Color,
    val blueSoft: androidx.compose.ui.graphics.Color,
    val cyan: androidx.compose.ui.graphics.Color,
    val red: androidx.compose.ui.graphics.Color,
    val green: androidx.compose.ui.graphics.Color,
    val amber: androidx.compose.ui.graphics.Color,
)

private val LightPalette = TingoPalette(
    bg = TingoColors.bg, card = TingoColors.card, line = TingoColors.line,
    lineSoft = TingoColors.lineSoft, seg = TingoColors.seg, hover = TingoColors.hover,
    text = TingoColors.text, text2 = TingoColors.text2, muted = TingoColors.muted,
    faint = TingoColors.faint, black = TingoColors.black, blue = TingoColors.blue,
    blueSoft = TingoColors.blueSoft, cyan = TingoColors.cyan, red = TingoColors.red,
    green = TingoColors.green, amber = TingoColors.amber,
)

private val DarkPalette = TingoPalette(
    bg = TingoColorsDark.bg, card = TingoColorsDark.card, line = TingoColorsDark.line,
    lineSoft = TingoColorsDark.lineSoft, seg = TingoColorsDark.seg, hover = TingoColorsDark.hover,
    text = TingoColorsDark.text, text2 = TingoColorsDark.text2, muted = TingoColorsDark.muted,
    faint = TingoColorsDark.faint, black = TingoColorsDark.black, blue = TingoColorsDark.blue,
    blueSoft = TingoColorsDark.blueSoft, cyan = TingoColorsDark.cyan, red = TingoColorsDark.red,
    green = TingoColorsDark.green, amber = TingoColorsDark.amber,
)

val LocalTingoPalette = staticCompositionLocalOf { LightPalette }

/** 界面里写 `TingoTheme.colors` 就拿到当前配色。 */
object TingoTheme {
    val colors: TingoPalette
        @Composable get() = LocalTingoPalette.current
}

/** 桌面版用系统字体（PingFang / SF），Android 上就是系统默认字体；只调字重与字距。 */
private val TingoTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.section, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.body, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.body, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.bodySmall, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.body, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.label),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = TingoType.caption),
)

private val LightScheme = lightColorScheme(
    primary = TingoColors.blue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    background = TingoColors.bg,
    onBackground = TingoColors.text,
    surface = TingoColors.card,
    onSurface = TingoColors.text,
    onSurfaceVariant = TingoColors.muted,
    outline = TingoColors.line,
    outlineVariant = TingoColors.lineSoft,
    error = TingoColors.red,
)

private val DarkScheme = darkColorScheme(
    primary = TingoColorsDark.blue,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0A1F6B),
    background = TingoColorsDark.bg,
    onBackground = TingoColorsDark.text,
    surface = TingoColorsDark.card,
    onSurface = TingoColorsDark.text,
    onSurfaceVariant = TingoColorsDark.muted,
    outline = TingoColorsDark.line,
    outlineVariant = TingoColorsDark.lineSoft,
    error = TingoColorsDark.red,
)

@Composable
fun TingoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTingoPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = TingoTypography,
            content = content,
        )
    }
}
