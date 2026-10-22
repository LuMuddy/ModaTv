package com.moda.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import com.moda.tv.ui.theme.TvTitle
import kotlin.math.absoluteValue

/** 封面兜底配色：同一标题恒定取到同一色，避免重组时闪烁 */
private val CoverPalette = listOf(
    Color(0xFF2B3A67), Color(0xFF3D2B4F), Color(0xFF1F4B4B), Color(0xFF4A2F2A),
    Color(0xFF2E4552), Color(0xFF3F3B2E), Color(0xFF232C4A), Color(0xFF4A3446),
)

fun coverColorOf(title: String): Color =
    CoverPalette[title.hashCode().absoluteValue % CoverPalette.size]

/** 封面占位块：无图/加载中/加载失败时都靠它撑住版面，绝不出现白屏 */
@Composable
fun CoverBlock(
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val base = coverColorOf(title)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.verticalGradient(listOf(base, base.copy(alpha = 0.55f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).ifBlank { "剧" },
            style = TvTitle.copy(fontSize = 34.sp),
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

/** 角标（VIP / 预告 / 接口返回的 badge） */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ModaColors.Accent,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = TvCaption.copy(fontSize = 13.sp), color = Color.White)
    }
}

@Composable
fun LoadingState(
    text: String = "加载中…",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = ModaColors.Accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, style = TvBody, color = ModaColors.TextDim)
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("加载失败", style = TvHeading, color = ModaColors.Danger)
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            style = TvBody,
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(24.dp))
            ActionButton(text = "重试", onClick = onRetry, primary = true)
        }
    }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = TvBody,
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(24.dp))
            ActionButton(text = "重新加载", onClick = onRetry, primary = true)
        }
    }
}

/** 小标题 + 内容的分隔文字，行标题与详情里的区块标题共用 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TvHeading, color = ModaColors.Text, modifier = modifier)
}
