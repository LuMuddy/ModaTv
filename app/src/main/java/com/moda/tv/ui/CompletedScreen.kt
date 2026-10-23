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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.LocalContentColor
import com.moda.tv.model.LibraryEntry
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.TvGridFocusScroll
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import java.util.Locale

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
    val firstCardFocus = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }
    // 「清空」不可撤销，遥控器上半点失误就是全删：先弹确认再真正清
    var confirmClear by remember { mutableStateOf(false) }
    // TV 的按行跟焦：焦点项下标进状态，滚动由 TvGridFocusScroll 按"整行"接管
    val gridState = rememberLazyGridState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = gridState, focusedIndex = focusedIndex, columns = 4)

    // 首帧布局完成前 requestFocus 会直接抛，等两帧再抢。
    // 空态没有可聚焦元素不成问题：返回键由 AppRoot 全局接，不依赖焦点落在哪个控件上。
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty() && !firstFocusDone) {
            firstFocusDone = true
            withFrameNanos { }
            withFrameNanos { }
            runCatching { firstCardFocus.requestFocus() }
        }
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
            Text("已看完 ${entries.size} 条", style = TvHeading, color = ModaColors.Text)
            // 没有单独的刷新按钮：这一页读的是本地 DataStore，刷新不会有任何新东西
            ActionButton(
                text = "清空全部",
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
            )
        }

        Spacer(Modifier.height(24.dp))

        if (entries.isEmpty()) {
            EmptyState("还没有看完的剧集\n看完一集之后会自动记到这里")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
// weight(1f)：Column 里 fillMaxSize 会拿到整列高度（不是剩余），
                // 网格视口于是比屏幕长出一截，末行贴着屏幕下沿被切、焦点滚动也以为它已可见
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                        focusRequester = if (index == 0) firstCardFocus else null,
                        onFocused = { focusedIndex = index },
                        onResume = { onResume(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearConfirmDialog(
            title = "清空全部已看完？",
            message = "将删除 ${entries.size} 条已看完记录，删除后无法恢复（不影响观看历史）。",
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
    focusRequester: FocusRequester?,
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
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused() },
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
                        "看完",
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
                        text = "片长 " + clock(entry.durationMs),
                        style = TvCaption.copy(fontSize = 14.sp),
                        color = ModaColors.AccentSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            DeleteButton(onDelete)
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
private fun episodeLabel(episodeIndex: Int): String =
    if (episodeIndex < 0) "集号未知" else "第 ${episodeIndex + 1} 集"

/** 与 HistoryScreen 的删除按钮同一套视觉：常态退进背景，焦点到了才亮 */
@Composable
private fun DeleteButton(onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val corner = 8.dp
    Card(
        onClick = onDelete,
        modifier = modifier,
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
                "删除",
                style = TvBody.copy(fontSize = 14.sp),
                color = LocalContentColor.current,
            )
        }
    }
}
