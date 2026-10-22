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
import androidx.compose.foundation.layout.fillMaxHeight
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
import java.util.Locale
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
 * 观看历史独立页：网格海报 + 进度条，点卡片一步续播（不进详情）。
 * 删除按钮放在卡片下方而不是叠在卡片内部：TV 的方向键按几何位置找下一个焦点，
 * 卡片内部套可聚焦按钮时，从卡片按方向键会直接跳到相邻卡片，删除按钮就永远够不着。
 */
@Composable
fun HistoryScreen(
    entries: List<LibraryEntry>,
    onResume: (LibraryEntry) -> Unit,
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
            Text("历史记录 ${entries.size} 条", style = TvHeading, color = ModaColors.Text)
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
            EmptyHistory()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                // 四周都要留空间：卡片聚焦时放大 1.06 并画 3dp 描边，
                // 网格边界贴死会把顶部和左右裁掉（截图里「选中后上部分丢失」就是这个）
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                itemsIndexed(items = entries, key = { _, entry -> entry.cid }) { index, entry ->
                    HistoryCard(
                        entry = entry,
                        focusRequester = if (index == 0) firstCardFocus else null,
                        onResume = { onResume(entry) },
                        onDelete = { onDelete(entry.cid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        Modifier.fillMaxSize().padding(bottom = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有观看记录",
            style = TvBody.copy(fontSize = 24.sp),
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** 单条记录：海报（底部叠进度条）+ 剧名 + 第 N 集 + 独立可聚焦的删除按钮 */
@Composable
private fun HistoryCard(
    entry: LibraryEntry,
    focusRequester: FocusRequester?,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val corner = 12.dp
    Column(Modifier.fillMaxWidth()) {
        Card(
            onClick = onResume,
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
                RemoteImage(
                    url = entry.cover,
                    title = entry.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    corner = 0.dp,
                )
                // 图片上只留一条进度条；具体时间挪到海报下方，
                // 压在图上要加黑底才看得清，反而遮住画面
                if (entry.durationMs > 0L) {
                    WatchProgress(
                        positionMs = entry.positionMs,
                        durationMs = entry.durationMs,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
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
        // 「32:15 / 47:38 已看」：时间点对时间点，比百分比精确，也和播放器 OSD 的时钟一致
        if (entry.durationMs > 0L) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = watchedLabel(entry.positionMs, entry.durationMs) + " 已看",
                style = TvCaption.copy(fontSize = 14.sp),
                color = ModaColors.AccentSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        DeleteButton(onDelete)
    }
}

/** 进度条：positionMs / durationMs，越界值夹到 0..1，脏数据不会把条画到卡片外面 */
@Composable
private fun WatchProgress(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // 6dp 在电视上（3 米观看距离）几乎看不见，加粗到 10dp 并压在图片下沿。
    // 深色槽保证任何海报上都看得出「这是进度条」，蓝色填充表示已看部分。
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Color.Black.copy(alpha = 0.72f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(ModaColors.Accent),
        )
    }
}

/**
 * 进度文案：「32:15 / 47:38」这种 mm:ss（超过一小时用 h:mm:ss）。
 * 用时间点对时间点，比「已看 32 分钟」精确，也和播放器 OSD 的时钟格式一致。
 */
private fun watchedLabel(positionMs: Long, durationMs: Long): String =
    clock(positionMs) + " / " + clock(durationMs)

private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 集号按人话显示：库里存的是 0 起下标，用户看到的是「第几集」 */
private fun episodeLabel(episodeIndex: Int): String =
    if (episodeIndex < 0) "集号未知" else "第 ${episodeIndex + 1} 集"

@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    val corner = 8.dp
    Card(
        onClick = onDelete,
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        // 红底吸睛但廉价，而且和整页深色风格冲突。
        // 改成「常态低调、聚焦反白」——与首页、设置页的可聚焦元素保持同一套语言：
        // 焦点用白底 + 深字表达，删除动作靠文案本身传达，不需要靠颜色喊。
        colors = CardDefaults.colors(
            containerColor = ModaColors.Surface,
            contentColor = ModaColors.Danger,
            focusedContainerColor = ModaColors.Focus,
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = ModaColors.SurfaceHigh,
            pressedContentColor = ModaColors.Danger,
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
            // 必须显式跟随内容色：TvCaption 把颜色烤死在 style.color 上（#9AA0AE），
            // 聚焦时底色变红、字还是浅灰，几乎看不见——这就是「删除按钮很丑」的直接原因
            Text(
                "删除",
                style = TvBody.copy(fontSize = 16.sp),
                color = androidx.tv.material3.LocalContentColor.current,
            )
        }
    }
}
