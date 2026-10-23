package com.moda.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.tv.R
import com.moda.tv.model.Episode
import com.moda.tv.ui.components.TvRowFocusScroll
import com.moda.tv.ui.components.railScrollRoom
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption

/** 卡片宽度：90~110dp 放不下 16:9 缩略图，168dp 在 1920 宽下可见约 8 张 */
private val EpisodeChipWidth = 168.dp
/**
 * 行高必须能装下「16:9 缩略图 + 两行文字 + 内边距」，否则底部文字被裁。
 * 实算：168dp 宽的图高 94.5dp + 集号 27dp + 副标题 22dp + 上下内边距 12dp ≈ 155.5dp，
 * 再留聚焦放大的余量，取 178dp。之前写 152dp 差了约 4dp，正好把副标题切掉。
 */
private val EpisodeRowHeight = 178.dp
private val EpisodeThumbRatio = 16f / 9f

/**
 * 分集副标题清洗：去掉「第N季」「第N集/期/话/回」这类自带编号，
 * 只保留真正的内容标题（如「实操加试 手术…」）。
 * 剥完为空说明这条 label 只有编号，返回空串让调用方整行不显示。
 */
private val EpisodeNumbering = Regex(
    "(第\\s*\\d+\\s*[季期集话回]\\s*[：:]?\\s*)+"
)

/**
 * 上游 seriesPartTitle 的集号槽位可能是空的（实测「空枪」返回「第1季 第集」），
 * 旧正则要求集号必须有数字，「第集」这种残渣匹配不上就漏到了屏上。
 * 编号词后跟空集号同样算编号残渣，一并剥掉。
 */
private val EpisodeEmptyNumbering = Regex(
    "(第\\s*\\d+\\s*季\\s*第\\s*[集期话回]\\s*[：:]?\\s*)+"
)

private fun cleanEpisodeLabel(label: String, vid: String): String {
    if (label.isBlank() || label == vid) return ""
    return label
        .replace(EpisodeEmptyNumbering, "")
        .replace(EpisodeNumbering, "")
        .trim()
        .trimStart('：', ':', ' ')
}

/**
 * 选集浮层：横向懒加载的集号条。
 * 976 集的剧也只 compose 可视窗口内的卡片，滚动时才逐个创建；
 * 用单行 LazyRow 而非网格，遥控器左右键就是天然的一维导航，不会有一堆空槽。
 * 左右键在这里只移动焦点，确认键才真正切集。
 *
 * 卡片结构：上方 16:9 缩略图 + 下方集号/副标题。
 * 文字压在缩略图下面而不是叠在图上——浅色画面上叠字几乎看不清。
 */
@Composable
fun EpisodePanel(
    episodes: List<Episode>,
    currentIndex: Int,
    focusedIndex: Int,
) {
    if (episodes.isEmpty()) return
    val listState = rememberLazyListState()
    /**
     * 打开面板时的那一次落位：把当前集直接摆进视口，用户不用先盲摸。
     *
     * 这是**一次性**的，key 是集数表而不是焦点下标。之前是按焦点下标触发的动画滚动：
     * 连按左右时每一下按键都会取消上一段动画再起一段新的，表现为整排卡片追着按键跳、
     * 本来就看得见的卡也被猛拖一下。左右跟焦现在归 TvRowFocusScroll（scrollBy 立即滚，见 R2）。
     */
    val initialIndex = remember(episodes) { focusedIndex.coerceIn(0, episodes.lastIndex) }
    LaunchedEffect(episodes) {
        listState.scrollToItem(initialIndex)
    }
    // 与首页/详情页的选集轨道同一套跟焦：按整格滚、左右留 16dp 呼吸位，
    // 连按时每次按键当场把目标格摆好，不做动画
    TvRowFocusScroll(state = listState, focusedIndex = focusedIndex)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .widthIn(max = 1100.dp)
            .clip(shape)
            .background(Color(0xE6111218))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(vertical = 12.dp)
    ) {
        Text(
            stringResource(R.string.player_episode_panel_title, episodes.size),
            style = TvCaption,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            state = listState,
            // 18dp 与标题行同源；写在视口之外，左右推到端点再回来才不会少这一截
            modifier = Modifier.height(EpisodeRowHeight).railScrollRoom(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(episodes) { index, episode ->
                // 注意：index 是 0 基内部下标，当前集比对必须用它，
                // 展示用的集号才 +1，否则第 1 集会显示成「第 0 集」
                val isCurrent = episode.index == currentIndex
                val focused = index == focusedIndex
                val cardShape = RoundedCornerShape(10.dp)
                Column(
                    Modifier
                        .width(EpisodeChipWidth)
                        .clip(cardShape)
                        .background(if (isCurrent) Color(0xFF2A5FD6) else Color(0x33000000))
                        .border(
                            if (focused) 3.dp else 1.dp,
                            if (focused) Color(0xFFFFFFFF) else Color(0x33FFFFFF),
                            cardShape,
                        )
                ) {
                    RemoteImage(
                        url = episode.cover,
                        title = episode.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(EpisodeThumbRatio),
                        // 缩略图与卡片同圆角，只圆上半边
                        corner = 0.dp,
                    )
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            stringResource(R.string.player_episode_number, episode.index + 1),
                            style = TvBody,
                            color = Color(0xFFF2F3F7),
                            maxLines = 1,
                        )
                        // 副标题剥掉自带的集号前缀。
                        // 接口给的 label 常是「第1季 第01集」这种，和上面的「第 N 集」完全重复，
                        // 两个集号并排既占宽又让人分不清哪个是真的。剥完只剩有信息量的部分，
                        // 剥空了就整行不显示（不再画一条空行占高度）。
                        val sub = cleanEpisodeLabel(episode.label, episode.vid)
                        if (sub.isNotBlank()) {
                            Text(
                                sub,
                                style = TvCaption,
                                color = Color(0xFFF2F3F7).copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
