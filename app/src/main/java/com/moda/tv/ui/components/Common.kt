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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
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
    /** 首焦点落点：整屏只有一个按钮时，焦点必须已经在它上面，否则用户按确认没反应 */
    retryFocus: FocusRequester? = null,
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
            ActionButton(
                text = "重试",
                onClick = onRetry,
                primary = true,
                modifier = if (retryFocus != null) Modifier.focusRequester(retryFocus) else Modifier,
            )
        }
    }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    /** 同 [ErrorState.retryFocus]：空态里唯一的可点项就是「重新加载」 */
    retryFocus: FocusRequester? = null,
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
            ActionButton(
                text = "重新加载",
                onClick = onRetry,
                primary = true,
                modifier = if (retryFocus != null) Modifier.focusRequester(retryFocus) else Modifier,
            )
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
 * 而 `LazyVerticalGrid` 的**视口**会把超出的绘制裁掉。于是真机上表现为：
 * 「光标压到最底部时，卡片下沿的白边 / 点击高亮被切平」。
 *
 * ## 规则（纵向与横向的余量**位置**不同，这是 2026-10 实测纠正过的）
 *
 * 1. [SidePad] 侧边对齐工具栏；同时也是横向溢出的余量。
 * 2. [TailPad] **最后一项之后**的留白，属于 contentPadding——列表末端才有内容可滚。
 * 3. **顶部**余量必须写进 contentPadding（视口之内）。焦点滚动会把被聚焦项顶到视口边，
 *    写在 modifier 上（视口之外）的留白它一点都用不上——这是 2026-10 真机复现修正的结论，
 *    原先"纵向余量一律写视口之外"的说法是错的。
 *
 * 4. **真正让焦点框不被切的办法是不放大**：tv-material 的 scale 只改绘制、不改布局，
 *    超出视口的绘制必然被 Lazy 视口裁掉，留白再多也只是把裁剪线挪了个位置
 *    （实测：网格首行卡片聚焦后上沿、tab 栏第一个 tab 的左沿都会缺一条边）。
 *    所以卡片/档位条一律 focusedScale = 1f，焦点只靠描边与底色表达，见 Cards.kt。
 *
 * 横向：网格固定列数、不横向滚动，那份 padding 不会被吃掉，所以留在视口内的 contentPadding；
 * 而**会横向滚动的 LazyRow（tab 栏、选集轨道、缩略图行、热词行）必须改用 [railScrollRoom]**。
 *
 * ## 标准用法
 *
 * ```kotlin
 * LazyVerticalGrid(
 *     modifier = Modifier.fillMaxSize().gridScrollRoom(),     // 规则 3（视口之外）
 *     contentPadding = ListMetrics.gridContentPadding(),      // 规则 1 + 2（只有横向 + 尾部）
 * ) { items(...) { Card(Modifier.fillMaxWidth()) { ... } } }
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
     * 网格顶边与它上方内容（tab 栏 / 标题）之间的余量。
     * 与 [ListBottomSafe] 一样必须落在视口之外，见 [gridScrollRoom]。
     */
    val GridTopRoom: Dp = 8.dp

    /**
     * 标准网格内边距 = 规则 1 + 规则 2。
     *
     * top 默认给 0：**竖向**的余量一律留给 [gridScrollRoom]（写在视口之外）。
     * 只要顶部这一段是 contentPadding，焦点滚动就会先把它吃掉，
     * 「上滚回不到最上面」正是这么来的（见 [gridScrollRoom] 的实测记录）。
     */
    fun gridContentPadding(top: Dp = 0.dp, side: Dp = SidePad, tail: Dp = TailPad): PaddingValues =
        PaddingValues(start = side, end = side, top = top, bottom = tail)

}

/**
 * 滚动方向上的边缘余量，必须套在**列表自己的 modifier** 上（视口之外）。
 *
 * 为什么不能写成 contentPadding：contentPadding 属于视口内部，焦点滚动会先把它吃掉。
 * 实测（模拟器、1080p）——网格顶部 contentPadding = 16dp 时，下滑再上滑，列表稳定停在
 * offset = 16dp 而不是 0：屏幕最上面永远切着上一行的半截标题，这就是"上滑回不到最上面"。
 * 把同样的 16dp 写进 contentPadding 之外的 padding，视口本身变小，offset 0 就重新可达，
 * 回到顶部与刚进页面时像素一致。
 *
 * 底部同理：最后一行被焦点滚动顶到视口下沿时，卡片下沿的圆角、3dp 描边与聚焦放大
 * （scale 1.06，每边约 5~7dp）都会被视口裁平——"下滑时最下面的数据被遮一点"。
 * 视口下沿让出 [ListMetrics.ListBottomSafe] 之后，贴边对齐的是视口而不是屏幕。
 */
fun Modifier.gridScrollRoom(
    top: Dp = ListMetrics.GridTopRoom,
    bottom: Dp = ListMetrics.ListBottomSafe,
): Modifier = this.padding(top = top, bottom = bottom)

/**
 * 横向轨道（LazyRow）的边缘余量，必须套在**轨道自己的 modifier** 上（视口之外）。
 *
 * 与 [gridScrollRoom] 是同一条实测结论：余量一旦写在 contentPadding 里，焦点滚动会先吃掉它——
 * 按「右」推到末尾再按「左」回第一个 chip，第一个 chip 会与页头左缘差一截，
 * 它左外侧的聚焦放大也被切平。差别只在横向不会切断文字：内容都落在项自己的布局盒里，
 * 贴住视口边的只有放大/发光的外沿。
 *
 * 纵向余量（若有）仍留在 contentPadding：那条轴不滚动，不会被吃掉。
 */
fun Modifier.railScrollRoom(horizontal: Dp): Modifier = this.padding(horizontal = horizontal)

/**
 * TV 的网格焦点滚动规则：**按行跟焦**，且整行可见。
 *
 * 为什么不能用系统默认的焦点滚动（BringIntoViewSpec / "刚好露出一点"）：
 * 那是给触摸滚动和鼠标滚轮设计的。TV 只有按键：
 *
 * 1. 焦点一次移动一行。列表必须跟着"整行"走，任何一行都不该被切成半截——
 *    半截行会让人以为数据没了（"下下下之后那块区域没显示"就是这么来的）。
 * 2. 连按方向键时，焦点事件比滚动动画快。系统那套是"滚动到刚好可见"，
 *    连按几下后它算的距离会被后一次覆盖，最后一个焦点行经常还停在视口外。
 *    所以这里不依赖系统那套：每次焦点落到某项，就直接量它的实际位置，
 *    缺多少补多少（一次滚到位，不做动画，连按也不掉队）。
 * 3. 上下都留 [comfort] 的呼吸空间，避免被聚焦行贴住 tab 栏或屏幕下沿。
 *
 * @param focusedIndex 当前被聚焦项的下标；-1 表示没有（例如焦点在 tab 栏上）
 * @param columns 网格列数，用来判断"这一行"的整体上下沿
 */
@Composable
fun TvGridFocusScroll(
    state: LazyGridState,
    focusedIndex: Int,
    columns: Int,
    comfort: Dp = 16.dp,
) {
    val comfortPx = with(LocalDensity.current) { comfort.toPx() }
    LaunchedEffect(state, focusedIndex, columns) {
        if (focusedIndex < 0) return@LaunchedEffect
        val rowStart = focusedIndex - focusedIndex % columns
        val rowEnd = rowStart + columns - 1
        val info = state.layoutInfo
        // 这一行在视口里的实际上下沿（同行各项同高，取并集最稳）
        val rowItems = info.visibleItemsInfo.filter { it.index in rowStart..rowEnd }
        if (rowItems.isEmpty()) {
            // 整行还没布局出来（连按跳了很远）：先按行跳过去，下一次焦点事件再微调
            state.scrollToItem(rowStart)
            return@LaunchedEffect
        }
        val top = rowItems.minOf { it.offset.y.toFloat() }
        // LazyGridItemInfo.size 是 IntSize，纵向高度取 height
        val bottom = rowItems.maxOf { (it.offset.y + it.size.height).toFloat() }
        val viewport = info.viewportSize.height.toFloat()
        val delta = when {
            top < comfortPx -> top - comfortPx
            bottom > viewport - comfortPx -> bottom - (viewport - comfortPx)
            else -> 0f
        }
        // scrollBy 是"立即"滚，不是动画：连按时每一次按键都当场把行摆好，
        // 不会出现"按了三下、画面还在追第一下"的错位
        if (delta != 0f) state.scrollBy(delta)
    }
}

/**
 * 规则 R1 的实现：**进页面就把焦点摆到该在的地方**。
 *
 * 电视上没有鼠标，焦点空着 = 遥控器按确认没反应。而首帧布局未完成时 requestFocus 会抛，
 * 所以统一等两帧再抢；失败不抛（目标可能还没上树，下一次组合/数据到达会再来一遍）。
 *
 * 用法：每个页面的"首焦点落点"建一个 FocusRequester，把 requester 挂到那一项上，
 * 再调一次本函数——不要在页面里各写一遍 withFrameNanos 重试。
 */
@Composable
fun TvFirstFocus(requester: FocusRequester?, key: Any? = Unit) {
    if (requester == null) return
    LaunchedEffect(requester, key) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/**
 * 横向轨道（LazyRow）版的 TV 焦点滚动规则：**按整格跟焦**。
 *
 * 与 [TvGridFocusScroll] 同源：连按时脚本要当场把目标格摆好，不能靠动画追；
 * 左右各留 [comfort]，被聚焦的 chip 不许贴轨道边缘（贴边时它的描边会被轨道视口切掉）。
 *
 * @param focusedIndex 当前被聚焦格的下标；-1 表示焦点不在该轨道上
 */
@Composable
fun TvRowFocusScroll(
    state: LazyListState,
    focusedIndex: Int,
    comfort: Dp = 16.dp,
) {
    val comfortPx = with(LocalDensity.current) { comfort.toPx() }
    LaunchedEffect(state, focusedIndex) {
        if (focusedIndex < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
        if (item == null) {
            state.scrollToItem(focusedIndex)
            return@LaunchedEffect
        }
        val viewport = info.viewportSize.width.toFloat()
        val start = item.offset.toFloat()
        val end = start + item.size
        val delta = when {
            start < comfortPx -> start - comfortPx
            end > viewport - comfortPx -> end - (viewport - comfortPx)
            else -> 0f
        }
        if (delta != 0f) state.scrollBy(delta)
    }
}

/**
 * 规则 3 的早期写法：给容器自己留底部安全边。
 *
 * 懒列表（LazyVerticalGrid / LazyColumn）请改用 [gridScrollRoom]——它同时管上下两端，
 * 写在一个 modifier 里才不会被漏掉一端。这个函数留给非懒滚动容器（如 DetailScreen 的
 * verticalScroll 外壳）。
 */
fun Modifier.listBottomSafe(): Modifier =
    this.padding(bottom = ListMetrics.ListBottomSafe)
