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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.res.stringResource
import com.moda.tv.R
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import com.moda.tv.ui.theme.TvTitle
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
            text = title.trim().take(1).ifBlank { stringResource(R.string.core_cover_fallback) },
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
    // 默认值必须走资源：写死的中文在英文系统上会原样蹦出来
    text: String = stringResource(R.string.core_loading),
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
        Text(stringResource(R.string.core_load_failed), style = TvHeading, color = ModaColors.Danger)
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
                text = stringResource(R.string.core_retry),
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
                text = stringResource(R.string.core_reload),
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
 * 认行**只认实际排布**，不做下标算术：同一行的各项 y 完全一致（网格行内顶端对齐），
 * 满宽 span 项（首页「继续观看」标题，GridItemSpan(GRID_COLUMNS)）自成一行。
 * 旧实现用 `focusedIndex - focusedIndex % columns` 反推行号，前提是「下标 = 行号 × 列数」；
 * 一旦网格里插进 span 项，这个前提就断了——它占掉一个 item 下标却不占一整行，
 * 行号从此整体错位，滚动量按「标题行 + 内容行」的并集算，用户滚一次多顶一行、
 * 退出再进来位置对不上。见 [columns] 的说明。
 *
 * @param focusedIndex 当前被聚焦项的下标（网格里的**绝对** item 下标）；-1 表示焦点不在网格上
 * @param columns 只为兼容既有调用保留，实现不再读它（理由见上）
 */
@Composable
fun TvGridFocusScroll(
    state: LazyGridState,
    focusedIndex: Int,
    @Suppress("UNUSED_PARAMETER") columns: Int = 1,
    comfort: Dp = 16.dp,
) {
    val comfortPx = with(LocalDensity.current) { comfort.toPx() }
    LaunchedEffect(state, focusedIndex, comfortPx) {
        if (focusedIndex < 0) return@LaunchedEffect
        // 焦点项可能压根还没布局（连按跳了很远）：先把它带进视口，再量新位置
        if (state.layoutInfo.visibleItemsInfo.none { it.index == focusedIndex }) {
            state.scrollToItem(focusedIndex)
            withFrameNanos { }
        }
        // 第一趟当场摆好，第二趟隔一帧再量一次重算。
        //
        // 为什么要有第二趟：系统默认的 bring-into-view 是条动画，它只把目标带成"刚好可见"。
        // 它若在第一趟之后（或取消第一趟之后）又动了列表，位置就停在半截——
        // 真机故障「按下到第二行再按上回第一行，顶部回不到原来的位置」就是这么来的。
        // 两趟都是**双向**算差值（向上越界给负 delta），所以 A→B→A 一定回到 A 的原始偏移，
        // 而不是"目标行已经露出来就不动"。
        repeat(2) { pass ->
            if (pass > 0) withFrameNanos { }
            val info = state.layoutInfo
            val focusedItem = info.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
                ?: return@LaunchedEffect
            // 行 = 与被聚焦项 y 相同的那些可见项；span 项 y 与内容行不同，自然落在自己那一行
            val rowItems = info.visibleItemsInfo.filter { it.offset.y == focusedItem.offset.y }
            val top = rowItems.minOf { it.offset.y.toFloat() }
            // LazyGridItemInfo.size 是 IntSize，纵向高度取 height
            val bottom = rowItems.maxOf { (it.offset.y + it.size.height).toFloat() }
            val viewport = info.viewportSize.height.toFloat()
            val delta = when {
                // 向上越界：负 delta，把内容推回去（首行因此能回到 comfort 位置、顶部 padding 复位）
                top < comfortPx -> top - comfortPx
                bottom > viewport - comfortPx -> bottom - (viewport - comfortPx)
                else -> 0f
            }
            if (abs(delta) < 1f) return@LaunchedEffect
            // scrollBy 是"立即"滚，不是动画：连按时每一次按键都当场把行摆好，
            // 不会出现"按了三下、画面还在追第一下"的错位。
            // 到边界时它会被夹住，这里不做无限重试（差值永远不为 0 会空转）。
            state.scrollBy(delta)
        }
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
 * 非懒滚动容器（`Modifier.verticalScroll`）版的 TV 纵向跟焦，与 [TvGridFocusScroll] /
 * [TvRowFocusScroll] 同源：**按整行跟焦 + scrollBy 立即到位，不用动画**。
 *
 * 为什么不能照抄懒列表那两套：`verticalScroll` 里的子项没有 `visibleItemsInfo`，
 * 拿不到任何"第几项在哪"的元数据，只能自己去量。所以这里由**行自己上报**：
 * 每行挂 [TvColumnFocusScroller.itemModifier]，被聚焦时把量到的位置和视口比一次，
 * 缺多少补多少。调用方因此不需要维护 focusedIndex。
 *
 * 为什么不用 Compose 默认的 bring-into-view：那套是给触摸/鼠标设计的——
 * 它只把目标滚到"刚好露出来"，于是被聚焦行永远贴着视口边（描边被切、上下没有呼吸空间），
 * 而且它是动画，连按时画面追不上光标。R2 要的是"整行 + 上下各留 comfort + 立即"。
 *
 * ## 为什么只做到 comfort 还不够：必须记住原位
 *
 * comfort 是一个**区间**，不是**点**：只要被聚焦行离上下沿都不少于 [comfort]，helper 就认定
 * "已经到位"，直接收工。可用空间里落的每一个偏移都满足这个条件，位置因此不唯一。
 *
 * 真机故障（设置 → 播放）：进页面时首行卡片上沿在 y=276，按 6 次下、再按 6 次上回首行，
 * 上沿变成 244——差的 32px 正是"首行的自然位置"与 comfort 之间的那段余量：回程算出的 delta
 * 恰好为 0，滚动器认为已经舒服了就不再滚，页面停在 comfort，而不是进页面时的偏移。
 * 对照 [TvGridFocusScroll]：那边内容顶部 padding(12dp) 比 comfort(16dp) 小，回程 delta 恒为负、
 * 被滚动边界夹到 0，于是"看起来"能回原位——那是参数凑巧，不是机制。
 *
 * 结论：**位置必须被记住**。这个滚动器因此维护 `行 key → 滚动偏移` 的记忆：
 * 离开一行时记下当前偏移；回到这一行就直接 scrollTo 那个偏移，并**跳过** comfort 校正
 * （校正会把位置重新归一到 comfort，正好是这次要避免的事）。
 *
 * ## 用法（三步，缺一不可）
 *
 * ```kotlin
 * val scrollState = rememberScrollState()
 * val columnFocus = TvColumnFocusScroll(scrollState)
 *
 * Column(
 *     Modifier
 *         .fillMaxWidth()
 *         .weight(1f)
 *         .then(columnFocus.viewportModifier)   // ① 必须写在 .verticalScroll 之前
 *         .verticalScroll(scrollState),
 * ) {
 *     rows.forEachIndexed { i, row ->
 *         SettingRow(
 *             // ② 挂到**整行**上。不必是焦点节点本身：内部判的是 hasFocus，
 *             //    焦点落在这一行的任何子节点上都会触发，量到的也是这一行的外框
 *             //    （所以描边余量、行间距都一起被保住）。
 *             modifier = Modifier.then(columnFocus.itemModifier(i)),
 *             ...
 *         )
 *     }
 * }
 * ```
 *
 * ③ [TvColumnFocusScroller.viewportModifier] 的位置不能挪到 `.verticalScroll` 之后：
 * 写在后面量到的是**跟随滚动的内层内容**，视口上下沿会随着滚动一起跑，等于没量。
 *
 * 行自己上报焦点/坐标，所以这个工具不接管按键、也不抢焦点：它只在焦点已经落到某一行之后
 * 把那一行摆到舒适位置，与 [TvRowFocusScroll] 的分工完全一致。
 */
@Composable
fun TvColumnFocusScroll(
    state: ScrollState,
    comfort: Dp = 16.dp,
): TvColumnFocusScroller {
    val comfortPx = with(LocalDensity.current) { comfort.toPx() }
    val scope = rememberCoroutineScope()
    // 行偏移记忆的生命期就是这个 remember：容器离开组合（换页 / 换子页 / 换 state）时整份丢弃，
    // 不会把上一页的偏移带到下一页。
    return remember(state, comfortPx) { TvColumnFocusScroller(state, comfortPx, scope) }
}

/**
 * [TvColumnFocusScroll] 返回的滚动器。两个修饰符分工见 [viewportModifier] / [itemModifier]。
 */
class TvColumnFocusScroller internal constructor(
    private val state: ScrollState,
    private val comfortPx: Float,
    private val scope: CoroutineScope,
) {
    /**
     * 视口坐标（滚动容器外框）。
     *
     * 存坐标对象而不是 Rect：光标滚下去之后同一个节点会换位置，
     * `LayoutCoordinates` 是活引用，读的时候永远是最新位置；存 Rect 就是存了一份快照。
     */
    private var viewport: LayoutCoordinates? = null

    /** 每个可聚焦行自己的坐标，key 由调用方给（一般就是它在列表里的下标） */
    private val itemCoords = mutableMapOf<Any, LayoutCoordinates>()

    /**
     * 行 key → 这一行落定（或被离开）时的滚动偏移。**回原位全靠它**。
     *
     * 为什么不能省：comfort 只保证"被聚焦行离上下沿都有余量"，落在这个区间里的偏移有无穷多个，
     * 光靠"是否满足 comfort"无法唯一确定位置，回程就会停在 comfort 而不是原来的偏移
     * （详见 [TvColumnFocusScroll] 上的实测记录）。
     *
     * 生命期 = 本滚动器 = 容器在组合里的时间：离开页面即丢弃，不跨页污染。
     */
    private val rowOffsets = mutableMapOf<Any, Int>()

    /** 最近持有焦点的行 key：用来判断"行有没有换"，以及离开的是哪一行 */
    private var activeKey: Any? = null

    /** 连按时只保留最后一次跟焦：上一次校正还没走完就会被新的顶掉，不让它和新行抢滚动轴 */
    private var focusJob: Job? = null

    /** ① 挂在滚动容器外框上（写在 `.verticalScroll(state)` 之前），用来量视口上下沿 */
    val viewportModifier: Modifier = Modifier.onGloballyPositioned { viewport = it }

    /** ② 挂到每一个可聚焦行上；被聚焦时把这一行摆到"记忆里的偏移"或 comfort 位置 */
    fun itemModifier(key: Any): Modifier = Modifier
        .onGloballyPositioned { itemCoords[key] = it }
        .onFocusChanged { focusState ->
            // 判 hasFocus 而不是 isFocused：非懒容器里的"行"通常不是一个可聚焦叶子，
            // 例如 SettingsSections 的 SettingRow——传进去的 modifier 挂在外层 Box 上，
            // 真正的焦点节点是里面的 Card。此时 Box 的 isFocused 永远为 false，
            // hasFocus 才是"这一行（含子节点）里现在有焦点"。
            // 量到的也是这个外层 Box，于是整行连同它两侧的描边余量一起被保住。
            if (!focusState.hasFocus) return@onFocusChanged
            // 同一行内部在子节点之间换焦点不算"行切换"：此刻 state.value 可能正被系统
            // bring-into-view 挪动，这时候记账等于记了个中间值；而这一行本来就已经摆好了。
            val leaving = activeKey
            if (leaving == key) return@onFocusChanged
            // 先记账再处理新行：离开的那一行"停在哪"只有这一刻知道，异步再读就晚了。
            if (leaving != null) rowOffsets[leaving] = state.value
            activeKey = key
            focusJob?.cancel()
            focusJob = scope.launch { scrollIntoView(key) }
        }

    private suspend fun scrollIntoView(key: Any) {
        val remembered = rowOffsets[key]
        val positioned = if (remembered != null) {
            restore(remembered)
            true
        } else {
            // 这一行第一次拿焦点：沿用按坐标双向补差值的 comfort 校正
            comfort(key)
        }
        // 量到了才落账（坐标还没上树时 comfort 什么也没做，记下来就是个假位置）。
        // 只在这一行仍是当前行时落账：连按把它顶掉之后，别用过期位置污染记忆。
        if (positioned && activeKey == key) rowOffsets[key] = state.value
    }

    /**
     * 有记忆：精确回到这一行上次落定的偏移，**不做 comfort 校正**。
     *
     * 校正做的事恰恰是"把位置归一到 comfort"，对回原位来说是反的——所以才要单独一条路径。
     */
    private suspend fun restore(remembered: Int) {
        repeat(2) { pass ->
            // 第二趟隔一帧再压一次：系统默认的 bring-into-view 是条动画，它在焦点事件之后
            // 才动列表，会把刚才 scrollTo 的结果顶偏。再 scrollTo 一次同值即可取消它并压回原位。
            if (pass > 0) withFrameNanos { }
            // 内容变短时记忆值可能超过当前 maxValue：先夹到当前可达范围。
            // 夹过的值同时用于判断"是否已经到位"——拿夹不住的原值去比永远不相等，会空转。
            state.scrollTo(remembered.coerceIn(0, state.maxValue))
        }
    }

    /**
     * 没记忆：按坐标双向补差值，让这一行离上下沿都有 [comfortPx]。
     *
     * @return 是否真的量到了（坐标/视口没上树时返回 false，调用方据此决定要不要记账）
     */
    private suspend fun comfort(key: Any): Boolean {
        val target = itemCoords[key] ?: return false
        // 最多补 3 次：靠近两端时 scrollBy 会被滚动边界夹住，差值永远不为 0，
        // 没有次数上限的循环会在那里空转（DetailScreen 里的居中滚动踩过同一个坑）。
        // 需要多趟是因为默认的 bring-into-view 也在同一条轴上动，它动画走完之后位置还会变。
        repeat(3) {
            val vp = viewport
            if (vp == null || !vp.isAttached || !target.isAttached) return false
            withFrameNanos { }
            // 直接换算到视口坐标系：不用再拿"根坐标减去视口根坐标"，
            // 也自动把滚动位移算进去了
            val box = vp.localBoundingBoxOf(target, clipBounds = false)
            val viewportHeight = vp.size.height.toFloat()
            val delta = when {
                box.top < comfortPx -> box.top - comfortPx
                box.bottom > viewportHeight - comfortPx -> box.bottom - (viewportHeight - comfortPx)
                else -> 0f
            }
            if (abs(delta) < 1f) return true
            state.scrollBy(delta)
        }
        return true
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
