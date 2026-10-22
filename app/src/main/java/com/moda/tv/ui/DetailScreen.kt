package com.moda.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.model.Episode
import com.moda.tv.model.SeriesDetail
import com.moda.tv.model.Tier
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.Badge
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.EpisodeChip
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.components.SectionTitle
import com.moda.tv.ui.components.TierChip
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle

/** 与 app.js 的 EXTRA 一致：这些分类算「非正片」，在集数条上打预告角标 */
private val TRAILER_KEYS = listOf(
    "预告片", "预告", "花絮", "彩蛋", "片头", "片尾", "宣传片", "片花",
    "独家", "幕后", "采访", "速看", "前瞻",
)

private fun isTrailer(ep: Episode): Boolean = TRAILER_KEYS.any { ep.label.contains(it) }

/**
 * 剧集详情：标题 + 简介 + 集数横条 + 播放按钮 + 清晰度。
 * 整页用 verticalScroll 而不是 LazyColumn，因为集数条与清晰度条各自是横向 LazyRow，
 * 嵌在两个方向上都可滚动的 lazy 容器里焦点寻路会变得不可预测。
 */
@Composable
fun DetailScreen(
    detail: SeriesDetail?,
    loading: Boolean,
    error: String?,
    onPlay: (Episode) -> Unit,
    onTierSelect: (Tier) -> Unit,
    selectedTier: Tier?,
    onBack: () -> Unit,
    description: String = "",
    onRetry: (() -> Unit)? = null,
    // 尾部可选参数：既有调用点（含测试/预览）不必改动
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
) {
    val playFocus = remember { FocusRequester() }
    var focusDone by remember { mutableStateOf(false) }
    var currentIndex by remember(detail?.cid) { mutableStateOf(0) }

    val episodes = detail?.episodes.orEmpty()
    val current = episodes.getOrNull(currentIndex)
    // 上游（Parser/Repo）已经过滤过，这里再兜一次：灰档位永远不该出现在用户眼前。
    // 空列表交给下面的「接口没有返回清晰度…」文案兜住，不会白屏。
    val tiers = detail?.tiers.orEmpty().filter { it.available && it.fn.isNotBlank() }

    // 同上：等布局落地再抢焦点，否则 requestFocus 会抛且焦点永远不落
    LaunchedEffect(detail?.episodes?.size) {
        if (!episodes.isEmpty() && !focusDone) {
            focusDone = true
            withFrameNanos { }
            withFrameNanos { }
            runCatching { playFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        when {
            // 拿不到详情时也要留得下出口：有 onRetry 就重试，没有就返回上一页
            detail == null && error != null -> ErrorState(error, onRetry = onRetry ?: onBack)
            detail == null && loading -> LoadingState("正在拉取剧集…")
            detail == null -> EmptyState("没有拿到剧集信息", onRetry = onRetry ?: onBack)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 56.dp),
            ) {
                // 左图右信息：电视上竖版海报配剧名比纯文字更像一个详情页
                Row(Modifier.fillMaxWidth()) {
                    RemoteImage(
                        url = detail.cover,
                        title = detail.title,
                        modifier = Modifier
                            .width(200.dp)
                            .aspectRatio(3f / 4f),
                        corner = 14.dp,
                    )
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = detail.title.ifBlank { "未命名剧集" },
                                style = TvTitle,
                                color = ModaColors.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (current?.isVip == true) {
                                Spacer(Modifier.width(12.dp))
                                Badge("VIP", color = Color(0xFFE0A32E))
                            }
                        }
                        if (description.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = description,
                                style = TvBody.copy(fontSize = 18.sp),
                                color = ModaColors.TextDim,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        // 收藏是这个数据唯一的产生入口：没有它收藏页永远是空的。
                        // 这一行只放收藏：返回交给遥控器返回键（AppRoot 的 BackHandler），
                        // 屏幕上再摆一个「返回」只是重复入口，还占掉了海报右侧最顺手的一格。
                        FavoriteIconButton(isFavorite = isFavorite, onClick = onToggleFavorite)
                    }
                }

                Spacer(Modifier.height(26.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("选集")
                    Spacer(Modifier.width(12.dp))
                    Text("${episodes.size} 集", style = TvCaption.copy(fontSize = 16.sp), color = ModaColors.TextDim)
                }
                Spacer(Modifier.height(12.dp))
                if (episodes.isEmpty()) {
                    Text("该剧没有返回任何集数", style = TvBody, color = ModaColors.Danger)
                } else {
                    // contentPadding 必须大于「聚焦放大溢出量」：LazyRow 会裁剪边界外的绘制，
                    // 卡最宽 190dp × 1.08 放大 = 每侧溢出约 7.6dp，再加 3dp 描边，留 20dp 才不会被切
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(count = episodes.size, key = { i -> "ep-${episodes[i].vid}-$i" }) { index ->
                            val ep = episodes[index]
                            EpisodeChip(
                                index = ep.index,
                                label = ep.label,
                                isVip = ep.isVip,
                                isTrailer = isTrailer(ep),
                                selected = index == currentIndex,
                                // 播放按钮已移除，首焦点落到第一集，进页即可确认播放
                                cover = ep.cover,
                                // 空串时 EpisodeChip 不画第二行：没有描述就不占位置
                                summary = ep.summary,
                                modifier = if (index == 0) Modifier.focusRequester(playFocus) else Modifier,
                                onClick = {
                                    currentIndex = index
                                    onPlay(ep)
                                },
                            )
                        }
                    }
                }

                if (loading) {
                    Spacer(Modifier.height(24.dp))
                    Text("正在刷新…", style = TvCaption.copy(fontSize = 16.sp), color = ModaColors.TextDim)
                }
                // 已有内容时的刷新失败不该盖住整页，只在底部提示并给重试
                if (error != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(error, style = TvBody, color = ModaColors.Danger)
                    Spacer(Modifier.height(12.dp))
                    ActionButton(text = "重试", onClick = { onRetry?.invoke() ?: onBack() }, primary = true)
                }
            }
        }
    }
}

/**
 * 已收藏时爱心的颜色。
 * 选黄不选强调蓝：蓝色在本应用里已经等于「当前选中 / 可操作」（选集卡、清晰度档），
 * 用它表达收藏会和"选中态"混淆；黄色在这套深色底上是唯一的高辨识暖色，
 * 且与白色图标（未收藏）形成空/实 + 白/黄的双重区分，色弱用户也能靠形状分辨。
 * 对比度（WCAG 2.1 相对亮度计算）：#FFC53D 压在 ModaColors.Surface #15161C 上 = 11.44:1，
 * 压在 ModaColors.Bg #0B0B0F 上 = 12.45:1，都远高于正文要求的 4.5:1。
 */
private val FavoriteYellow = Color(0xFFFFC53D)

/**
 * 详情页的收藏开关。刻意不复用工具栏的 IconActionButton：
 * 那个组件的图标色由 contentColor() 统一接管，没有"随状态变色"的入口，
 * 为它加一个状态色参数会波及首页工具栏的四个图标。这里就地写一个 56dp 的图标按钮，
 * 尺寸/圆角/聚焦白描边与 IconActionButton 完全一致，视觉上仍属同一套。
 *
 * 状态变化只换 imageVector 与 tint：没有 if/else 包裹整块布局，
 * Lazy 的集数条与海报不会被重组重建，点一下不会闪屏。
 */
@Composable
private fun FavoriteIconButton(isFavorite: Boolean, onClick: () -> Unit) {
    val corner = 12.dp
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = IconButtonDefaults.colors(
            containerColor = ModaColors.SurfaceHigh,
            contentColor = if (isFavorite) FavoriteYellow else ModaColors.Text,
            focusedContainerColor = ModaColors.Focus,
            // 聚焦时底变白，黄心在白底上没有对比度，必须换成近黑色才看得见；
            // 未收藏的白心同样要反转，否则白底白心等于图标消失
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = ModaColors.SurfaceHigh,
            pressedContentColor = if (isFavorite) FavoriteYellow else ModaColors.Text,
        ),
        scale = IconButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
        border = IconButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(3.dp, ModaColors.Focus),
                shape = RoundedCornerShape(corner),
            ),
        ),
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            // 无障碍：状态直接进描述，读屏用户不必靠颜色猜
            contentDescription = if (isFavorite) "已收藏，点击取消收藏" else "收藏",
            modifier = Modifier.size(30.dp),
            // 跟随 IconButton 的 contentColor（含聚焦反色的白底近黑心），不硬编码颜色
            tint = LocalContentColor.current,
        )
    }
}
