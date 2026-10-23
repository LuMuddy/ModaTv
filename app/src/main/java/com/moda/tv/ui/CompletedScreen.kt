package com.moda.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.LocalContentColor
import com.moda.tv.R
import com.moda.tv.model.LibraryEntry
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.components.TvGridFocusScroll
import com.moda.tv.ui.components.gridScrollRoom
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import java.util.Locale
import kotlinx.coroutines.launch

/** 网格列数：按行跟焦与「按下去下一行」都按它算下标，改列数只改这一处 */
private const val CompletedColumns = 4

/**
 * 已看完独立页：网格海报 + 「看完」角标，点卡片直接续播。
 *
 * 与 [HistoryScreen] 刻意保持同一套骨架（网格 / 卡片尺寸 / 删除按钮位置），
 * 理由很实际：两页在用户眼里是同一类东西，长得像才不需要重新学一遍遥控器怎么走。
 * 差别只有三处，每处都对应一条真实语义：
 *   - 角标显示「看完」而不是进度条——这一页的条目没有"看到哪"可言；
 *   - 删除只删这一集（历史那边删的是整部剧），因为它按集记录；
 *   - 没有「第 N 集」以外的进度文案，补的是「时长」。
 *
 * 顶部不放「返回」：遥控器返回键由 AppRoot 的 BackHandler 全局接管，
 * 这一页再多一个返回只是重复入口，还占掉工具栏的视觉重量。
 */
@Composable
fun CompletedScreen(
    entries: List<LibraryEntry>,
    onResume: (LibraryEntry) -> Unit,
    onDelete: (LibraryEntry) -> Unit,
    onClear: () -> Unit,
) {
    // 「清空」不可撤销，遥控器上半点失误就是全删：先弹确认再真正清
    var confirmClear by remember { mutableStateOf(false) }
    // TV 的按行跟焦：焦点项下标进状态，滚动由 TvGridFocusScroll 按"整行"接管
    val gridState = rememberLazyGridState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = gridState, focusedIndex = focusedIndex, columns = CompletedColumns)

    // 每张卡的海报一个 requester：按下键要显式点名下一行的海报，
    // 交给几何搜索的话最近的落点是本格的「删除」键（见 CompletedCard 的 onPreviewKeyEvent）
    //
    // 为什么按条目存、而不是 remember(entries.size) { List(size) { FocusRequester() } }：
    // TvFirstFocus 内部是 LaunchedEffect(requester, key)，**requester 本身就是 key**。
    // 按 size 建列表的话，删掉一张就整批换新对象，于是"列表规模变了"被翻译成"重新抢首焦点"：
    // 焦点被送回第 0 张，系统 bring-into-view 顺手把列表拉回顶部——历史页踩过的同一处回归。
    // 挂在 item key（cid#集号）上，同一条记录的 requester 就一直是同一个对象，size 变化惊动不了首焦点。
    val posterFocus = remember { HashMap<String, FocusRequester>() }
    fun posterAt(index: Int): FocusRequester {
        val entry = entries[index]
        // key 必须与 itemsIndexed 的 key 一致：同一部剧的多集各自一个 requester，
        // 只认 cid 的话两集共用同一个对象，删除后焦点会落到另一集上
        return posterFocus.getOrPut("${entry.cid}#${entry.episodeIndex}") { FocusRequester() }
    }

    // 工具栏「下」的落点：requester 在组合期就建好，因为 focusProperties 的 lambda
    // 不在组合期执行，不该在里面现造对象
    val firstPosterFocus = if (entries.isEmpty()) null else posterAt(0)

    // 删除后要接住焦点的那一格下标 = 被删掉的下标（删完之后那个下标上正是顶替者）。
    // 只有真的删了一格时才有值，其余时候保持 -1，不参与首焦点。
    var pendingFocusIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    // 首焦点交给 TvFirstFocus：等两帧再抢、失败不抛，也不用布尔 key——
    // 早先「先置位再 requestFocus」的写法一旦某次抢失败就永久放弃，进页面白框永远不出现。
    // 空态没有可聚焦元素不成问题：返回键由 AppRoot 全局接，不依赖焦点落在哪个控件上。
    // 传进去的是**第 0 条自己的 requester**：只要第 0 条还是同一条记录，对象 identity 就不变，
    // 列表增删不会再触发一次"回第 0 条"的首焦点（只在首次进入这一页时抢一次）。
    TvFirstFocus(firstPosterFocus)

    // 删除后的焦点接力：焦点落到顶替被删那条的新卡上，而不是回到第 0 条。
    //
    // 为什么 key 是 entries.size 而不是 pendingFocusIndex：pending 是在数据变化**之前**写的，
    // 以它作 key，effect 会先对着那条还没被删的卡抢一次焦点，抢成功就把 pending 清空，
    // 等新数据到手已经没人接力——焦点停在半空（遥控器按确认没反应）。
    // 所以只在"规模真的变了"时进场，进场后再读 pending 决定落点。
    LaunchedEffect(entries.size) {
        if (pendingFocusIndex < 0) return@LaunchedEffect
        // 删到空列表：没有可聚焦项，清标记直接退出（不 requestFocus，不重试空转）
        if (entries.isEmpty()) {
            pendingFocusIndex = -1
            return@LaunchedEffect
        }
        // 删的是最后一条时 pendingFocusIndex 恰好等于新的 size，夹到新的最后一条
        var target = minOf(pendingFocusIndex, entries.lastIndex)
        repeat(4) {
            // 删除那一帧里顶替者还没建焦点节点，立刻 requestFocus 必抛
            withFrameNanos { }
            val moved = runCatching { posterAt(target).requestFocus() }.getOrDefault(false)
            if (moved) {
                pendingFocusIndex = -1
                return@LaunchedEffect
            }
            // 顶替者还在视口外时懒网格根本没给它建节点：退一格到已经组合好的前一条。
            // 宁可停在"新位置的前一格"，也不能让焦点空着——空焦点是本项目最忌讳的状态。
            if (target > 0) target -= 1
        }
        // 都抢不到就清标记收工（不做无限重试，否则就是空转）；下一次按键仍由几何搜索落位
        pendingFocusIndex = -1
    }

    /**
     * 按「下」：走到下一行的同一列，返回 true 表示这次按键已被接管。
     *
     * 为什么不用 focusProperties { down = ... }：那要求目标节点此刻已在组合里，
     * 而懒网格不会给屏幕外的项建焦点节点，指过去会抛「FocusRequester is not initialized」。
     * 所以按下时先在协程里把目标行滚进视口、再逐帧重试要焦点（最多 3 帧），
     * 而不是挂一个指向屏幕外节点的 focusProperties。
     */
    fun moveDownToNextRow(index: Int): Boolean {
        val next = index + CompletedColumns
        if (next !in entries.indices) return false
        focusedIndex = next
        scope.launch {
            // 目标行若还在视口外，懒网格根本没为它建焦点节点，requestFocus 会直接抛。
            // 所以先把它滚进视口（scrollToItem 是立即到位，不是动画），
            // 再最多试 3 帧：requestFocus 返回 false/抛异常就等下一帧重来，成功立刻收工。
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == next }) {
                gridState.scrollToItem(next)
            }
            repeat(3) {
                withFrameNanos { }
                val moved = runCatching { posterAt(next).requestFocus() }
                    .getOrDefault(false)
                if (moved) return@launch
            }
        }
        return true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ModaColors.Bg)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
    ) {
        // 标题靠左、清空靠右：工具栏两端各挂一件东西，中间留空才看得出这是两个不相干的操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.lib_done_title, entries.size),
                style = TvHeading,
                color = ModaColors.Text,
            )
            // 没有单独的刷新按钮：这一页读的是本地 DataStore，刷新不会有任何新东西
            ActionButton(
                text = stringResource(R.string.lib_clear_all),
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
                // 从工具栏按「下」直接进第一张卡：不指定落点时几何搜索会挑中
                // 最右那一格海报下方的删除键，一眼看去像"光标跳到了删除上"
                modifier = if (firstPosterFocus != null) {
                    Modifier.focusProperties { down = firstPosterFocus }
                } else {
                    Modifier
                },
            )
        }

        Spacer(Modifier.height(24.dp))

        if (entries.isEmpty()) {
            // 两行文案的换行写在 string 资源里（lib_done_empty），
            // EmptyState 签名只收一个 message，拆成两条资源就得改组件契约
            EmptyState(stringResource(R.string.lib_done_empty))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
// weight(1f)：Column 里 fillMaxSize 会拿到整列高度（不是剩余），
                // 网格视口于是比屏幕长出一截，末行贴着屏幕下沿被切、焦点滚动也以为它已可见。
                // gridScrollRoom：上下各让一档**视口之外**的余量，末行焦点描边的下沿才不被切平
                modifier = Modifier.fillMaxWidth().weight(1f).gridScrollRoom(),
                // 同历史页：顶部余量写进 contentPadding 才不会被焦点滚动吃掉
                contentPadding = ListMetrics.gridContentPadding(top = 12.dp, side = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                // 纵向缝隙由每项的 10dp 余量提供，这里不再叠加
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // 用 itemsIndexed 而不是 items + entries.indexOf(entry)：
                // LibraryEntry 是 data class，indexOf 走的是 equals 全字段比较，
                // 几百条时是平方级开销，而且每次重组都要重算一遍
                itemsIndexed(
                    items = entries,
                    // key 不能只用 cid：同一部剧的多个已看完集会在同一个网格里并存
                    key = { _, entry -> "${entry.cid}#${entry.episodeIndex}" },
                ) { index, entry ->
                    CompletedCard(
                        entry = entry,
                        posterFocus = posterAt(index),
                        onMoveDown = { moveDownToNextRow(index) },
                        onFocused = { focusedIndex = index },
                        onResume = { onResume(entry) },
                        onDelete = {
                            // 先记下"我要删掉第几格"，再让数据真的变：跨重组就靠它把焦点交回原位
                            pendingFocusIndex = index
                            onDelete(entry)
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearConfirmDialog(
            title = stringResource(R.string.lib_done_clear_title),
            message = stringResource(R.string.lib_done_clear_message, entries.size),
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * 单条：海报（右上角「看完」角标）+ 剧名 / 第 N 集 / 时长 + 删除。
 *
 * 删除按钮放在海报下方而不是叠在卡片内部：TV 的方向键按几何位置找下一个焦点，
 * 卡片内部再套一个可聚焦按钮时，从海报按方向键会直接跳到相邻卡片，删除按钮永远够不着。
 */
@Composable
private fun CompletedCard(
    entry: LibraryEntry,
    /** 本格海报的焦点节点；也是「删除」按上键时的回程落点 */
    posterFocus: FocusRequester,
    /** 按「下」去下一行；返回 true 表示这次按键已被接管（末行没有下一行，交回默认搜索） */
    onMoveDown: () -> Boolean,
    onFocused: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val corner = 12.dp
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Card(
            onClick = onResume,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(posterFocus)
                .onFocusChanged { if (it.isFocused) onFocused() }
                // 「下」显式进下一行的同一列：删除键贴在海报下沿，几何搜索一定先命中它，
                // 连按向下会逐格卡在删除键上，走不到下一行。与 HistoryScreen 同一套处理。
                .onPreviewKeyEvent { ev ->
                    ev.type == KeyEventType.KeyDown &&
                        ev.key == Key.DirectionDown &&
                        onMoveDown()
                },
            shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
            colors = CardDefaults.colors(
                containerColor = ModaColors.Surface,
                contentColor = ModaColors.Text,
                focusedContainerColor = ModaColors.SurfaceHigh,
                focusedContentColor = ModaColors.Text,
                pressedContainerColor = ModaColors.Accent,
                pressedContentColor = Color.White,
            ),
            // 不放大：网格视口会裁掉放大溢出，末行卡片的下沿/上沿会缺一条边
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border(
                    border = BorderStroke(3.dp, ModaColors.Focus),
                    shape = RoundedCornerShape(corner),
                ),
            ),
        ) {
            Box(Modifier.fillMaxWidth()) {
                RemoteImage(
                    url = entry.cover,
                    title = entry.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    corner = 0.dp,
                )
                // 角标压在右上角：海报右下角是历史上进度条的位置，
                // 两页用同一个角落表达"状态"会让用户以为这一条还剩一点没看完
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(ModaColors.Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        stringResource(R.string.lib_done_badge),
                        style = TvCaption.copy(fontSize = 13.sp),
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 文字吃满剩余宽度：剧名再长也只挤自己，删除按钮始终钉在列右边缘
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = TvBody.copy(fontSize = 18.sp),
                    color = ModaColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episodeLabel(entry.episodeIndex),
                    style = TvCaption.copy(fontSize = 14.sp),
                    color = ModaColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 时长而不是进度：这一页的条目没有"看到哪"可言
                if (entry.durationMs > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.lib_duration, clock(entry.durationMs)),
                        style = TvCaption.copy(fontSize = 14.sp),
                        color = ModaColors.AccentSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            DeleteButton(
                onDelete = onDelete,
                onFocused = onFocused,
                upFocus = posterFocus,
                onMoveDown = onMoveDown,
            )
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 集号按人话显示：库里存的是 0 起下标，用户看到的是「第几集」 */
@Composable
private fun episodeLabel(episodeIndex: Int): String =
    if (episodeIndex < 0) {
        stringResource(R.string.lib_episode_unknown)
    } else {
        stringResource(R.string.lib_episode_no, episodeIndex + 1)
    }

/** 与 HistoryScreen 的删除按钮同一套视觉：常态退进背景，焦点到了才亮 */
@Composable
private fun DeleteButton(
    onDelete: () -> Unit,
    /** 焦点落到删除键时也要上报本格下标：末行时它常落在视口外，不跟焦就等于焦点丢了 */
    onFocused: () -> Unit,
    /** 按「上」回本格海报 */
    upFocus: FocusRequester,
    onMoveDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val corner = 8.dp
    Card(
        onClick = onDelete,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            // 显式方向：上回本格海报，下去下一行的海报。
            // 不写的话它是「下」的默认最近落点，进去以后下键只会停在原地。
            .focusProperties { up = upFocus }
            .onPreviewKeyEvent { ev ->
                ev.type == KeyEventType.KeyDown &&
                    ev.key == Key.DirectionDown &&
                    onMoveDown()
            },
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = ModaColors.TextDim,
            focusedContainerColor = ModaColors.Focus,
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = ModaColors.SurfaceHigh,
            pressedContentColor = ModaColors.TextDim,
        ),
        // 不放大：网格视口会裁掉放大溢出，末行卡片的下沿/上沿会缺一条边
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(3.dp, ModaColors.Focus),
                shape = RoundedCornerShape(corner),
            ),
        ),
    ) {
        // 保留 56x40dp 的点击/焦点面积：3 米外遥控器要把焦点停在这上面，太小会一直滑过去
        Box(
            Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 40.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 必须显式跟随内容色：TvBody 把颜色烤死在 style.color 上，
            // 不传 color 的话聚焦白底上仍是浅色字，等于看不见
            Text(
                stringResource(R.string.lib_delete),
                style = TvBody.copy(fontSize = 14.sp),
                color = LocalContentColor.current,
            )
        }
    }
}
