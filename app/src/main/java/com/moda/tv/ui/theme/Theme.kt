package com.moda.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** TV 用的大字号 + 深色底，避免电视上远处的对比度不足 */
object ModaColors {
    val Bg = Color(0xFF0B0B0F)
    val Surface = Color(0xFF15161C)
    val SurfaceHigh = Color(0xFF1F2129)
    /** 基础蓝：白字压上去 5.67:1 合格；原先 #3D7BFF 只有 3.84:1，白字看不清 */
    val Accent = Color(0xFF2A5FD6)
    /** 聚焦/按下时略亮的蓝：白字 5.02:1 仍合格，保留一点按下反馈 */
    val AccentSoft = Color(0xFF3568DE)
    val Text = Color(0xFFF2F3F7)
    val TextDim = Color(0xFF9AA0AE)
    val Focus = Color(0xFFFFFFFF)
    val Danger = Color(0xFFFF6B6B)
}

private val scheme = darkColorScheme(
    primary = ModaColors.Accent,
    onPrimary = Color.White,
    background = ModaColors.Bg,
    onBackground = ModaColors.Text,
    surface = ModaColors.Surface,
    onSurface = ModaColors.Text,
)

val TvTitle = TextStyle(fontSize = 44.sp, color = ModaColors.Text)
val TvHeading = TextStyle(fontSize = 28.sp, color = ModaColors.Text)
val TvBody = TextStyle(fontSize = 20.sp, color = ModaColors.Text)
val TvCaption = TextStyle(fontSize = 16.sp, color = ModaColors.TextDim)

@Composable
fun ModaTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
