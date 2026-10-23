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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.moda.tv.R
import com.moda.tv.model.AppError
import com.moda.tv.model.VideoItem
import com.moda.tv.ui.components.ListMetrics
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.components.gridScrollRoom
import com.moda.tv.ui.components.railScrollRoom
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.appErrorText
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.SectionTitle
import com.moda.tv.ui.components.TvGridFocusScroll
import com.moda.tv.ui.components.TvRowFocusScroll
import com.moda.tv.ui.components.VideoCard
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle
import java.io.IOException
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搜索页：进页面就把焦点压到输入框并显式拉起系统输入法。
 * 遥控器本身没有键盘，不手动 show() 的话模拟器上只是个没有光标的空框。
 *
 * 未输入时展示热词轮播 + 搜索历史；一旦有结果就只留结果网格。
 * 热词与历史都通过参数注入，屏幕自身不碰网络也不碰 DataStore：
 * 这样热词慢/挂掉只表现为「少一块」，不会把搜索页拖进错误态。
 */
@Composable
fun SearchScreen(
    onSearch: suspend (String) -> List<VideoItem>,
    onOpenDetail: (String, String) -> Unit,
    onBack: () -> Unit,
    loadHotWords: suspend () -> List<String> = { emptyList() },
    history: List<String> = emptyList(),
    onRecordSearch: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    // 用 TextFieldValue 而不是 String：需要能控制光标落在哪里。
    // 输入框原本用 String，光标永远停在 0 位置（文字最前面），
    // 用户看到的是「|醉梦」而不是「醉梦|」。
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf<List<VideoItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    /** 搜索失败只存错误码：文案在渲染处过 appErrorText，切系统语言才能跟着变 */
    var error by remember { mutableStateOf<AppError?>(null) }
    var lastQuery by remember { mutableStateOf("") }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    // 输入框里当前轮播到的热词；输入为空时按搜索就用它
    var hintWord by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // TV 的按行跟焦（见 TvGridFocusScroll）：焦点项下标进状态，滚动按"整行"接管
    val resultGridState = rememberLazyGridState()
    var resultFocusIndex by remember { mutableIntStateOf(-1) }
    TvGridFocusScroll(state = resultGridState, focusedIndex = resultFocusIndex, columns = 4)
    // 搜索成功后要把焦点从输入框移走，否则方向键继续被输入框吃掉，结果列表点不到
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    // 结果区的首焦点落点（第 0 张卡）。clearFocus() 之后必须有东西接手焦点，
    // 否则整屏没有白框、方向键还被输入框吃掉，结果网格永远进不去。
    val firstResultFocus = remember { FocusRequester() }
    // 错误态 / 空结果态里唯一的按钮落点，见 Common.kt 的 retryFocus
    val retryFocus = remember { FocusRequester() }
    // 第几次提交。落点效果的 key 必须带上它：同一个词再搜一次时结果可能一模一样，
    // 只看结果内容当 key 的话效果不重启，而这一轮里结果网格已经被 LoadingState
    // 换下去过一次——旧节点连同焦点一起消失，焦点就再也回不到第 0 张了。
    var searchTick by remember { mutableIntStateOf(0) }

    fun submit(word: String) {
        // 输入为空但热词提示存在时，用户按下搜索显然是「想搜屏幕上这个词」，
        // 直接用当前热词，而不是静默什么都不做。
        val q = word.trim().ifBlank { hintWord.trim() }
        if (q.isEmpty() || searching) return
        // 回填输入框并把光标放到末尾：用户得看得见自己搜了什么，
        // 且下一步继续输入时是往后追加，不是插在最前面。
        query = TextFieldValue(q, selection = TextRange(q.length))
        keyboard?.hide()
        // 搜索后焦点必须离开输入框，否则用户再按方向键还是被输入框吃掉，
        // 结果列表根本用遥控器点不到。
        focusManager.clearFocus()
        lastQuery = q
        searchTick++
        searching = true
        error = null
        scope.launch {
            try {
                val found = onSearch(q)
                results = found
                // 新一批结果必须从第一张开始：上一次搜索可能已经滚到第 N 行，
                // 那时第 0 张根本不在组合里，requestFocus 找不到节点（首焦点直接落空）。
                // requestScrollToItem 是非挂起版本，只登记位置请求，不会卡住这条协程。
                resultFocusIndex = -1
                resultGridState.requestScrollToItem(0)
                // 只有真的搜成功才进历史：失败/取消不该污染记录
                onRecordSearch(q)
            } catch (e: CancellationException) {
                // 页面被销毁时的取消不该显示成「搜索失败」
                throw e
            } catch (e: Exception) {
                // 屏幕层不碰 data/Api：只按 JDK 的 IOException 粗分「网络 / 未知」，
                // 更细的错误码（HTTP 状态等）在 Repo 侧已经进了日志
                error = if (e.isNetworkFailure()) AppError.Network else AppError.Unknown
                results = null
            } finally {
                searching = false
            }
        }
    }

    // 首帧还没完成布局时 requestFocus 会抛，等两帧再抢焦点，之后立刻显式拉起输入法
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { inputFocus.requestFocus() }
        keyboard?.show()
    }

    // 热词异步加载：不挂在首帧上，接口慢或挂了输入框照常可用，来了再显示
    LaunchedEffect(Unit) {
        hotWords = try {
            loadHotWords()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Repo 侧已兜底，这里只防注入的实现抛异常；失败整块不显示
            emptyList()
        }
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_title), style = TvTitle, color = ModaColors.Text)
                Spacer(Modifier.width(28.dp))
                SearchField(
                    hotWords = hotWords,
                    onHintChange = { hintWord = it },
                    value = query,
                    onValueChange = { next ->
                        query = next
                        // 清空输入就回到「未输入」：热词与历史重新出现，否则用户没有退路
                        if (next.text.isBlank()) {
                            results = null
                            error = null
                            lastQuery = ""
                        }
                    },
                    onSubmit = { submit(query.text) },
                    modifier = Modifier.weight(1f),
                    focusRequester = inputFocus,
                    onFocused = { focused -> if (focused) keyboard?.show() },
                )
                Spacer(Modifier.width(16.dp))
                ActionButton(
                    text = if (searching) {
                        stringResource(R.string.search_submitting)
                    } else {
                        stringResource(R.string.search_submit)
                    },
                    onClick = { submit(query.text) },
                    primary = true,
                    enabled = !searching,
                )
                Spacer(Modifier.width(12.dp))
                ActionButton(text = stringResource(R.string.search_back), onClick = onBack)
            }

            Spacer(Modifier.height(22.dp))

            val shown = results
            // 先落成 val 再用：error 是委托属性，when 分支里智能转换不到非空 AppError
            val shownError = error
            val hint = when {
                searching -> null
                shownError != null -> null
                shown == null -> stringResource(R.string.search_idle_hint)
                shown.isEmpty() -> stringResource(R.string.search_no_result, lastQuery)
                else -> stringResource(R.string.search_result_count, shown.size)
            }
            if (hint != null) {
                Text(hint, style = TvCaption.copy(fontSize = 16.sp), color = ModaColors.TextDim)
                Spacer(Modifier.height(12.dp))
            }

            // 这次搜索的焦点落点：有结果 → 第 0 张卡；失败 / 空结果 → 那一屏唯一的按钮。
            // key 用提交次数 searchTick：每搜一次都要重新落一次焦点，
            // 不能只看结果内容（两次搜同一个词的结果可能一模一样，效果就不重启了）。
            TvFirstFocus(
                requester = when {
                    // 搜索进行中一律不给落点：这一屏只有加载态，没有可聚焦节点。
                    // 必须显式返回 null——否则上一批结果还留在 shown 里，落点会被判定为
                    // "结果第 0 张"，而那时网格已经换成了 LoadingState，
                    // requestFocus 找不到节点、效果又已经用掉，新结果到了也不会重新落焦点。
                    searching -> null
                    shown != null && shown.isNotEmpty() -> firstResultFocus
                    shown != null || shownError != null -> retryFocus
                    else -> null
                },
                key = searchTick,
            )

            // weight(1f)：Column 里 fillMaxSize 拿到的是整列高度，结果网格视口
            // 比屏幕长出一截，末行贴着屏幕下沿被切
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searching -> LoadingState(stringResource(R.string.search_loading, lastQuery))
                    // retryFocus：失败时整屏只有这一个按钮，焦点必须先落在它上面，
                    // 否则用户看着「重试」按确认没反应（R9）
                    shownError != null -> ErrorState(
                        message = appErrorText(shownError),
                        onRetry = { submit(query.text) },
                        retryFocus = retryFocus,
                    )
                    shown == null -> IdlePanel(
                        hotWords = hotWords,
                        history = history,
                        onPick = { word -> submit(word) },
                        onClearHistory = onClearHistory,
                    )
                    // 空结果同样要有一个可聚焦落点：R9 要求空态里的按钮拿得到首焦点。
                    // 按下去 = 用同一个词重搜一次（网络抖动导致的空结果真能救回来）
                    shown.isEmpty() -> EmptyState(
                        stringResource(R.string.search_empty_retry_hint),
                        onRetry = { submit(query.text) },
                        retryFocus = retryFocus,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = resultGridState,
                        // 顶部 12dp 落在视口之内：焦点滚动把项顶到视口边时，
                        // 视口外的留白帮不上忙，首行卡片聚焦放大就会被切平
                        contentPadding = ListMetrics.gridContentPadding(top = 12.dp, side = 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        // 纵向缝隙由每项的 10dp 余量提供（见下方 VideoCard 的 modifier）
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        // gridScrollRoom：底部余量写在视口之外，末行焦点描边才不被切平
                        modifier = Modifier.fillMaxSize().gridScrollRoom(),
                    ) {
                        items(count = shown.size, key = { i -> "${shown[i].cid}-${shown[i].vid}-$i" }) { index ->
                            val item = shown[index]
                            VideoCard(
                                item = item,
                                onClick = { onOpenDetail(item.cid, item.title) },
                                // 项自己留 10dp 纵向余量：聚焦放大会向上/下溢出，
                                // 首末行贴视口时会被网格视口切平（"选中框缺一条边"）
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    // 第 0 张同时接收首焦点 requester：搜索完成后焦点直接落到这里
                                    .then(
                                        if (index == 0) Modifier.focusRequester(firstResultFocus)
                                        else Modifier
                                    )
                                    // 跟焦下标必须与 focusRequester 同一个节点上报，
                                    // 否则滚列表和焦点框会各说各话
                                    .onFocusChanged { if (it.isFocused) resultFocusIndex = index },
                                width = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 未输入时的两块内容，顺序是需求定的：热词在上、历史在下。
 * 两块都为空（热词没拉到 + 没有历史）时退回原来的空态文案。
 */
@Composable
private fun IdlePanel(
    hotWords: List<String>,
    history: List<String>,
    onPick: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (hotWords.isEmpty() && history.isEmpty()) {
        EmptyState(stringResource(R.string.search_empty_idle))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 热词已经作为输入框内的灰色 hint 呈现，这里不再重复列一遍——
        // 同一批词出现两处会让人分不清哪个是提示、哪个是可选项
        if (history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.search_history_title))
                Spacer(Modifier.width(16.dp))
                ActionButton(text = stringResource(R.string.search_clear_history), onClick = onClearHistory)
            }
            Spacer(Modifier.height(4.dp))
            // R2：这条轨道也按整格跟焦（左右连按时焦点格不会跑到视口外）
            val historyState = rememberLazyListState()
            var historyFocus by remember { mutableIntStateOf(-1) }
            TvRowFocusScroll(state = historyState, focusedIndex = historyFocus)
            LazyRow(
                state = historyState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // 纵向 12dp 给聚焦放大留余量；横向 16dp 必须写在视口之外（见 railScrollRoom），
                // 否则按「左」回到第一个词时这 16dp 已被滚掉，焦点框会贴住容器边。
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.fillMaxWidth().railScrollRoom(16.dp),
            ) {
                itemsIndexed(history, key = { _, word -> "h-$word" }) { index, word ->
                    WordChip(
                        text = word,
                        onClick = { onPick(word) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) historyFocus = index },
                    )
                }
            }
        }
    }
}

/**
 * 热词轮播：一行横向列表，每 4 秒自动前进一格并回卷。
 * 焦点落在热词区时暂停——用户正用遥控器选词，列表自己滑走等于把目标挪掉。
 */
@Composable
private fun HotWordsCarousel(
    words: List<String>,
    onPick: (String) -> Unit,
) {
    val state = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(words.size, focused) {
        if (focused || words.size <= 1) return@LaunchedEffect
        var cursor = state.firstVisibleItemIndex
        while (true) {
            delay(4_000)
            cursor = (cursor + 1) % words.size
            state.animateScrollToItem(cursor)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.search_hotword_title))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.search_hotword_tap_hint),
                style = TvCaption.copy(fontSize = 15.sp),
                color = ModaColors.TextDim,
            )
        }
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // 横向 16dp 同样必须在视口之外：这个列表还会自己 animateScrollToItem 回卷，
            // 写在 contentPadding 里等于每次自动翻页都把余量吃掉一点
            contentPadding = PaddingValues(vertical = 12.dp),
            // hasFocus 覆盖整个子树：在条目之间移动焦点时不会误判成「焦点离开」而重启轮播
            modifier = Modifier
                .fillMaxWidth()
                .railScrollRoom(16.dp)
                .onFocusChanged { focused = it.hasFocus },
        ) {
            items(words, key = { word -> "w-$word" }) { word ->
                WordChip(text = word, onClick = { onPick(word) })
            }
        }
    }
}

/** 热词 / 历史共用的可聚焦词条：遥控器上下左右都停得住，点一下直接搜 */
@Composable
private fun WordChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = 10.dp
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape = RoundedCornerShape(corner)),
        colors = CardDefaults.colors(
            containerColor = ModaColors.SurfaceHigh,
            contentColor = ModaColors.Text,
            // 白底必须配近黑字：TvBody 里烤死的 #F2F3F7 在白底上只有 1.11:1
            focusedContainerColor = ModaColors.Focus,
            focusedContentColor = ModaColors.Bg,
            pressedContainerColor = ModaColors.AccentSoft,
            pressedContentColor = Color.White,
        ),
        // 同首页 tab：LazyRow 视口会裁掉子项的放大溢出，第一个词条的左边会被切平，
        // 焦点态靠白底 + 3dp 白描边表达就够了
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(3.dp, ModaColors.Focus),
                shape = RoundedCornerShape(corner),
            ),
        ),
    ) {
        Text(
            text = text,
            style = TvBody.copy(fontSize = 20.sp),
            // 可聚焦容器里必须显式跟随内容色，见 Cards.kt 的配色规则
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

/**
 * 输入框内的热词占位提示。
 *
 * 设计取舍：热词过去是输入框下方一整块可点列表，既占纵向空间又让人分不清
 * 「这是提示还是已经输入的内容」。改成 hint 后语义清晰——空框显示灰色热词，
 * 一旦开始打字就完全让位给用户输入。每 3.5 秒换一条，带淡入淡出避免突兀。
 */
@Composable
private fun HotWordHint(words: List<String>, onCurrentChange: (String) -> Unit = {}) {
    var index by remember(words) { mutableIntStateOf(0) }
    LaunchedEffect(words) {
        if (words.size <= 1) return@LaunchedEffect
        while (true) {
            delay(3500)
            index = (index + 1) % words.size
        }
    }
    val word = words.getOrNull(index % maxOf(words.size, 1)).orEmpty()
    // 把「当前轮播到的热词」实时交给父级：输入框为空时按搜索会用它，
    // 否则用户看到提示却搜不了，按钮像是坏的。
    LaunchedEffect(word) { onCurrentChange(word) }
    Crossfade(targetState = word, animationSpec = tween(420), label = "hotword-hint") { shown ->
        Text(
            text = if (shown.isBlank()) {
                stringResource(R.string.search_hint_input)
            } else {
                stringResource(R.string.search_hint_hotword, shown)
            },
            style = TvBody.copy(fontSize = 22.sp),
            color = ModaColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** tv-material 的 Surface 做外壳，焦点反馈靠描边粗细与颜色变化 */
@Composable
private fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    /** 热词在这里当占位提示轮播：输入框为空时灰色滚动展示，用户真正输入的文字才是正文 */
    hotWords: List<String> = emptyList(),
    /** 当前轮播到的热词：输入框为空时按搜索，就用它作为搜索词 */
    onHintChange: (String) -> Unit = {},
) {
    val corner = 12.dp
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(corner),
        colors = SurfaceDefaults.colors(
            containerColor = if (focused) ModaColors.SurfaceHigh else ModaColors.Surface,
            contentColor = ModaColors.Text,
        ),
        border = Border(
            border = BorderStroke(
                width = if (focused) 3.dp else 2.dp,
                color = if (focused) ModaColors.Focus else ModaColors.SurfaceHigh,
            ),
            shape = RoundedCornerShape(corner),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.text.isEmpty()) {
                    // 热词当 hint：没有热词时退回通用提示，保证任何情况下输入框都不是空的
                    if (hotWords.isEmpty()) {
                        Text(
                            stringResource(R.string.search_hint_input),
                            style = TvBody.copy(fontSize = 22.sp),
                            color = ModaColors.TextDim,
                        )
                    } else {
                        HotWordHint(hotWords, onHintChange)
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 22.sp, color = ModaColors.Text),
                    cursorBrush = SolidColor(ModaColors.AccentSoft),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocused(it.isFocused)
                        }
                        // 单行输入框会把上下键也吃掉，焦点就永远走不出这一栏；
                        // 只截上下键做焦点转移，左右键仍留给光标移动
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                Key.DirectionCenter, Key.Enter -> {
                                    onSubmit()
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.search_ime_hint),
                style = TvCaption.copy(fontSize = 14.sp),
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

/**
 * 只按异常类型判「是不是网络掉线」，不看任何 message：
 * Repo 抛出的 ApiException 把底层 IOException 挂在 cause 上（见 Repo.asAppError）。
 * 屏幕层因此不必 import data/Api，也就不会把数据层拖进 UI。
 */
private fun Throwable.isNetworkFailure(): Boolean =
    this is IOException || cause is IOException
