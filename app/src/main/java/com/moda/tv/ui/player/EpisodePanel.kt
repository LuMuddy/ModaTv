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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.tv.model.Episode
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

private fun cleanEpisodeLabel(label: String, vid: String): String {
    if (label.isBlank() || label == vid) return ""
    return label.replace(EpisodeNumbering, "").trim().trimStart('：', ':', ' ')
}

/**
 * 选集浮层：横向懒加载的集号条。
 * 976 集的剧也只 compose 可视窗口内的卡片，滚动时才逐个创建；
 * 用单行 LazyRow 而非网格，遥控器上下键就是天然的一维导航，不会有一堆空槽
 * （左右键在播放页被进度条占用，面板内一律用上下键移动焦点）。
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
    // 打开面板时把焦点项滚进视野，用户不用先盲摸
    LaunchedEffect(focusedIndex) {
        listState.animateScrollToItem(focusedIndex.coerceIn(0, episodes.lastIndex))
    }
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
            "选集 · 共 " + episodes.size + " 集",
            style = TvCaption,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            state = listState,
            modifier = Modifier.height(EpisodeRowHeight),
            contentPadding = PaddingValues(horizontal = 18.dp),
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
                            "第 " + (episode.index + 1) + " 集",
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
