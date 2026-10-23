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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.moda.tv.R
import com.moda.tv.model.LibraryEntry
import kotlinx.coroutines.launch
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.components.TvGridFocusScroll
import com.moda.tv.ui.components.gridScrollRoom
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading

/** 网格列数：按行跟焦与「按下去下一行」都按它算下标，改列数只改这一处 */
private const val FavoritesColumns = 4

/**
 * 收藏独立页：网格海报 + 剧名，点卡片进详情（不是直接播放）。
 * 交互与视觉刻意与 HistoryScreen 保持一套：同一个网格、同一个删除按钮位置、
 * 同一个「聚焦放大 + 白色描边」焦点语言——两个列表页长得不一样才是怪事。
 *
 * 与历史页的唯一差别是卡片上不画进度条、不显示集号：
 * 收藏条目写入时 episodeIndex/positionMs/durationMs 恒为 0（收藏是"追这部剧"，
 * 不是"看到第几分钟"），画出来会是一条永远为空的进度条和一个假的「第 1 集」。
 *
 * 删除按钮同样靠卡片右边缘、顶部同样不放「返回」按钮——两页并排看必须一模一样，
 * 差异只允许出现在内容上（有进度条 vs 没有），不允许出现在布局上。
 * 退出靠遥控器返回键：AppRoot 的 BackHandler 在栈深 > 1 时统一 pop。
 */
@Composable
fun FavoritesScreen(
    entries: List<LibraryEntry>,
    onOpen: (LibraryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    // 与历史页同一条规则：清空是批量不可撤销操作，先弹确认
    var confirmClear by remember { mutableStateOf(false) }
    // TV 的按行跟焦：焦点项下标进状态，滚动由 TvGridFocusScroll 按"整行"接管
    val gridState = rememberLazyGridState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = gridState, focusedIndex = focusedIndex, columns = FavoritesColumns)

    // 每张卡的海报一个 requester：按下键要显式点名下一行的海报，
    // 交给几何搜索的话最近的落点是本格的「删除」键（见 FavoriteCard 的 onPreviewKeyEvent）
    //
    // 为什么按条目（cid）存、而不是 remember(entries.size) { List(size) { FocusRequester() } }：
    // TvFirstFocus 内部是 LaunchedEffect(requester, key)，**requester 本身就是 key**。
    // 按 size 建列表的话，删掉一张就整批换新对象，于是"列表规模变了"被翻译成"重新抢首焦点"：
    // 焦点被送回第 0 张，系统 bring-into-view 顺手把列表拉回顶部——历史页踩过的同一处回归。
    // 挂在 item key（cid）上，同一部剧的 requester 就一直是同一个对象，size 变化惊动不了首焦点。
    val posterFocus = remember { HashMap<String, FocusRequester>() }
    fun posterAt(index: Int): FocusRequester =
        posterFocus.getOrPut(entries[index].cid) { FocusRequester() }

    // 工具栏「下」的落点：requester 在组合期就建好，因为 focusProperties 的 lambda
    // 不在组合期执行，不该在里面现造对象
    val firstPosterFocus = if (entries.isEmpty()) null else posterAt(0)

    // 删除后要接住焦点的那一格下标 = 被删掉的下标（删完之后那个下标上正是顶替者）。
    // 只有真的删了一格时才有值，其余时候保持 -1，不参与首焦点。
    var pendingFocusIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    // 首焦点交给 TvFirstFocus：等两帧再抢、失败不抛，也不用布尔 key——
    // 早先「先置位再 requestFocus」的写法一旦某次抢失败就永久放弃，进页面白框永远不出现。
    // 空态没有可聚焦元素是不成问题的：返回键由 AppRoot 全局接，不依赖焦点落在哪个控件上。
    // 传进去的是**第 0 张自己的 requester**：只要第 0 张还是同一部剧，对象 identity 就不变，
    // 列表增删不会再触发一次"回第 0 张"的首焦点（只在首次进入这一页时抢一次）。
    TvFirstFocus(firstPosterFocus)

    // 删除后的焦点接力：焦点落到顶替被删那张的新卡上，而不是回到第 0 张。
    //
    // 为什么 key 是 entries.size 而不是 pendingFocusIndex：pending 是在数据变化**之前**写的，
    // 以它作 key，effect 会先对着那张还没被删的卡抢一次焦点，抢成功就把 pending 清空，
    // 等新数据到手已经没人接力——焦点停在半空（遥控器按确认没反应）。
    // 所以只在"规模真的变了"时进场，进场后再读 pending 决定落点。
    LaunchedEffect(entries.size) {
        if (pendingFocusIndex < 0) return@LaunchedEffect
        // 删到空列表：没有可聚焦项，清标记直接退出（不 requestFocus，不重试空转）
        if (entries.isEmpty()) {
            pendingFocusIndex = -1
            return@LaunchedEffect
        }
        // 删的是最后一张时 pendingFocusIndex 恰好等于新的 size，夹到新的最后一张
        var target = minOf(pendingFocusIndex, entries.lastIndex)
        repeat(4) {
            // 删除那一帧里顶替者还没建焦点节点，立刻 requestFocus 必抛
            withFrameNanos { }
            val moved = runCatching { posterAt(target).requestFocus() }.getOrDefault(false)
            if (moved) {
                pendingFocusIndex = -1
                return@LaunchedEffect
            }
            // 顶替者还在视口外时懒网格根本没给它建节点：退一格到已经组合好的前一项。
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
        val next = index + FavoritesColumns
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
        // 与历史页同一条规则：标题靠左、「清空全部」靠右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.lib_fav_title, entries.size),
                style = TvHeading,
                color = ModaColors.Text,
            )
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
            EmptyFavorites()
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
                itemsIndexed(items = entries, key = { _, entry -> entry.cid }) { index, entry ->
                    FavoriteCard(
                        entry = entry,
                        posterFocus = posterAt(index),
                        onMoveDown = { moveDownToNextRow(index) },
                        onFocused = { focusedIndex = index },
                        onOpen = { onOpen(entry) },
                        onDelete = {
                            // 先记下"我要删掉第几格"，再让数据真的变：跨重组就靠它把焦点交回原位
                            pendingFocusIndex = index
                            onDelete(entry.cid)
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearConfirmDialog(
            // 标题复用设置页那条同义文案：同一件事不该有两套说法
            title = stringResource(R.string.clear_favorites_title),
            message = stringResource(R.string.lib_fav_clear_message, entries.size),
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun EmptyFavorites() {
    Column(
        Modifier.fillMaxSize().padding(bottom = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.lib_fav_empty),
            style = TvBody.copy(fontSize = 24.sp),
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        // 说清入口在哪：否则空页只会让人以为是功能坏了
        Text(
            text = stringResource(R.string.lib_fav_empty_hint),
            style = TvCaption.copy(fontSize = 16.sp),
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 单条收藏：海报 + 左剧名 / 右删除。
 * 布局与 HistoryCard 逐像素对齐——两页并排看只允许内容不同（有没有进度条），
 * 不允许删除按钮跑到别的位置上。
 */
@Composable
private fun FavoriteCard(
    entry: LibraryEntry,
    /** 本格海报的焦点节点；也是「删除」按上键时的回程落点 */
    posterFocus: FocusRequester,
    /** 按「下」去下一行；返回 true 表示这次按键已被接管（末行没有下一行，交回默认搜索） */
    onMoveDown: () -> Boolean,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val corner = 12.dp
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Card(
            onClick = onOpen,
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
                // cover 来自 SeriesDetail.cover（eps.meta.pic 原图）；取不到时 RemoteImage 自己回落色块
                RemoteImage(
                    url = entry.cover,
                    title = entry.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    corner = 0.dp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.title.ifBlank { stringResource(R.string.detail_untitled) },
                modifier = Modifier.weight(1f),
                style = TvBody.copy(fontSize = 18.sp),
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            FavoriteDeleteButton(
                onDelete = onDelete,
                onFocused = onFocused,
                upFocus = posterFocus,
                onMoveDown = onMoveDown,
            )
        }
    }
}

@Composable
private fun FavoriteDeleteButton(
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
        // 与历史页同一套：常态透明、只有浅灰字，聚焦才白底反白。
        // 之前常态是浅灰底 + 灰字、聚焦又变红底，与全站「焦点=白底深字」的语言不一致
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
        // 宽度跟着文字走，但保留 56x40dp 的遥控器可停靠面积
        Box(
            Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 40.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 显式跟随内容色：TvBody 把颜色烤死在 style.color 上，不传 color 聚焦后看不见
            Text(
                stringResource(R.string.lib_delete),
                style = TvBody.copy(fontSize = 14.sp),
                color = androidx.tv.material3.LocalContentColor.current,
            )
        }
    }
}
