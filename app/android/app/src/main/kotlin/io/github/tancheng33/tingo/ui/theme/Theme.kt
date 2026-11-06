package io.github.tancheng33.tingo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌蓝沿用桌面版与图标的主色（#1F4BFF），保证两端观感一致。
private val TingoBlue = Color(0xFF1F4BFF)
private val TingoBlueDark = Color(0xFF9DB2FF)

private val LightColors = lightColorScheme(
    primary = TingoBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF001257),
    surface = Color(0xFFFAFAFC),
)

private val DarkColors = darkColorScheme(
    primary = TingoBlueDark,
    onPrimary = Color(0xFF0A1F6B),
    primaryContainer = Color(0xFF2A3F87),
    onPrimaryContainer = Color(0xFFDDE3FF),
    surface = Color(0xFF121318),
)

@Composable
fun TingoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
