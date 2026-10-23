package com.moda.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

// ============================ 列表边距规则 ============================

/**
 * 可滚动列表（LazyVerticalGrid / LazyColumn）的边距与抗裁剪规则。
 *
 * ## 为什么会有一整组数字
 *
 * tv-material 的 Card/Surface 在聚焦、点击时会做三件改变**绘制**的事，而它们全都
 * **不改变布局尺寸**（见 tv-material `Surface.kt` 的 `tvSurfaceScale` / `tvSurfaceGlow` /
 * `tvSurfaceBorder`）：
 *
 *   | 状态 | 变化 | 视觉外扩 |
 *   |------|------|---------|
 *   | 聚焦 | scale 1.08 | 每边约 4% 宽/高 |
 *   | 点击 | scale 1.0~0.95 + 换色 | 不扩，但换色块本身要完整可见 |
 *   | 发光 | glow 半径（API 28+） | 再外扩数个 dp |
 *
 * 而 `LazyVerticalGrid` 的**视口**会把超出的绘制裁掉，`contentPadding` 又只在列表两端
 * 提供空间。于是真机上表现为：「光标压到最底部时，卡片下沿的白边 / 点击高亮被切平」。
 *
 * ## 四条规则，缺一不可
 *
 * 1. [SidePad] 侧边对齐工具栏，同时兜住横向溢出。
 * 2. [TailPad] **最后一项之后**必须留出的空白（滚到末尾时生效）。
 * 3. [ListBottomSafe] **容器**与屏幕底边的安全边（光标停在中间行时生效）。
 * 4. [listItemInset] **每个列表项自身**的溢出余量（任何一行都生效）。
 *
 * 第 2、3、4 条解决的是三个不同位置的裁剪，任何一条缺失都会在某个滚动位置翻车——
 * 这正是「改一次好一次、换个位置又被吃」的原因。
 *
 * ## 标准用法
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize().listBottomSafe()) {              // 规则 3
 *     LazyVerticalGrid(
 *         contentPadding = ListMetrics.gridContentPadding(),  // 规则 1 + 2
 *     ) {
 *         items(...) { Card(modifier = Modifier.listItemInset()) { ... } }  // 规则 4
 *     }
 * }
 * ```
 */
object ListMetrics {

    /** 规则 1：侧边对齐工具栏；同时是聚焦放大的横向余量 */
    val SidePad: Dp = 48.dp

    /** 规则 2：最后一项之后的留白。**不要传 0**，那等于关掉「最后一条留底」 */
    val TailPad: Dp = 56.dp

    /** 规则 3：列表容器与屏幕底边的安全边 */
    val ListBottomSafe: Dp = 24.dp

    /**
     * 规则 4 的数值：单个列表项为聚焦/发光预留的垂直余量。
     *
     * 取 12dp 的依据：卡片高约 120~200dp，scale 1.08 上下各外扩约 5~8dp，
     * 再加 3dp 描边与 API 28+ 的 glow，单边最坏约 11dp。留 12 才覆盖得住。
     */
    val ItemFocusInset: Dp = 12.dp

    /**
     * 标准网格内边距 = 规则 1 + 规则 2。顶部由调用方按各页头部高度决定。
     */
    fun gridContentPadding(top: Dp = 16.dp, side: Dp = SidePad, tail: Dp = TailPad): PaddingValues =
        PaddingValues(start = side, end = side, top = top, bottom = tail)

}

/**
 * 规则 4：列表项必须套的修饰。**每个列表项都要加**，不要只加第一行或最后一行——
 * 因为滚动位置会变，「最后一行」不是固定的某一项。
 *
 * 垂直 12dp 是抗裁主力（上下都可能是视口边界）；水平 2dp 只用来避免第一列聚焦时
 * 左边框紧贴 SidePad 显得偏窄。
 *
 * 必须是**顶层扩展函数**而不是 ListMetrics 的成员：Kotlin 的成员扩展函数只能在声明它的
 * 类作用域内当扩展用，在 UI 文件里写 `Modifier.fillMaxWidth().listItemInset()` 会
 * Unresolved reference（已经踩过一次）。
 */
fun Modifier.listItemInset(): Modifier =
    // 实验：暂时取消 padding，验证是否为它导致下描边被裁
    this

/**
 * 规则 3：列表容器必须套的安全边。每个「包住滚动列表」的 Box 都该套上：
 *
 *     Box(Modifier.fillMaxSize().listBottomSafe()) { LazyVerticalGrid(...) }
 *
 * 只写 contentPadding 是不够的——那是规则 2，只在滚到末尾生效；
 * 光标停在中间行时裁的是容器边界，必须靠这条。
 */
fun Modifier.listBottomSafe(): Modifier =
    this.padding(bottom = ListMetrics.ListBottomSafe)
