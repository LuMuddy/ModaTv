package com.moda.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.LocalContentColor
import com.moda.tv.R
import com.moda.tv.model.HomeFeed
import com.moda.tv.model.HomeTab
import com.moda.tv.model.LibraryEntry
import com.moda.tv.model.VideoItem
import com.moda.tv.ui.components.ContinueWatchingCard
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.IconActionButton
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.listBottomSafe
import com.moda.tv.ui.components.railScrollRoom
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.SectionTitle
import com.moda.tv.ui.components.TvGridFocusScroll
import com.moda.tv.ui.components.VideoCard
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 首屏预取高清封面的卡片数。
 * 只给推荐行最前面这几张：它们是最可能被先看到的卡，
 * 同时把首屏的 eps 请求数压到个位数（见 RemoteImage 里的取舍说明）。
 */
private const val HI_RES_PREFETCH_COUNT = 6

/**
 * 首页卡片宽度不再由自己定：轮播改成 5 列网格之后，列宽由 LazyVerticalGrid 按屏幕分配
 * （960dp 宽、48dp 侧边、16dp 间距 → 每列约 160dp），卡片一律 width = null 撑满列宽。
 * 之前那个 110dp 来自「轮播 + 一行横向卡片」的旧结构，留着只会误导下一处改动。
 */

/** 分类 tab 每行的海报数（需求 11：每行 5 个） */
private const val GRID_COLUMNS = 5

/**
 * 首页「继续观看」行最多显示多少条。
 *
 * 5 条正好铺满一行（[GRID_COLUMNS]），首页因此只为它留一行高度：这一行是「顺手接着看」，
 * 不是历史页的复刻——完整记录去历史页看，那边仍然显示全部（最多 HistoryStore.MAX 条）。
 * 历史流本身已按 updatedAt 倒序（见 LibraryOps.sortDesc），取前 5 条就是「最近看的 5 条」，
 * 首页不需要再排一次序。存储上限也一并保持 60 不动：只改展示，不改留多久。
 */
private const val CONTINUE_WATCHING_LIMIT = 5

/**
 * 首页：顶部工具栏 + tab 栏 + 内容区。
 *
 * 为什么是「固定头部 + 只滚内容」而不是像旧版那样整页一个 LazyColumn：
 * 分类 tab 的内容必须是 LazyVerticalGrid（每行 5 个海报），它和 LazyColumn 同方向，
 * 嵌套滚动会直接抛 "infinity maximum height constraints"；而 tab 栏在遥控器上必须随时按得到。
 * 于是头部固定、只有内容区滚动——这也是电视端最省事、焦点最稳的结构。
 *
 * 焦点图（每一条都显式指定，不交给几何搜索；★ = 首焦点落点）：
 *
 *   [刷新][搜索][历史][收藏][已看完][设置]            工具栏（只显示，不抢首焦点）
 *        │ ↓ 几何：tab 栏就在正下方
 *   [推荐][电视剧][电影][综艺][动漫]                   tab 栏：←→ 扫过即切 tab
 *        │ ↓ down = 内容区首卡（内容区根节点的 enter 也指向它）
 *   推荐行首卡 ★                                     ↑ up 几何回 tab 栏
 *   继续观看行（有历史才出现）        ↑ up 几何回上一行
 *
 * 分类 tab：LazyVerticalGrid(5 列)，★ = 第 0 张，第一行按 ↑ 回 tab 栏。
 *
 * 数据只有一条通路：feeds 里当前 tab 的 items；没有第二份数据可以兜底，没数据就是加载/空态。
 * 时间也只有一条通路：[pageFetchedAt]——整页最近一次成功刷新的时刻，和 30 分钟门禁判的是同一个值。
 * 不再拿当前 tab 自己那条 HomeFeed 的 fetchedAt 当工具栏时间：那样切一个 tab 时间就跳一次，
 * 用户读不出「整页有多新」，而 30 分钟门禁判的偏偏是整页。
 *
 * 工具栏时间读作两截：「上次刷新 今天 08:32」是数据有多新，「现在 14:05」是此刻几点。
 * 两者必须并排：只给一个裸时刻（旧版的 yyyy-MM-dd HH:mm）读者分不清它是「刷新的时刻」
 * 还是「现在的时刻」，而且日期天天一样、看着像卡住的电子钟。现在两截并列，
 * 用户一眼就能算出「这份内容已经放了 5 个多小时」，也就知道该不该按刷新。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlayer: (String, String, String, Int) -> Unit,
    // 尾部可选参数：后续加收藏/设置页时直接追加实现，不必再动既有调用点
    onOpenHistory: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // ---- P2-A：tab 化的数据入口，默认值保证未接线时行为不退步 ----
    /** 当前 tab。默认 Recommend 与 vm.homeTab 的初值一致（需求 12：冷启动就是 tab 0） */
    tab: HomeTab = HomeTab.Recommend,
    feeds: Map<HomeTab, HomeFeed> = emptyMap(),
    /** 整页最近一次成功刷新的时刻，工具栏显示的就是它；0L 表示从未刷成功过 */
    pageFetchedAt: Long = 0L,
    onSelectTab: (HomeTab) -> Unit = {},
    /**
     * 刷新按钮：**不判缓存时间、无条件整页重拉**，与错误态重试不是一回事——
     * onRetry 是「这一屏失败了再试一次」，onRefresh 是用户明知有旧内容仍要最新的那份。
     * 默认值复用 onRetry 只是未接线时的降级（至少不会按下去空转），接线后必须传 vm::refreshHome。
     */
    onRefresh: () -> Unit = onRetry,
    /**
     * 整页刷新是否正在进行。只用来让刷新图标自转：一轮要 3~4 秒，
     * 期间内容与时间戳都可能一动不动，没有这个反馈用户会以为按钮坏了。
     */
    refreshing: Boolean = false,
    /**
     * 最近一次整页刷新的结果。非 null 且 tick 变化时弹一条 2.5 秒的提示。
     *
     * 为什么不靠「内容变了没有」让用户自己判断：整页刷新成功时，
     * 这一屏的内容很可能和刷新前一模一样（推荐位十分钟才换一次），
     * 用户按完看到的还是同一屏——不弹一句「刷新完成」，他就会以为按钮又坏了。
     */
    refreshNotice: HomeRefreshNotice? = null,
    history: List<LibraryEntry> = emptyList(),
    /**
     * 点「继续观看」直接续播（需求 10：不经过详情页）。
     * 默认实现按历史里记的集号直接进播放器——这正是 vm.resumeFromHistory 的落点，
     * 只少了它内部的进度恢复，接线后换成 vm::resumeFromHistory 即完整。
     */
    onResumeHistory: (LibraryEntry) -> Unit = { entry ->
        onOpenPlayer(entry.cid, entry.vid, entry.title, entry.episodeIndex)
    },
    onOpenCompleted: () -> Unit = {},
) {
    /**
     * 直接用 feeds，没有任何摊平兜底。
     *
     * 旧版这里有一层「feeds 为空就把四行频道的列表摊成推荐 tab」的过渡兜底，
     * 它是接线之前的权宜之计，造成的问题比解决的还多：那是一锅
     * 「电视剧/电影/综艺/动漫」混在一起的拼接，用户一眼就看出「这个推荐不对」，
     * 因为它根本不是首页推荐位。
     *
     * 现在 feeds 是唯一数据源；没数据就走加载态，宁可转一会儿也不给错内容。
     */
    val feed = feeds[tab]
    val items = feed?.items.orEmpty()
    val isRecommend = tab == HomeTab.Recommend

    // 推荐 tab 不在 UI 层再截断：截断的职责只在 Repo.recommendFeed（RECOMMEND_SIZE）。
    // UI 再截一次就是同一个数字有两处口径，改一处漏一处，最后没人说清「该显示几条」。
    val recommendItems = if (isRecommend) items else emptyList()
    val prefetchCids = recommendItems.take(HI_RES_PREFETCH_COUNT).map { it.cid }.toSet()

    // 推荐与分类各自一个首卡 requester：首卡都是网格第 0 张。
    // 去掉轮播后不再需要 heroFocus 那个额外落点——轮播是首页焦点最不稳的来源
    // （异步到达、宽度随数据变、单条时还没有可聚焦节点）。
    val firstRecommendFocus = remember { FocusRequester() }
    val firstGridFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    // 空态 / 错误态里唯一的可点项是「重新加载」。不主动请求的话焦点会留在 tab 栏，
    // 用户看着屏幕中央那个蓝色按钮按确认，什么也不会发生。
    val emptyRetryFocus = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }
    // 内容区是否已经拿到过焦点。
    //
    // 「用户自己动了 tab」必须以此为前提：冷启动时焦点会先落在 tab 栏上（数据还没到，
    // 内容区一个可聚焦节点都没有），那一刻的 onFocusChanged 和「用户主动按到 tab 栏」
    // 长得一模一样。旧实现把它当成用户接管、直接置位 firstFocusDone，重试循环于是退出——
    // 数据到了之后焦点永远留在 tab 栏，第一张卡没有白框，看起来就是「首页没有选中项」。
    var contentFocusedOnce by remember { mutableStateOf(false) }
    val onContentFocused = {
        contentFocusedOnce = true
        firstFocusDone = true
    }

    // 内容区第一张卡：推荐与分类都是各自网格的第 0 张
    val entryFocus: FocusRequester = if (isRecommend) firstRecommendFocus else firstGridFocus

    /**
     * 首焦点必须落在内容区第一张卡上，而不是工具栏的刷新图标。
     *
     * 踩过的坑（沿用旧实现的结论）：用 LaunchedEffect(布尔) 抢焦点时 key 一变协程就被取消重启，
     * 而数据是异步到的，重启发生在新布局完成之前，requestFocus 抛的异常被吞、标记又已置位
     * → 焦点永久留在工具栏。所以 key 用「数据规模」（数据真正就绪才触发），
     * 并用连续多帧重试兜住布局抖动；只有内容卡自己报告获得焦点后才算成功——
     * requestFocus() 在 FocusRequester 还没挂到节点上时只打日志、照样正常返回，靠它判断会误报成功。
     */
    LaunchedEffect(tab, items.size) {
        if (firstFocusDone || items.isEmpty()) return@LaunchedEffect
        // 300 帧 ≈ 5 秒：冷启时网络要 2 秒多才回，固定 12 帧根本覆盖不到数据到达的那一刻
        repeat(300) {
            withFrameNanos { }
            if (firstFocusDone) return@LaunchedEffect
            runCatching { entryFocus.requestFocus() }
        }
    }

    LaunchedEffect(tab, items.isEmpty(), loading, error) {
        if (items.isNotEmpty() || loading) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { emptyRetryFocus.requestFocus() }
    }

    // 工具栏右半的「现在几点」：对齐到整分再更新，不需要每秒重组。
    // 只写一个 Long 状态，分钟没变时 Text 的入参不变，这一次重组几乎不花钱。
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // 60_000 - (now % 60_000) 就是「离下一个整分还有多少毫秒」：
            // 于是每一跳都落在 :00 秒，而不是从启动那一刻起错位着跳
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            nowMs = System.currentTimeMillis()
        }
    }

    // 提示的生命周期归 UI：ViewModel 只负责「发出一条结果」，几时消失由这里决定。
    // key 取 tick 而不是整条 notice：连续两次刷新失败文案完全一样，
    // 用对象内容当 key 协程不会重启，第二句话就再也不会出现
    var notice by remember { mutableStateOf<HomeRefreshNotice?>(null) }
    LaunchedEffect(refreshNotice?.tick) {
        val incoming = refreshNotice ?: return@LaunchedEffect
        notice = incoming
        delay(2_500L)
        notice = null
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            HomeToolbar(
                fetchedAt = pageFetchedAt,
                now = nowMs,
                refreshing = refreshing,
                onRefresh = onRefresh,
                onOpenSearch = onOpenSearch,
                onOpenHistory = onOpenHistory,
                onOpenFavorites = onOpenFavorites,
                onOpenCompleted = onOpenCompleted,
                onOpenSettings = onOpenSettings,
                // 工具栏按「下」落到当前选中的那个 tab（默认就是第 1 项「推荐」）。
                // 不指方向时几何搜索会挑中最右边的「动漫」，见 HomeToolbar 的说明。
                downFocus = tabFocus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 12.dp),
            )

            Spacer(Modifier.height(10.dp))

            HomeTabRow(
                selected = tab,
                onSelect = onSelectTab,
                // 用户一旦自己动过 tab 栏，就说明焦点已经由他接管，别再往回抢
                onUserFocused = { if (contentFocusedOnce) firstFocusDone = true },
                selectedTabFocus = tabFocus,
                // 只有内容真的存在时才给「下」绑落点：加载/错误/空态那几屏没有可聚焦元素
                downFocus = if (items.isEmpty()) null else entryFocus,
                // 48 -> 40：剩下 8dp 交给 LazyRow 的 contentPadding，
                // 让第一个 tab 聚焦放大时有地方可溢出（见 HomeTabRow 的说明）。
                // 两者相加仍是 48dp，与工具栏、网格左边缘严格对齐。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 40.dp),
            )

            Spacer(Modifier.height(12.dp))

            // 内容区必须用 weight(1f) 吃掉"剩余"高度。
            // 用 fillMaxSize() 是个坑：Column 给非 weight 子项的 maxHeight 是整列高度，
            // fillMaxSize 于是拿到 540dp 全高、排在页头（约 130dp）后面，实际溢出屏幕 130dp——
            // 网格视口比屏幕长出一截，焦点滚动以为末行"已可见"，真机上末行就被屏幕下沿切掉。
            // 尾部留白交给网格自己的 contentPadding.bottom（ListMetrics.TailPad）：
            // 那是**列表内部**的空间，滚到底时最后一项下面才会真正空出来。
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // 「错误只在没有任何数据时才露脸」：有内容时请求失败必须静默——
                    // 用户看到的仍然是能用的那一屏（需求 9），再把错误糊上去没有意义。
                    items.isEmpty() && error != null -> ErrorState(
                        error,
                        onRetry = onRefresh,
                        retryFocus = emptyRetryFocus,
                    )
                    items.isEmpty() && loading -> LoadingState("正在拉取「${tab.title}」…")
                    items.isEmpty() -> EmptyState(
                        "「${tab.title}」暂无内容",
                        onRetry = onRefresh,
                        retryFocus = emptyRetryFocus,
                    )
                    isRecommend -> RecommendContent(
                        recommendItems = recommendItems,
                        // 历史流已是 updatedAt 倒序，take 前 N 条即「最近 N 条」
                        history = history.take(CONTINUE_WATCHING_LIMIT),
                        loading = loading,
                        prefetchCids = prefetchCids,
                        tabFocus = tabFocus,
                        firstCardFocus = firstRecommendFocus,
                        entryFocus = entryFocus,
                        onContentFocused = onContentFocused,
                        onOpenDetail = onOpenDetail,
                        onResumeHistory = onResumeHistory,
                    )
                    else -> CategoryGrid(
                        tab = tab,
                        items = items,
                        loading = loading,
                        firstCardFocus = firstGridFocus,
                        tabFocus = tabFocus,
                        entryFocus = entryFocus,
                        onContentFocused = onContentFocused,
                        onOpenDetail = onOpenDetail,
                    )
                }

                // 底边渐隐：一屏装不下一整行时，第二行必然被屏幕下沿切一半。
                // 硬切看起来像布局没做完，一层到页底的渐变把它变成「下面还有」的暗示。
                // 它压在最上层但不吃按键（没有 clickable/focusable），也不参与布局。
                // 只在有内容时出现：空态/加载态不需要这层遮盖。
                if (items.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to ModaColors.Bg,
                                )
                            )
                    )
                }
            }
        }

        // 刷新结果提示浮在底部中央：脱离布局流，不推挤内容、不参与焦点、不放大会抢键。
        // 一次刷新的结论只值一行字，为这一行字把整片内容顶上去再弹回来，
        // 比不提示还难受——「页面不要重新刷新」这条要求对提示同样成立。
        notice?.let {
            RefreshNoticeBar(
                notice = it,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
            )
        }
    }
}

/**
 * 刷新结果提示条：成功用常规字色，失败用 [ModaColors.Danger]。
 *
 * 颜色是这里唯一的区分手段，文案必须能自己说清结果——电视上没有图标可点、
 * 也没有悬浮查看，用户扫一眼就得知道「成没成」。
 */
@Composable
private fun RefreshNoticeBar(notice: HomeRefreshNotice, modifier: Modifier = Modifier) {
    Text(
        text = notice.message,
        style = TvBody.copy(fontSize = 18.sp),
        color = if (notice.success) ModaColors.Text else ModaColors.Danger,
        modifier = modifier
            .background(ModaColors.SurfaceHigh, RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 14.dp),
    )
}

/**
 * 顶部工具栏：品牌 + 整页获取时间 + 图标组。
 *
 * 时间夹在「ModaTv」与图标组之间而不是放到图标之后：它描述的是**整页内容有多新**，
 * 与内容同侧读起来才连成一句话；丢到图标组右边会被读成又一个按钮。
 *
 * 图标顺序固定为 刷新 | 搜索 | 历史 | 收藏 | 已看完 | 设置。
 * 刷新是 P2-A 新增的：内容分散在多个频道缓存里，用户需要一个「不判缓存时间、就地重拉」的入口；
 * 旧版刻意没放刷新，是因为那时只有一屏、重进应用就等于刷新。
 */
@Composable
private fun HomeToolbar(
    fetchedAt: Long,
    now: Long,
    /** 整页刷新进行中：刷新图标自转，作为「按下去有反应」的唯一凭证 */
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenCompleted: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * 按下键的去处：tab 行的第一个。
     *
     * 不指定时 Compose 按几何找正下方最近的可聚焦元素，实测会落到 tab 行最右边那个
     * （「动漫」），因为图标组本身就贴在右上角、几何距离更近。眼睛在左上、光标跳到最右，
     * 这是纯粹的反直觉。所以每个图标都显式指回第一个 tab。
     */
    downFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    // 每个图标都要挂：焦点停在任意一个图标上按「下」都得进 tab 行第一项
    val downModifier = if (downFocus != null) Modifier.focusProperties { down = downFocus } else Modifier

    // 刷新时的自转：角度在**绘制阶段**改（graphicsLayer 的 lambda 里读状态只重绘、不重组），
    // 而不是在组合期把 Modifier 链换掉。
    //
    // 踩过的坑：早先把 Modifier.rotate 放进 if (refreshing) 分支，按下刷新的那一刻
    // 工具栏的组合结构变一次，焦点从刷新图标掉到内容区第一张卡——按一次刷新，
    // 光标被踢下去，等于刷新顺手把你赶出了工具栏。现在结构与动画都固定：
    // 节点一直是同一个，只是它转起来。空闲时帧循环根本不启动，不白烧帧。
    val spinAngle = remember { mutableStateOf(0f) }
    LaunchedEffect(refreshing) {
        while (refreshing) {
            withFrameNanos { }
            spinAngle.value = (spinAngle.value + 6f) % 360f
        }
    }
    val refreshIconModifier = Modifier.graphicsLayer { rotationZ = spinAngle.value }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // 品牌位：图标 + 文字。图标与桌面启动图标同一套造型，
        // 用户从桌面点进来能立刻对上号。
        Icon(
            painter = painterResource(id = R.drawable.ic_brand_modatv),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        // 品牌文字比页面大标题小一档：它是标识不是标题，
        // 和图标一起构成一个「标志块」，抢了 hero 标题的视觉重量就不对了
        Text(
            "ModaTv",
            style = TvTitle.copy(fontSize = 28.sp),
            color = ModaColors.Text,
        )
        Spacer(Modifier.width(20.dp))
        // 时间比正文小一档、用 TextDim 退进背景：它是注脚，不是标题。
        // 两截并列（上次刷新 | 现在）：见 HomeScreen 顶部的说明，缺了「现在」那一截，
        // 读者分不清这个时刻是数据的年龄还是此刻的钟点。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lastRefreshLabel(fetchedAt, now),
                style = TvCaption.copy(fontSize = 15.sp),
                color = ModaColors.TextDim,
            )
            Text(
                text = " · ",
                style = TvCaption.copy(fontSize = 15.sp),
                color = ModaColors.TextDim,
            )
            Text(
                text = "现在 " + clockLabel(now),
                style = TvCaption.copy(fontSize = 15.sp),
                color = ModaColors.TextDim,
            )
        }
        Spacer(Modifier.weight(1f))
        // 每个图标都带 contentDescription：屏幕阅读器与自动化测试靠它识别语义。
        // 工具栏只显示、不抢首焦点（首焦点由 HomeScreen 的 enter 与多帧重试交给内容区）。
        IconActionButton(
            icon = Icons.Default.Refresh,
            contentDescription = "刷新",
            onClick = onRefresh,
            modifier = downModifier,
            iconModifier = refreshIconModifier,
        )
        Spacer(Modifier.width(16.dp))
        IconActionButton(
            icon = Icons.Default.Search,
            contentDescription = "搜索",
            onClick = onOpenSearch,
            modifier = downModifier,
        )
        Spacer(Modifier.width(16.dp))
        IconActionButton(
            icon = Icons.Default.History,
            contentDescription = "历史",
            onClick = onOpenHistory,
            modifier = downModifier,
        )
        Spacer(Modifier.width(16.dp))
        // === 收藏模块（勿删）=== 位于历史与设置之间，不要移动位置。
        // FavoriteBorder 而不是实心 Favorite：未收藏是这片图标组的常态，
        // 空心轮廓与搜索/历史/设置的视觉重量一致
        IconActionButton(
            icon = Icons.Default.FavoriteBorder,
            contentDescription = "收藏",
            onClick = onOpenFavorites,
            modifier = downModifier,
        )
        Spacer(Modifier.width(16.dp))
        // 已看完（需求 1）：CheckCircle「圆里一个勾」与「已看完」语义直接对应，
        // 比 Stars / DoneAll 更不容易和收藏、历史混淆
        IconActionButton(
            icon = Icons.Default.CheckCircle,
            contentDescription = "已看完",
            onClick = onOpenCompleted,
            modifier = downModifier,
        )
        Spacer(Modifier.width(16.dp))
        IconActionButton(
            icon = Icons.Default.Settings,
            contentDescription = "设置",
            onClick = onOpenSettings,
            modifier = downModifier,
        )
    }
}

/**
 * 「上次刷新」文案。
 *
 * fetchedAt == 0 表示「整页还没成功刷新过」（pageFetchedAt 从未刷成功过就是 0L），
 * 必须显式判零：直接格式化会把 0 渲染成 1970-01-01 08:00，看起来像真抓到了什么。
 *
 * 今天/昨天用中文相对日而不是日期：这个位置一天里绝大多数时候都是「今天」，
 * 天天挂着同一个日期只会让这一行变成一块看不出变化的背景噪声。
 */
private fun lastRefreshLabel(fetchedAt: Long, now: Long): String {
    if (fetchedAt <= 0L) return "尚未刷新"
    val day = when {
        isSameDay(fetchedAt, now) -> "今天 "
        isSameDay(fetchedAt, now - DAY_MS) -> "昨天 "
        // 更早的才退回日期，且不带年份：电视上「去年刷的」和「从没刷过」没有区别
        else -> SimpleDateFormat("M月d日 ", Locale.getDefault()).format(Date(fetchedAt))
    }
    return "上次刷新 " + day + clockLabel(fetchedAt)
}

/** 时分。两处（上次刷新 / 现在）共用一份格式，避免各写一遍、日后改一处漏一处 */
private fun clockLabel(at: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** 同一「自然日」判断：按本地时区的年月日比，不能用 24 小时差量糊过去 */
private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** tab 卡片的焦点描边；和 Cards.kt 里的 focusBorder 同一套视觉（那个是 private 的） */
private fun tabFocusBorder(corner: Dp) =
    Border(border = BorderStroke(3.dp, ModaColors.Focus), shape = RoundedCornerShape(corner))

/**
 * 分类 tab 栏。
 *
 * 交互选择：**焦点移动即切换**，不要求再按一次确认。
 * 遥控器上「移过去预览、再按确认才生效」是两步操作，而这一栏的效果只是换一屏列表，
 * 没有需要二次确认的代价；v.qq.com 官网也是鼠标移过去就换。
 * 确认键仍接同一个动作——有些用户习惯「移过去再按一下」，不能让他按下去没反应。
 *
 * 选中态与焦点态是两套视觉，不能混：
 *   选中 = 深色块(SurfaceHigh) + 加粗白字 + 蓝色下划线，表示「这一屏是哪个分类」；
 *   焦点 = 全站统一的白底深字 + 白描边，表示「遥控器现在停在哪」。
 * 两者可以同时成立（焦点正停在当前 tab 上），这时白底盖住深色块，但下划线仍是蓝色，
 * 于是「当前分类」这条信息不会因为焦点压上来而丢失。
 */
@Composable
private fun HomeTabRow(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    /** 用户自己把焦点移进 tab 栏时通知外层：从此不要再抢首焦点 */
    onUserFocused: () -> Unit,
    selectedTabFocus: FocusRequester,
    /** 内容区首卡。为 null 表示这一屏没有可落点的内容（正在加载 / 错误 / 空态） */
    downFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    // 用 LazyRow 而不是普通 Row：频道扩到 22 个之后一屏必然放不下。
    //
    // 之前用 Row 的理由是「5 个 tab 一排放得下，换来切到第 5 个时整排不被滚走」。
    // 这条理由在 22 个 tab 下已经不成立——不滚动的话后面 17 个根本点不到。
    //
    // 焦点驱动的自动滚动由 LazyRow 自己处理：焦点移到视口外的 tab 时它会自动把它带进来。
    LazyRow(
        /**
         * 这个 8dp 是给「聚焦放大」留的溢出空间，不是审美留白，而且必须写在**视口之外**。
         *
         * 写成 contentPadding 时会出事：焦点按「右」推到最后一个 tab 再按「左」回来，
         * 这 8dp 先被滚动吃掉——第一个 tab 与工具栏左缘差 8dp，
         * 「推荐」左侧的圆角/描边还被 LazyRow 的视口切平（真机上就是这个现象）。
         * 外层 40dp + 这里 8dp 仍是 48dp，第一个 tab 依旧与工具栏图标左对齐。
         */
        modifier = modifier.railScrollRoom(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(count = HomeTab.entries.size, key = { i -> HomeTab.entries[i].name }) { index ->
            val entry = HomeTab.entries[index]
            val isSelected = entry == selected
            TabChip(
                title = entry.title,
                isSelected = isSelected,
                onClick = { onSelect(entry) },
                // 焦点一落上来就切。重复切同一个 tab 由 ViewModel 的 selectHomeTab 幂等兜住
                // （它只在这个 tab 从未加载过时才发请求）
                onFocused = {
                    onUserFocused()
                    if (!isSelected) onSelect(entry)
                },
                focusRequester = if (isSelected) selectedTabFocus else null,
                downFocus = downFocus,
            )
        }
    }
}

@Composable
private fun TabChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
    downFocus: FocusRequester?,
) {
    val corner = 8.dp
    // 焦点态必须自己记：选中项的深色底得让位给聚焦白底，
    // 否则卡片画了白底、内层 Box 又把深色刷回去，用户看到的就是「焦点没了」。
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 从 tab 栏往下必须进内容区第一张卡。
            // 不指定的话 Compose 按几何找下方最近的可聚焦元素，
            // 实测会落到工具栏图标或内容区之外，焦点凭空消失。
            //
            // 只在内容真的存在时绑定：加载中 / 错误态 / 空态那几屏一个可聚焦元素都没有，
            // 此时挂一个连不上节点的 requester 是没有意义的（按下键什么也不会发生，
            // 而正确的行为本来就是「下面没东西可去」）。
            .then(
                if (downFocus != null) Modifier.focusProperties { down = downFocus }
                else Modifier
            )
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            // 选中项常态用亮一档的白字，未选中退成 TextDim：靠亮度分级，
            // 不靠字号——同一栏里字号忽大忽小会让 tab 行看起来在抖
            contentColor = if (isSelected) ModaColors.Text else ModaColors.TextDim,
            focusedContainerColor = ModaColors.Focus,
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = ModaColors.Focus,
            pressedContentColor = ModaColors.Bg,
        ),
        // 不放大（focusedScale = 1f）：LazyRow 会把子项裁到自己的视口，而放大是"只改绘制
        // 不改布局"——第一个 tab 向左溢出的那几 dp 被视口直接切掉，真机上表现为
        // 「推荐的白框没有左边、左圆角是平的」。焦点态改由白底 + 加粗字 + 蓝下划线表达，
        // 与 SettingRow / 设置分组卡同一套（它们也不放大，理由相同）。
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = tabFocusBorder(corner),
        ),
    ) {
        Column(
            Modifier
                // 焦点/点击面积必须够大：遥控器在 3 米外要把光标停在这上面，
                // 只按文字宽度做热区会一直滑过去
                // 宽度交给文字自己撑（只保底一个可点的最小高度）：
                // 22 个 tab 不可能全部塞进一屏，与其算一个刚好卡住的固定宽度，
                // 不如让「NBA」这种窄标题和「纪录片」这种宽标题各占自己该占的地方。
                // 热区靠 vertical padding 撑够，遥控器在 3 米外也停得住。
                .defaultMinSize(minHeight = 44.dp)
                // 选中底色从 SurfaceHigh(#1F2129) 换成强调蓝的浅染：
                // 前者压在 #0B0B0F 页底上只有约 1.35:1，3 米外等于没有，
                // 于是「当前是哪个分类」全靠一条 3dp 下划线在撑，太单薄。
                .background(if (isSelected && !focused) ModaColors.Accent.copy(alpha = 0.32f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 可聚焦容器内必须显式跟随内容色：TvBody 把颜色烤死在 style.color 上，
            // 不传 color 的话聚焦白底上仍是浅色字（规则见 Cards.kt 顶部那段）
            Text(
                text = title,
                style = TvBody.copy(
                    fontSize = 19.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = LocalContentColor.current,
                maxLines = 1,
                // 下划线画在文字自己身上，宽度天然等于文字宽度。
                // 不能用一个 fillMaxWidth 的 Box：LazyRow 的项在滚动轴上宽度无界，
                // fillMaxWidth 会退化成 0 宽——真机上表现为「下划线整个消失」。
                modifier = Modifier.drawBehind {
                    if (isSelected) {
                        val gap = 4.dp.toPx()
                        drawRect(
                            color = ModaColors.AccentSoft,
                            topLeft = Offset(0f, size.height + gap),
                            size = Size(size.width, 3.dp.toPx()),
                        )
                    }
                },
            )
            // 下划线常驻占位（画不画由上面的 drawBehind 决定）：否则切换选中时整块高度会跳一下，
            // 下面的内容区跟着抖一帧。高度 = 4dp 间隙 + 3dp 线。
            Spacer(Modifier.height(7.dp))
        }
    }
}

/**
 * 推荐 tab：一块 5 列网格装下推荐位条目与「继续观看」行。
 *
 * 为什么不是「大轮播 + 横向卡片行」（旧版做法，已按用户要求推翻）：
 *  1. 用户要的是「一行最多 5 个、不超出屏幕」——横向 LazyRow 永远只露 4~5 张、剩下的靠右键翻，
 *     屏幕上永远看不全一整组，这与「一屏看尽」的遥控器阅读习惯相反；
 *  2. 轮播吃掉 200dp 竖向空间，等于把一整行海报挤出首屏；
 *  3. 竖排网格里 ↑↓ 就是「上一行/下一行」，符合直觉，不需要为横向行单独设计焦点规则。
 *
 * 推荐与继续观看放进**同一个** LazyVerticalGrid：两者都是海报墙，分成两个滚动容器会出现
 * 「内层滚完外层再滚」的双滚动，遥控器上极难操作。分区靠满宽 span 的标题行隔开。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RecommendContent(
    recommendItems: List<VideoItem>,
    history: List<LibraryEntry>,
    loading: Boolean,
    prefetchCids: Set<String>,
    tabFocus: FocusRequester,
    firstCardFocus: FocusRequester,
    entryFocus: FocusRequester,
    onContentFocused: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onResumeHistory: (LibraryEntry) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // 焦点所在项的下标：TV 的列表滚动只认它，见 TvGridFocusScroll
    var focusedIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = gridState, focusedIndex = focusedIndex, columns = GRID_COLUMNS)

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        // enter 指向首卡：进首页时焦点必须直接落进内容区，
        // 而不是让 Compose 按几何挑到工具栏的刷新图标
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { onEnter = { entryFocus } },
        // 顶部 12dp 必须留在 contentPadding（视口之内）：焦点滚动会把被聚焦项顶到视口边，
        // 写在外面的留白它根本用不上，于是首行卡片聚焦放大后上沿被视口切平
        //（"光标向下再向上，光标有一部分被吃了"就是这么来的）。
        contentPadding = ListMetrics.gridContentPadding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // 纵向缝隙由每项自己的 10dp 余量提供（2×10=20dp），这里不再叠加
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        gridItemsIndexed(
            items = recommendItems,
            key = { _, item -> "recommend-${item.cid}" },
        ) { index, item ->
            VideoCard(
                item = item,
                onClick = { onOpenDetail(item.cid, item.title) },
                // 一行标题：两行会让行高随内容浮动，5 列网格里会少露一整行
                titleMaxLines = 1,
                // 关掉副标题：它是又一整行 14sp 文字，加上标题会把卡片顶高到
                // 一屏放不下两行海报；而且「有无副标题」会让行高在数据之间来回跳
                showSubtitle = false,
                // 列宽由网格算，卡片不再自己定宽
                width = null,
                preferHiRes = prefetchCids.contains(item.cid),
                modifier = Modifier
                    .fillMaxWidth()
                    // 每项自带 10dp 纵向余量：聚焦放大（scale 1.06~1.08，上下各约 5~10dp）
                    // 是纯绘制溢出，网格视口不会为它让路——首行卡片的上沿、末行的下沿
                    // 会被切平，看起来就是"选中框缺了一条边"。余量放进项自己的布局里就不会被裁。
                    .padding(vertical = 10.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier)
                    // 只有第一行按上键回 tab 栏。第二行往下若再指同一个 requester，
                    // 跨行时焦点就会被拽回第 0 张
                    .then(
                        if (index < GRID_COLUMNS) {
                            Modifier.focusProperties { up = tabFocus }
                        } else {
                            Modifier
                        },
                    )
                    // 有任何一张卡拿到焦点就停掉首焦点的重试循环，
                    // 否则用户刚往右挪两格，循环又把他拽回第 0 张
                    .onFocusChanged { st ->
                        if (st.isFocused) {
                            // 交给 TV 的按行跟焦规则：它按这个下标算"整行"的上下沿
                            focusedIndex = index
                            onContentFocused()
                        }
                    },
            )
        }

        // ---- 继续观看区 ----
        // 需求 10 原话「如果没有那么就不显示」：整段不组合，不是给一个空态占位
        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(GRID_COLUMNS) }, key = "continue-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("继续观看")
                    Spacer(Modifier.width(12.dp))
                    Text("${history.size} 条", style = TvBody, color = ModaColors.TextDim)
                }
            }

            gridItemsIndexed(
                items = history,
                // 同一部剧可能同时有多集记录，只用 cid 会撞 key，网格会把重复项直接吞掉
                key = { i, entry -> "history-${entry.cid}-${entry.episodeIndex}-$i" },
            ) { _, entry ->
                ContinueWatchingCard(
                    entry = entry,
                    // 列宽由网格算
                    width = null,
                    // 直接进播放页，不经过详情（需求 10 原话）
                    onClick = { onResumeHistory(entry) },
                    modifier = Modifier
                    .fillMaxWidth()
                    // 每项自带 10dp 纵向余量：聚焦放大（scale 1.06~1.08，上下各约 5~10dp）
                    // 是纯绘制溢出，网格视口不会为它让路——首行卡片的上沿、末行的下沿
                    // 会被切平，看起来就是"选中框缺了一条边"。余量放进项自己的布局里就不会被裁。
                    .padding(vertical = 10.dp)
                        .onFocusChanged { state -> if (state.isFocused) onContentFocused() },
                )
            }
        }

        // 尾部「正在刷新」提示，与 CategoryGrid 同一个语义：
        // 有旧内容就继续显示，只多一行小字，不遮屏。
        // 首页每个 tab 只有一屏，loading 在这里只可能是「重拉这一屏」，
        // 文案也就不能说成「加载更多」——那会让用户以为下面还有内容。
        if (loading) {
            item(span = { GridItemSpan(GRID_COLUMNS) }, key = "loading-more") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("正在刷新…", style = TvBody, color = ModaColors.TextDim)
                }
            }
        }
    }
}

/**
 * 分类 tab：竖向滚动的海报网格，每行 5 个（需求 11）。
 *
 * 用 LazyVerticalGrid 而不是「LazyColumn 套 LazyRow」：网格的行列在同一个滚动容器里，
 * 上下键天然跨行、左右键天然在行内移动，不需要给每张卡手写 next/down 的焦点关系——
 * 那种写法在列数变化或最后一行不满时必然出错（焦点会卡在最后一行的空位上）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CategoryGrid(
    tab: HomeTab,
    items: List<VideoItem>,
    loading: Boolean,
    firstCardFocus: FocusRequester,
    tabFocus: FocusRequester,
    entryFocus: FocusRequester,
    onContentFocused: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = gridState, focusedIndex = focusedIndex, columns = GRID_COLUMNS)

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        // enter 指向首卡：切到分类 tab 时焦点必须直接落进网格，
        // 而不是让 Compose 按几何挑到工具栏的刷新图标
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { onEnter = { entryFocus } },
        // 顶部 12dp 必须留在 contentPadding（视口之内）：焦点滚动会把被聚焦项顶到视口边，
        // 写在外面的留白它根本用不上，于是首行卡片聚焦放大后上沿被视口切平
        //（"光标向下再向上，光标有一部分被吃了"就是这么来的）。
        contentPadding = ListMetrics.gridContentPadding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // 同推荐页：纵向缝隙由项自身的 10dp 余量提供
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        gridItemsIndexed(
            items = items,
            key = { _, item -> "${tab.name}-${item.cid}" },
        ) { index, item ->
            VideoCard(
                item = item,
                onClick = { onOpenDetail(item.cid, item.title) },
                // 两行标题在 5 列网格里会把行高撑高、少露一行；一行足够认剧
                titleMaxLines = 1,
                // 关掉副标题：它是又一整行 14sp 文字，加上标题会把卡片顶高到
                // 一屏放不下两行海报；而且「有无副标题」会让行高在数据之间来回跳
                showSubtitle = false,
                // 列宽由网格算，卡片不再自己定宽
                width = null,
                preferHiRes = false,
                modifier = Modifier
                    .fillMaxWidth()
                    // 每项自带 10dp 纵向余量：聚焦放大（scale 1.06~1.08，上下各约 5~10dp）
                    // 是纯绘制溢出，网格视口不会为它让路——首行卡片的上沿、末行的下沿
                    // 会被切平，看起来就是"选中框缺了一条边"。余量放进项自己的布局里就不会被裁。
                    .padding(vertical = 10.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier)
                    // 只有第一行按上键回 tab 栏。第二行往下如果再指同一个 requester，
                    // 跨行时焦点就会被拽回第 0 张。
                    .then(
                        if (index < GRID_COLUMNS) {
                            Modifier.focusProperties { up = tabFocus }
                        } else {
                            Modifier
                        },
                    )
                    // 有任何一张卡拿到焦点就停掉首焦点的重试循环，
                    // 否则用户刚往右挪两格，循环又把他拽回第 0 张
                    .onFocusChanged { st ->
                        if (st.isFocused) {
                            // 交给 TV 的按行跟焦规则：它按这个下标算"整行"的上下沿
                            focusedIndex = index
                            onContentFocused()
                        }
                    },
            )
        }

        // 本分支本来就要求 items 非空，所以这里永远不是「首加载骨架」，
        // 只表示「这一屏正在重拉」——需求 9：有旧数据就继续显示，底部一行小字提示即可
        if (loading) {
            item(span = { GridItemSpan(GRID_COLUMNS) }, key = "loading-more") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("正在刷新…", style = TvBody, color = ModaColors.TextDim)
                }
            }
        }
    }
}

