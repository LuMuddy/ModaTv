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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.model.Episode
import com.moda.tv.model.SeriesDetail
import com.moda.tv.model.Tier
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.listBottomSafe
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.Badge
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.EpisodeChip
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.RemoteImage
import com.moda.tv.ui.components.SectionTitle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import kotlin.math.abs
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
 * 「居中」时最多补几次滚动。
 *
 * 为什么不是一次算准：scrollToItem 的 offset 要的是「这一格左边缘距视口左边缘多少像素」，
 * 而每格宽度不等（有分集描述的卡固定 190dp，没有的贴合文字），得先跳过去、量到实际宽度才谈得上算偏移。
 *
 * 为什么要有上限：靠近两端时根本滚不过去（第 1 格左边没有空间，最后一格右边也没有），
 * scrollBy 会被列表边界夹住，差值永远不为 0，没有次数的循环会在那里空转。
 */
private const val CENTER_SCROLL_PASSES = 3

/** 「看完」角标的前景色：深底上的柔绿，与 VIP 的金、预告的靛蓝互不混淆 */
private val CompletedGreen = Color(0xFF7BD88F)

/**
 * 断点时间格式化：不足 1 小时 mm:ss（补零），≥1 小时 h:mm:ss（小时不补零），0 返回空串。
 *
 * 为什么 0 返回空串而不是 "00:00"：调用方用空串表示「这集没有断点，别显示时间」，
 * 显示 "00:00" 会被读成「看到了开头」，和「没看过」是两件事。
 * 为什么不补小时位（01:02:05 而不是 1:02:05）：小时位永远是个位数的影视内容里，
 * 前置 0 会白占一个字符宽度，让按钮文案宽度在 mm:ss / h:mm:ss 之间来回抖。
 *
 * 边界用例：
 *   0        → ""（不显示时间）
 *   754000   → "12:34"
 *   67000    → "01:07"
 *   3725000  → "1:02:05"
 *   3600000  → "1:00:00"
 */
private fun formatProgress(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    fun pad(v: Long) = if (v < 10L) "0$v" else "$v"
    return if (hours > 0L) "$hours:${pad(minutes)}:${pad(seconds)}" else "${pad(minutes)}:${pad(seconds)}"
}

/**
 * 把轨道里的第 [index] 格滚到视口正中间。
 *
 * 为什么不用 scrollToItem(index, offset) 一步到位：offset 要的是
 * 「这一格左边缘距视口左边缘多少像素」，而每格宽度不等（有分集描述的卡固定 190dp，
 * 没有的贴合文字），必须先跳过去、量到实际宽度才谈得上算偏移。
 * 所以是「跳 → 等一帧拿到测量结果 → 按差值补滚动」，最多补 [CENTER_SCROLL_PASSES] 次。
 *
 * 靠近两端时居中做不到（第 1 格左边没有空间、最后一格右边也没有），
 * scrollBy 会被列表边界夹住，此时它会退化成「贴边」——这是预期行为，不是 bug。
 */
private suspend fun LazyListState.scrollToCentered(index: Int) {
    scrollToItem(index)
    repeat(CENTER_SCROLL_PASSES) {
        withFrameNanos { }
        val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val delta = (info.offset + info.size / 2f) - layoutInfo.viewportSize.width / 2f
        if (abs(delta) < 1f) return
        scrollBy(delta)
    }
}

/**
 * 剧集详情：海报 + 标题 + 主按钮（继续播放/播放）+ 简介 + 收藏 + 单行选集轨道。
 *
 * 集数形态按「这部剧返回了几集」分两种，不按屏幕宽度：
 *   - 只有 1 集 → 整段「选集」都不出现，页面只剩上面的主按钮（播放 / 继续播放 mm:ss）；
 *   - 多集 → 页头下方一行横向轨道，方向键左右一路往右延伸；
 *     有观看记录时把记录的那一格横向居中，每次进页面都重新居中（见 scrollToCentered）。
 *
 * 纵轴只有一个滚动容器（这层 Column），横轴只交给轨道自己的 LazyRow：
 * 两层都吃上下键的话，在遥控器上表现为上下键突然滚了整页、或焦点卡在边界不动。
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
    /** 历史落点：这部剧看过的话，选中态落在那一集、选集轨道滚到它居中（不抢焦点） */
    resume: DetailResume? = null,
    /**
     * 这部剧已看完的集号集合，用于选集卡右上角的「看完」角标。
     * 带默认值是为了不强迫调用点同步：接线只需要 AppRoot 里补一行
     * `completedIndexes = vm.detailCompletedIndexes.collectAsStateWithLifecycle().value`。
     */
    completedIndexes: Set<Int> = emptySet(),
) {
    val ctaFocus = remember { FocusRequester() }
    // 选集轨道的滚动状态。每次进详情页都是一份新的 composition，
    // 所以「居中」每次加载都会重来一次，不会记住上一次的滚动位置
    val railState = rememberLazyListState()

    val episodes = detail?.episodes.orEmpty()

    // 只有一集（预告/花絮也算集数，只要返回 2 条以上就算多集）时不画选集：
    // 一集本来就没有可选的东西，「第 1 集」孤零零挂在下面只会让人以为别的集没加载出来
    val showRail = episodes.size > 1

    // 历史落点：历史里看过的那一集，-1 表示这部剧没记录。
    // 优先按 vid 匹配——集数表重排/换档后 episodeIndex 可能漂移，
    // vid 才是「那一集」的稳定身份，下标只在 vid 找不到时兜底。
    // cid 必须对得上：切剧瞬间 StateFlow 还留着上一部剧的值，不丢掉它会拿 A 的集号去选 B。
    val watched = resume?.takeIf { it.loaded && it.cid == detail?.cid }

    /** 按 vid 命中的那一集在列表里的下标；-1 表示没命中 */
    val watchedByVid = watched?.vid?.takeIf { it.isNotBlank() }
        ?.let { vid -> episodes.indexOfFirst { it.vid == vid } }
        ?: -1

    // 选中态/首焦点用的落点：vid 优先，其次才退回历史里存的下标
    val resumeIndex = if (watchedByVid >= 0) {
        watchedByVid
    } else {
        watched?.episodeIndex?.takeIf { it in episodes.indices } ?: -1
    }

    // 「继续播放 mm:ss」的判定必须**只认 vid 命中**，不能退回下标：
    // positionMs 与 vid/episodeIndex 出自同一条历史记录，只对那一集有意义。
    // 按下标匹配的话，集数表重排后 A 集的断点会被画到 B 集上，
    // 用户点下去 resumePosition 又不会真的跳过去——表现就是「每集都显示同一串时间，点了却不续播」。
    val continueIndex = watchedByVid
    val continueEpisode = episodes.getOrNull(continueIndex)
    // 该集已经看完时不显示断点：看完的集从"还剩多少"这个语义上已经没有续播意义，
    // 而且历史里那条 positionMs 往往是贴着片尾的，显示出来反而误导。
    val continueMs = if (continueEpisode != null && continueIndex !in completedIndexes) {
        watched?.positionMs ?: 0L
    } else {
        0L
    }
    val continueProgress = formatProgress(continueMs)
    val hasResume = continueEpisode != null && continueProgress.isNotEmpty()

    // 初值直接取历史落点：历史读的是本地盘、详情走的是网络，通常历史先到，
    // 这里就能一次落位，不必先画一帧「第 1 集选中」再跳过去。
    val initialIndex = resumeIndex.coerceAtLeast(0)
    // 落位只做一次：didPlaceByResume 之后历史再怎么变（比如看完一集回来）轨道都只跟随，不再跳
    var didPlaceByResume by remember(detail?.cid) { mutableStateOf(false) }
    // 用户自己操作过（点了某一集/主按钮），任何迟到的自动落位都必须让路
    var userTouched by remember(detail?.cid) { mutableStateOf(false) }
    var currentIndex by remember(detail?.cid) { mutableStateOf(initialIndex) }

    val current = episodes.getOrNull(currentIndex)

    // 首焦点**永远**是主按钮（播放 / 继续播放 mm:ss），单集多集都一样。
    //
    // 早先的版本把焦点交给选集里"看过的那一格"，真机上表现为一进详情页整页往上顶一下：
    // 轨道在页头下方，Compose 为了让聚焦项可见会去滚外层的纵向容器。主按钮在标题正下方、
    // 首屏就在眼前，落在那里页面一动不动，而且它本来就是这页唯一的主操作。
    //
    // 因此历史落点与焦点彻底解耦：历史只决定「哪一格显示为选中」+「轨道滚到哪一格居中」。
    // 等布局落地再 requestFocus，否则目标还没 compose 出来会抛且焦点永远不落。
    LaunchedEffect(detail?.cid, episodes.size) {
        if (episodes.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { ctaFocus.requestFocus() }
    }

    // 历史落点：只滚轨道，不碰焦点。
    // 历史还在读盘时直接返回、**不猜第 1 集**；等 loaded 变 true 让本 effect 重跑一次再落位。
    // 每次进详情页都是新 composition，didPlaceByResume 随之重置，所以这是"每次加载都重新居中"。
    LaunchedEffect(detail?.cid, episodes.size, resume?.loaded, resumeIndex) {
        if (!showRail || userTouched) return@LaunchedEffect
        if (resume?.loaded != true) return@LaunchedEffect
        if (didPlaceByResume) return@LaunchedEffect
        didPlaceByResume = true
        val target = resumeIndex.coerceIn(0, episodes.lastIndex)
        currentIndex = target
        // 目标格往往在轨道之外（第 916 集），还没 compose 出来，靠 scrollToItem 直接跳，
        // 再把这一格拉到视口正中；没有记录时 target 是 0，贴左边即可
        railState.scrollToCentered(target)
    }

    /** 主按钮/集数点击的统一入口：onPlay 只认 Episode，断点由 VM 内部 resumePosition 带出 */
    fun playEpisode(ep: Episode) {
        userTouched = true
        currentIndex = episodes.indexOfFirst { it.vid == ep.vid }.takeIf { it >= 0 } ?: currentIndex
        onPlay(ep)
    }

    // 规则 3：容器与屏幕底边之间留安全边，否则光标停在中间行时聚焦/点击外扩会被切平
    Box(Modifier.fillMaxSize().background(ModaColors.Bg).listBottomSafe()) {
        when {
            // 拿不到详情时也要留得下出口：有 onRetry 就重试，没有就返回上一页
            detail == null && error != null -> ErrorState(error, onRetry = onRetry ?: onBack)
            detail == null && loading -> LoadingState("正在拉取剧集…")
            detail == null -> EmptyState("没有拿到剧集信息", onRetry = onRetry ?: onBack)
            else -> Column(
                // 整页只有一个纵向滚动容器：页头 + 单行选集轨道 + 底部提示。
                // 纵轴只有这一层，横轴交给轨道自己的 LazyRow——
                // 两层都吃上下键的话，遥控器上会突然滚掉整页，或者焦点卡在边界不回头。
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 32.dp, bottom = ListMetrics.TailPad),
            ) {
                // 左图右信息：电视上竖版海报配剧名比纯文字更像一个详情页
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ListMetrics.SidePad),
                ) {
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
                        Spacer(Modifier.height(18.dp))
                        // 主 CTA 紧跟标题：这是进详情页后最想按的一颗键，
                        // 位置必须在「标题下方第一眼」而不是滚动之后。
                        // 只有一集时它是整页唯一的播放入口——下面不会再有选集。
                        if (episodes.isNotEmpty()) {
                            PlayCtaButton(
                                text = if (hasResume) "继续播放 ${continueProgress}" else "播放",
                                modifier = Modifier.focusRequester(ctaFocus),
                                onClick = {
                                    // 有断点从断点那一集起播，没断点从第 1 集起播。
                                    // 不自己传 positionMs：断点由 VM 的 resumePosition 在
                                    // push 时查历史得出，这里塞一个快照反而会和真实历史打架。
                                    val target = episodes.getOrNull(continueIndex) ?: episodes.first()
                                    playEpisode(target)
                                },
                            )
                        }
                        if (description.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
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

                if (episodes.isEmpty()) {
                    Text(
                        "该剧没有返回任何集数",
                        style = TvBody,
                        color = ModaColors.Danger,
                        modifier = Modifier.padding(start = ListMetrics.SidePad, top = 26.dp),
                    )
                } else if (showRail) {
                    // 只有一集时整段「选集」都不画：一集本来就没有可选的东西，
                    // 轨道里孤零零躺着一个「第 1 集」只会让人以为还有别的集没加载出来。
                    Spacer(Modifier.height(26.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = ListMetrics.SidePad),
                    ) {
                        SectionTitle("选集")
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${episodes.size} 集",
                            style = TvCaption.copy(fontSize = 16.sp),
                            color = ModaColors.TextDim,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // 轨道满宽铺开（不跟页头一起吃 SidePad）：卡片要能一路滑到屏幕边缘，
                    // 侧边留白改由 contentPadding 提供，否则滑到一半的卡片会在 48dp 处被硬裁一刀。
                    LazyRow(
                        state = railState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            horizontal = ListMetrics.SidePad,
                            // 纵向 8dp 兜住聚焦放大 1.08 的溢出，否则轨道上下沿会切掉白边
                            vertical = 8.dp,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(count = episodes.size, key = { i -> "ep-${episodes[i].vid}-$i" }) { index ->
                            val ep = episodes[index]
                            val finished = index in completedIndexes
                            // 顶部 12dp 给「已看完」勾标悬挂用（勾塞进卡片内部会压住「第 N 集」那行字）
                            Box(Modifier.padding(top = 12.dp)) {
                                EpisodeChip(
                                    index = ep.index,
                                    label = ep.label,
                                    isVip = ep.isVip,
                                    isTrailer = isTrailer(ep),
                                    selected = index == currentIndex,
                                    cover = ep.cover,
                                    // 空串时 EpisodeChip 不画第二行：没有描述就不占位置
                                    summary = ep.summary,
                                    onClick = {
                                        // 用户已经自己选了：钉住落点判定，别让迟到的历史把选择顶掉
                                        userTouched = true
                                        currentIndex = index
                                        onPlay(ep)
                                    },
                                )
                                if (finished) {
                                    // 骑在卡片右上角：Box 预留的 12dp + 负 offset 让它压在边框上，
                                    // 既不吃卡片内部那行字，也不会被轨道裁掉（绘制范围仍在单元格内）。
                                    CompletedBadge(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = (-4).dp, y = (-12).dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (loading || error != null) {
                    Column(Modifier.padding(horizontal = ListMetrics.SidePad)) {
                        if (loading) {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "正在刷新…",
                                style = TvCaption.copy(fontSize = 16.sp),
                                color = ModaColors.TextDim,
                            )
                        }
                        // 已有内容时的刷新失败不该盖住整页，只在底部提示并给重试
                        if (error != null) {
                            Spacer(Modifier.height(24.dp))
                            Text(error, style = TvBody, color = ModaColors.Danger)
                            Spacer(Modifier.height(12.dp))
                            ActionButton(
                                text = "重试",
                                onClick = { onRetry?.invoke() ?: onBack() },
                                primary = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 详情页主 CTA（继续播放 xx:xx / 播放）。
 *
 * 为什么不复用 components 里的 ActionButton：那个按钮固定 20sp、没有图标位，
 * 和选集卡的 17sp 差得不够多，在 3 米外的电视上分不出「这是主操作」还是「这是普通按钮」。
 * 这里用更大的字号（22sp）+ 播放图标 + 蓝底 2dp 强调描边，视觉重量明显压过选集卡。
 *
 * 文字/图标颜色写死白色：这个按钮的普通态、聚焦态、按下态**全是蓝底**，
 * 不存在其它按钮那种"聚焦变纯白底"的反色情况，所以不需要（也不应该）
 * 跟随 LocalContentColor——何况 material3 与 tv-material3 各有一个同名
 * LocalContentColor，读错了会拿到默认黑，蓝底黑字直接看不见。
 */
@Composable
private fun PlayCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = 12.dp
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = ButtonDefaults.colors(
            containerColor = ModaColors.Accent,
            contentColor = Color.White,
            focusedContainerColor = ModaColors.AccentSoft,
            focusedContentColor = Color.White,
            pressedContainerColor = ModaColors.AccentSoft,
            pressedContentColor = Color.White,
        ),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.06f),
        border = ButtonDefaults.border(
            // 未聚焦也留一圈亮蓝：主 CTA 在静态画面上也要比选集卡显眼
            border = Border(border = BorderStroke(2.dp, ModaColors.AccentSoft), shape = RoundedCornerShape(corner)),
            focusedBorder = Border(border = BorderStroke(3.dp, ModaColors.Focus), shape = RoundedCornerShape(corner)),
        ),
        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = Color.White,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = TvBody.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * 「✅ 已看完」角标：一颗绿勾图标，骑在集数卡右上角。
 *
 * 为什么从「✓ 看完」三个字改成图标：选集是一条横向轨道，卡片本身只有 60 来 dp 高，
 * 文字角标要么压住「第 N 集」那行字、要么把卡片撑高；一颗 20dp 的勾在 3 米外一眼就认得出，
 * 而「看完」这两个字对这个状态是冗余的——勾本身已经说明完了。
 *
 * 不是可点击/可聚焦元素（Box + Icon 没有 clickable/focusable），
 * 所以它可以放心写死颜色——它不会进到"聚焦反白底"的配色规则里。
 * 底色用 80% 不透明的近黑而不是纯色：压在浅色海报或蓝底选中卡上都还能读，
 * 同时不会把卡片本身的底色完全盖掉。
 */
@Composable
private fun CompletedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC0B0B0F), CircleShape)
            .padding(2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            // 无障碍：图标没有文字，状态必须进描述
            contentDescription = "已看完",
            tint = CompletedGreen,
            modifier = Modifier.size(20.dp),
        )
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
 * Lazy 的集数网格与海报不会被重组重建，点一下不会闪屏。
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
