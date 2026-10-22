package com.moda.tv.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.size
import com.moda.tv.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.model.HomeRow
import com.moda.tv.model.VideoItem
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.HeroCarousel
import com.moda.tv.ui.components.IconActionButton
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.SectionTitle
import com.moda.tv.ui.components.VideoCard
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvTitle

/**
 * 首屏预取高清封面的卡片数。
 * 只有第一行、且只在最前面这几张：它们是最可能被先看到的卡，
 * 同时把首屏的 eps 请求数压到个位数（见 RemoteImage 里的取舍说明）。
 */
private const val HI_RES_PREFETCH_COUNT = 6

/**
 * 首屏卡宽度。本机实测屏幕是 960x540dp（1920x1080 @320dpi），
 * 去掉工具栏 + 200dp 轮播 + 行标题后只剩约 190dp 给卡片。
 * 210dp 宽的 3:4 海报高达 280dp，一行永远露不全；140dp 是
 * 「海报还能看清剧照 + 剧名完整显示」与「整行落在首屏内」的交点。
 */
private val HOME_CARD_WIDTH = 118.dp

/**
 * Leanback 式首页：顶部横幅 + 多行横向卡片。
 * 行内左右交给 LazyRow，行间上下交给 LazyColumn 与焦点系统；
 * 走到最后一行的尾部时回调 onLoadMore，让外部去拉下一批行。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    rows: List<HomeRow>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlayer: (String, String, String, Int) -> Unit,
    onLoadMore: () -> Unit = {},
    // 尾部可选参数：后续加收藏/设置页时直接追加实现，不必再动既有调用点
    onOpenHistory: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // 轮播条目来自 v.qq.com 推荐位；为空时横幅回退到 rows[0].items[0]，首页永远不空
    carousel: List<com.moda.tv.model.VideoItem> = emptyList(),
) {
    val listState = rememberLazyListState()
    // 每行最后一次停留的卡片下标，行被回收重建后还能回到原处
    val rowCursor = remember { mutableStateMapOf<Int, Int>() }
    val heroFocus = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }
    // 首页整体焦点容器：首焦点只允许进入内容区。
    // 用法见下方 Box 上的 focusProperties { enter = … }——不这样指定的话，
    // Compose 会按「几何位置最靠前」挑第一个可聚焦元素，正好是工具栏的搜索图标。
    val contentFocus = remember { FocusRequester() }
    // 第一行首卡：轮播最后一张按右键落在这里（见 HeroCarousel 的 rightEdgeFocus）
    val firstRowFocus = remember { FocusRequester() }
    var loadMoreFired by remember(rows.size) { mutableStateOf(false) }

    val firstItem = rows.firstOrNull()?.items?.firstOrNull()
    /**
     * 横幅数据源：优先用抓来的推荐位；它为空（断网 / 页面改版 / 全部 cid 解析失败）
     * 就回退成原来的「第一行第一张」，这是首页不空的唯一保证。
     */
    val heroItems = remember(carousel, firstItem) {
        carousel.ifEmpty { listOfNotNull(firstItem) }
    }
    // 第一行的横幅卡与首个卡片行共用这批 item，预取预算从第一行里出
    val prefetchCids = remember(rows) {
        rows.firstOrNull()?.items?.take(HI_RES_PREFETCH_COUNT)?.map { it.cid }?.toSet() ?: emptySet()
    }

    // 首焦点必须落在轮播第一张缩略图上。
    //
    // 踩过的坑：用 LaunchedEffect(heroItems.isNotEmpty()) 抢焦点时，key 一变协程就被取消重启；
    // 而轮播数据是异步到的，重启发生在新布局完成之前，requestFocus 抛异常被吞，
    // firstFocusDone 又已置位 → 焦点永久留在工具栏搜索图标上。
    //
    // 现在：key 用 heroItems.size（数据真正就绪才触发），并用「连续多帧成功」兜住布局抖动。
    // 轮播数据是异步抓的（实测冷启约 2.4 秒），必须等它真的到了再抢焦点。
    // 之前固定重试 12 帧（约 200ms）根本覆盖不到数据到达的时刻，
    // 那一瞬间缩略图行还不存在，requestFocus 必然失败，焦点就留在了工具栏。
    LaunchedEffect(heroItems.size) {
        if (heroItems.isEmpty() || firstFocusDone) return@LaunchedEffect
        // 数据已就绪：给布局最多 300 帧（约 5 秒）完成排布，抢到即止。
        //
        // 为什么不是判 requestFocus() 的返回值：当 FocusRequester 还没有挂到任何
        // 可聚焦节点上（缩略图行尚未组合出来）时，它会打印一条
        // 「FocusRequester is not initialized」后**正常返回**，不抛异常。
        // 之前用 runCatching{}.isSuccess 判断，于是第一次调用就被当成成功，
        // firstFocusDone 直接置位，焦点实际还留在工具栏的搜索图标上。
        // 现在改成让轮播自己在获得焦点时置位，请求只是「尽力而为」。
        repeat(300) {
            withFrameNanos { }
            if (firstFocusDone) return@LaunchedEffect
            heroFocus.requestFocus()
        }
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        when {
            rows.isEmpty() && error != null -> ErrorState(error, onRetry = onRetry)
            rows.isEmpty() && loading -> LoadingState("正在拉取首页…")
            rows.isEmpty() -> EmptyState("首页暂无内容", onRetry = onRetry)
            else -> LazyColumn(
                state = listState,
                // 首页的「进入焦点」直接交给轮播第一张缩略图，
                // 而不是让 Compose 按几何位置自己挑——那样会挑到工具栏的搜索图标。
                // 这正是「启动后光标停在搜索上」的根因。
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    // enter 需要一个 (FocusDirection) -> FocusRequester 的函数
                    .focusProperties { enter = { heroFocus } },
                // 竖向留白收紧：本机实测量到屏幕是 960x540dp（1920x1080 @320dpi），
                // 原来 top 32 + 间距 26x2 + bottom 64 光留白就吃掉 148dp，
                // 轮播下面那一行就永远露不全。收到 20/18/24 后，一屏能稳定放下
                // 「工具栏 + 轮播 + 热门行的行标题与整张卡」。
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "toolbar") {
                    // 工具栏：只显示，不参与首焦点竞争。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 品牌名保持文字；功能入口只用图标——遥控器上图标比文字更好扫读
                        // 品牌位：图标 + 文字。图标与桌面启动图标同一套造型，
                        // 用户从桌面点进来能立刻对上号。
                        Icon(
                            painter = painterResource(id = R.drawable.ic_brand_modatv),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        // 品牌文字比页面大标题小一档：它是标识不是标题，
                        // 和图标一起构成一个「标志块」，抢了 hero 标题的视觉重量就不对了
                        Text(
                            "ModaTv",
                            style = TvTitle.copy(fontSize = 30.sp),
                            color = ModaColors.Text,
                        )
                        Spacer(Modifier.weight(1f))
                        // 每个图标都带 contentDescription：屏幕阅读器与自动化测试靠它识别语义。
                        // 工具栏顺序固定为 搜索 | 历史 | 收藏 | 设置。
                        // 没有刷新图标：遥控器上翻首页的成本远高于误触一次刷新的收益，
                        // 需要刷新时重进应用即可；onRetry 仍供错误态的 ErrorState 重试按钮使用。
                        IconActionButton(
                            icon = Icons.Default.Search,
                            contentDescription = "搜索",
                            onClick = onOpenSearch,
                        )
                        Spacer(Modifier.width(16.dp))
                        IconActionButton(
                            icon = Icons.Default.History,
                            contentDescription = "历史",
                            onClick = onOpenHistory,
                        )
                        Spacer(Modifier.width(16.dp))
                        // === 收藏模块（勿删）=== 位于历史与设置之间，不要移动位置。
                        // FavoriteBorder 而不是实心 Favorite：未收藏是这片图标组的常态，
                        // 空心轮廓与搜索/历史/设置的视觉重量一致
                        IconActionButton(
                            icon = Icons.Default.FavoriteBorder,
                            contentDescription = "收藏",
                            onClick = onOpenFavorites,
                        )
                        Spacer(Modifier.width(16.dp))
                        IconActionButton(
                            icon = Icons.Default.Settings,
                            contentDescription = "设置",
                            onClick = onOpenSettings,
                        )
                    }
                }

                if (heroItems.isNotEmpty()) {
                    item(key = "hero") {
                        Column(Modifier.fillMaxWidth()) {
                            // 单条时 HeroCarousel 自动退化成旧外观：没有指示器、不自动切换，
                            // 免得回退到搜索热门第一条时看起来像"还有别的可以翻"
                            HeroCarousel(
                                items = heroItems,
                                onClick = { item -> onOpenDetail(item.cid, item.title) },
                                // 首焦点交给缩略图行：左右键立刻可用且能驱动大图，
                                // 比停在只是一块 onClick 的大图区更顺手
                                thumbFocusRequester = heroFocus,
                                // 轮播真的拿到焦点后才置位：requestFocus() 在节点未上树时
                                // 只打日志、照样返回，靠它判断会误报成功。
                                onFocused = { firstFocusDone = true },
                                // 最后一张缩略图按右键 → 往下进「电视剧」首卡，
                                // 而不是跳到右上角工具栏（几何上那里离得更近）。
                                rightEdgeFocus = firstRowFocus,
                                // 不写死高度：HeroCarousel 自己按「主视觉 + 缩略图行」算高度
                                // （约 270dp），外面再套一层固定高度会在缩略图行上留出空白或裁掉它。
                                // 整体仍落在 260~300dp 的预算内，见 Cards.kt 的说明。
                                // 注意：这里不能再挂 focusRequester(heroFocus)。
                                // 同一个 FocusRequester 绑到两个可聚焦元素上会让焦点解析冲突，
                                // 表现就是「切一条后焦点丢失、大图变成空白」。
                                // 首焦点只由 thumbFocusRequester 交给第 0 个缩略图。
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // 横幅本身按确认就进详情，「从头播放/查看详情」是同一行为的重复入口，
                            // 还会挤占纵向空间、稀释焦点导航，所以不放按钮。
                        }
                    }
                }

                items(count = rows.size, key = { index -> "row-$index-${rows[index].title}" }) { rowIndex ->
                    val row = rows[rowIndex]
                    val isLastRow = rowIndex == rows.lastIndex
                    HomeRowLane(
                        row = row,
                        rowIndex = rowIndex,
                        initialCursor = rowCursor[rowIndex] ?: 0,
                        onCursorChange = { rowCursor[rowIndex] = it },
                        onOpenDetail = onOpenDetail,
                        prefetchCids = prefetchCids,
                        // 只有紧贴轮播的第一行需要这条上行边：更下面几行的上方就是另一行
                        upFocus = if (rowIndex == 0) heroFocus else null,
                        firstCardFocus = if (rowIndex == 0) firstRowFocus else null,
                        onReachTail = {
                            if (isLastRow && !loadMoreFired) {
                                loadMoreFired = true
                                onLoadMore()
                            }
                        },
                    )
                }

                if (loading) {
                    item(key = "loading-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("正在加载更多…", style = TvBody, color = ModaColors.TextDim)
                        }
                    }
                }

                if (error != null) {
                    item(key = "row-error") {
                        ErrorState(error, modifier = Modifier.height(260.dp), onRetry = onRetry)
                    }
                }
            }
        }
    }
}

/** 单行：标题 + 横向卡片；行被回收重建时用 initialCursor 把位置还原 */
@Composable
private fun HomeRowLane(
    row: HomeRow,
    rowIndex: Int,
    initialCursor: Int,
    onCursorChange: (Int) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    prefetchCids: Set<String>,
    onReachTail: () -> Unit,
    /** 第一行专用：按上键回到轮播缩略图。其余行为空，交给 Compose 的几何搜索 */
    upFocus: FocusRequester? = null,
    /** 第一行专用：首卡挂上这个 requester，作为轮播最后一张按右键的落点 */
    firstCardFocus: FocusRequester? = null,
) {
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialCursor)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(row.title)
            Spacer(Modifier.width(12.dp))
            Text("${row.items.size} 部", style = TvBody, color = ModaColors.TextDim)
        }
        // 行标题与海报之间只留 6dp：这一行的每一像素都是从轮播手里抠出来的，
        // 少一点留白就能让海报 + 剧名整块落在 540dp 高的首屏内
        Spacer(Modifier.height(6.dp))
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count = row.items.size, key = { i -> "$rowIndex-${row.items[i].cid}-${row.items[i].vid}-$i" }) { index ->
                val item: VideoItem = row.items[index]
                VideoCard(
                    item = item,
                    onClick = { onOpenDetail(item.cid, item.title) },
                    // 单行标题：两行会让行高随内容浮动，"一屏看到一整行"就无法保证
                    titleMaxLines = 1,
                    width = HOME_CARD_WIDTH,
                    // 只有首屏少数卡预取高清；其余小卡保持 /260 缩略图，省流量与时间
                    preferHiRes = rowIndex == 0 && prefetchCids.contains(item.cid),
                    modifier = Modifier
                        // 第一行按上键必须回到轮播缩略图。
                        // 不显式指定方向时 Compose 按几何位置找最近的可聚焦元素，
                        // 结果是跳到工具栏的搜索图标——用户想回横幅，光标却飞到了右上角。
                        // 属性挂在这里而不是 LazyRow 上：LazyRow 本身不是焦点目标，
                        // 导航属性只有落在真正可聚焦的卡片节点上才生效。
                        .then(
                            if (upFocus != null) {
                                // up 的属性类型就是 FocusRequester（不是 (FocusDirection) -> FocusRequester，
                                // 那个签名只属于 enter/exit），所以直接赋 requester 本身
                                Modifier.focusProperties { up = upFocus }
                            } else {
                                Modifier
                            },
                        )
                        // 首卡额外挂 requester：轮播最后一张按右键要精确落到这里
                        .then(
                            if (index == 0 && firstCardFocus != null) {
                                Modifier.focusRequester(firstCardFocus)
                            } else {
                                Modifier
                            },
                        )
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                onCursorChange(index)
                                // 最后一行扫到尾部就算「到底」，提前触发下一页
                                if (index >= row.items.lastIndex - 1) onReachTail()
                            }
                        },
                )
            }
        }
    }
}
