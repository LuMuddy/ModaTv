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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.moda.tv.model.LibraryEntry
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading

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
    val firstCardFocus = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }
    // 与历史页同一条规则：清空是批量不可撤销操作，先弹确认
    var confirmClear by remember { mutableStateOf(false) }

    // 首帧布局完成前 requestFocus 会直接抛，等两帧再抢焦点。
    // 空态没有可聚焦元素是不成问题的：返回键由 AppRoot 全局接，不依赖焦点落在哪个控件上。
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
        // 与历史页同一条规则：标题靠左、「清空全部」靠右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("我的收藏 ${entries.size} 部", style = TvHeading, color = ModaColors.Text)
            ActionButton(
                text = "清空全部",
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
            )
        }

        Spacer(Modifier.height(24.dp))

        if (entries.isEmpty()) {
            EmptyFavorites()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                // 同历史页：四周留出聚焦放大的余量，否则卡片选中时顶部与左右会被裁掉
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                itemsIndexed(items = entries, key = { _, entry -> entry.cid }) { index, entry ->
                    FavoriteCard(
                        entry = entry,
                        focusRequester = if (index == 0) firstCardFocus else null,
                        onOpen = { onOpen(entry) },
                        onDelete = { onDelete(entry.cid) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearConfirmDialog(
            title = "清空全部收藏？",
            message = "将删除 ${entries.size} 部收藏，删除后无法恢复。",
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
            text = "还没有收藏的剧集",
            style = TvBody.copy(fontSize = 24.sp),
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        // 说清入口在哪：否则空页只会让人以为是功能坏了
        Text(
            text = "在剧集详情页点「收藏」即可加入这里",
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
    focusRequester: FocusRequester?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val corner = 12.dp
    Column(Modifier.fillMaxWidth()) {
        Card(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
            colors = CardDefaults.colors(
                containerColor = ModaColors.Surface,
                contentColor = ModaColors.Text,
                focusedContainerColor = ModaColors.SurfaceHigh,
                focusedContentColor = ModaColors.Text,
                pressedContainerColor = ModaColors.Accent,
                pressedContentColor = Color.White,
            ),
            scale = CardDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1f),
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
                text = entry.title.ifBlank { "未命名剧集" },
                modifier = Modifier.weight(1f),
                style = TvBody.copy(fontSize = 18.sp),
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            FavoriteDeleteButton(onDelete)
        }
    }
}

@Composable
private fun FavoriteDeleteButton(onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val corner = 8.dp
    Card(
        onClick = onDelete,
        modifier = modifier,
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
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1f),
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
                "删除",
                style = TvBody.copy(fontSize = 14.sp),
                color = androidx.tv.material3.LocalContentColor.current,
            )
        }
    }
}
