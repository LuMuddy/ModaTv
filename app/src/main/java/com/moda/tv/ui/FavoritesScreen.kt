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
 */
@Composable
fun FavoritesScreen(
    entries: List<LibraryEntry>,
    onOpen: (LibraryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val firstCardFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }

    // 首帧布局完成前 requestFocus 会直接抛，等两帧再抢焦点；空态时焦点落到"返回"上
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isEmpty()) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { backFocus.requestFocus() }
        } else if (!firstFocusDone) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的收藏 ${entries.size} 部", style = TvHeading, color = ModaColors.Text)
            Spacer(Modifier.width(28.dp))
            ActionButton(text = "清空全部", onClick = onClear, enabled = entries.isNotEmpty())
            Spacer(Modifier.width(14.dp))
            ActionButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocus),
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

/** 单条收藏：海报 + 剧名 + 独立可聚焦的删除按钮（按钮放卡片外，理由同 HistoryCard） */
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
        Text(
            text = entry.title.ifBlank { "未命名剧集" },
            style = TvBody.copy(fontSize = 18.sp),
            color = ModaColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        FavoriteDeleteButton(onDelete)
    }
}

@Composable
private fun FavoriteDeleteButton(onDelete: () -> Unit) {
    val corner = 8.dp
    Card(
        onClick = onDelete,
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = ModaColors.SurfaceHigh,
            contentColor = ModaColors.TextDim,
            focusedContainerColor = ModaColors.Danger,
            focusedContentColor = Color.White,
            pressedContainerColor = ModaColors.Danger,
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
        Box(
            Modifier.width(92.dp).padding(vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("删除", style = TvCaption.copy(fontSize = 15.sp))
        }
    }
}
