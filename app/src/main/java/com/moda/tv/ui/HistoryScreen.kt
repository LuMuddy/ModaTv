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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.TvGridFocusScroll
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
 * 位置靠卡片右边缘：右侧是"这一列之外没有东西"的安全边，
 * 从海报按「下」正好落到删除上，和海报左对齐的初始位置对不上眼。
 * 顶部不放「返回」按钮——遥控器返回键由 AppRoot 的 BackHandler 全局接管，
 * 这一页再多一个返回只是重复入口，还占掉工具栏的视觉重量。
 */
@Composable
fun HistoryScreen(
    entries: List<LibraryEntry>,
    onResume: (LibraryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    val firstCardFocus = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }
    // 「清空全部」不可撤销，遥控器上半点失误就是全删：先弹确认，不直接调用 onClear
    var confirmClear by remember { mutableStateOf(false) }
    // TV 的按行跟焦：焦点项下标进状态，滚动由 TvGridFocusScroll 按"整行"接管
    val gridState = rememberLazyGridState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = gridState, focusedIndex = focusedIndex, columns = 4)

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
        // 标题靠左、「清空全部」靠右：工具栏两端各挂一件东西，
        // 中间留空才看得出这是两个不相干的操作；挤在标题后面会被读成标题的一部分
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("历史记录 ${entries.size} 条", style = TvHeading, color = ModaColors.Text)
            ActionButton(
                text = "清空全部",
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
            )
        }

        Spacer(Modifier.height(24.dp))

        if (entries.isEmpty()) {
            EmptyHistory()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
// weight(1f)：Column 里 fillMaxSize 会拿到整列高度（不是剩余），
                // 网格视口于是比屏幕长出一截，末行贴着屏幕下沿被切、焦点滚动也以为它已可见
                modifier = Modifier.fillMaxWidth().weight(1f),
                // 顶部 12dp 必须落在 contentPadding（视口之内）：焦点滚动会把项顶到视口边，
                // 写在视口外的留白用不上，首行卡片聚焦放大的上沿就会被切平。
                contentPadding = ListMetrics.gridContentPadding(top = 12.dp, side = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                // 纵向缝隙由每项的 10dp 余量提供（见 HistoryCard），这里不再叠加
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                itemsIndexed(items = entries, key = { _, entry -> entry.cid }) { index, entry ->
                    HistoryCard(
                        entry = entry,
                        focusRequester = if (index == 0) firstCardFocus else null,
                        onFocused = { focusedIndex = index },
                        onResume = { onResume(entry) },
                        onDelete = { onDelete(entry.cid) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearConfirmDialog(
            title = "清空全部历史记录？",
            message = "将删除 ${entries.size} 条观看记录及其播放进度，删除后无法恢复。",
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
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

/**
 * 单条记录：海报（底部叠进度条）+ 左栏文字（剧名 / 第 N 集 / 已看时间）+ 右侧删除。
 * 删除与剧名同一行而不是独占一行：它和文字共用一条基线，卡片底部才收得干净；
 * 之前吊在文字块下方右侧、还带一块灰底，四个并排就成了打断海报节奏的悬浮方块。
 */
@Composable
private fun HistoryCard(
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
            }
            Spacer(Modifier.width(12.dp))
            DeleteButton(onDelete)
        }
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
private fun DeleteButton(onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val corner = 8.dp
    Card(
        onClick = onDelete,
        modifier = modifier,
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        // 常态完全透明、只有浅灰字：遥控器指到哪才亮到哪，平时这块退进背景，
        // 不跟海报抢视线。焦点沿用全站同一套语言（白底 + 深字），
        // 删除的语义交给文案本身，不靠红色喊。
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
        // 宽度跟着文字走（不再固定 92dp 的方块），但保留 56x40dp 的点击/焦点面积：
        // 遥控器要在 3 米外把焦点停在这上面，太小会一直滑过去
        Box(
            Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 40.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 必须显式跟随内容色：TvBody 把颜色烤死在 style.color 上，
            // 不传 color 的话聚焦白底上仍是浅色字，等于看不见
            Text(
                "删除",
                style = TvBody.copy(fontSize = 14.sp),
                color = androidx.tv.material3.LocalContentColor.current,
            )
        }
    }
}
