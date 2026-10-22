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
import androidx.compose.foundation.lazy.items
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
import com.moda.tv.model.VideoItem
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.EmptyState
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.SectionTitle
import com.moda.tv.ui.components.VideoCard
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle
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
    var error by remember { mutableStateOf<String?>(null) }
    var lastQuery by remember { mutableStateOf("") }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    // 输入框里当前轮播到的热词；输入为空时按搜索就用它
    var hintWord by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // 搜索成功后要把焦点从输入框移走，否则方向键继续被输入框吃掉，结果列表点不到
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }

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
        searching = true
        error = null
        scope.launch {
            try {
                val found = onSearch(q)
                results = found
                // 只有真的搜成功才进历史：失败/取消不该污染记录
                onRecordSearch(q)
            } catch (e: CancellationException) {
                // 页面被销毁时的取消不该显示成「搜索失败」
                throw e
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
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
                Text("搜索", style = TvTitle, color = ModaColors.Text)
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
                    text = if (searching) "搜索中…" else "搜索",
                    onClick = { submit(query.text) },
                    primary = true,
                    enabled = !searching,
                )
                Spacer(Modifier.width(12.dp))
                ActionButton(text = "返回", onClick = onBack)
            }

            Spacer(Modifier.height(22.dp))

            val shown = results
            val hint = when {
                searching -> null
                error != null -> null
                shown == null -> "输入关键词后按「搜索」确认"
                shown.isEmpty() -> "没有找到「$lastQuery」相关的内容"
                else -> "共 ${shown.size} 个结果"
            }
            if (hint != null) {
                Text(hint, style = TvCaption.copy(fontSize = 16.sp), color = ModaColors.TextDim)
                Spacer(Modifier.height(12.dp))
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    searching -> LoadingState("正在搜索「$lastQuery」…")
                    error != null -> ErrorState(message = error ?: "", onRetry = { submit(query.text) })
                    shown == null -> IdlePanel(
                        hotWords = hotWords,
                        history = history,
                        onPick = { word -> submit(word) },
                        onClearHistory = onClearHistory,
                    )
                    shown.isEmpty() -> EmptyState("换个关键词试试")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(count = shown.size, key = { i -> "${shown[i].cid}-${shown[i].vid}-$i" }) { index ->
                            val item = shown[index]
                            VideoCard(
                                item = item,
                                onClick = { onOpenDetail(item.cid, item.title) },
                                modifier = Modifier.fillMaxWidth(),
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
        EmptyState("还没有搜索内容")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 热词已经作为输入框内的灰色 hint 呈现，这里不再重复列一遍——
        // 同一批词出现两处会让人分不清哪个是提示、哪个是可选项
        if (history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("搜索历史")
                Spacer(Modifier.width(16.dp))
                ActionButton(text = "清空历史", onClick = onClearHistory)
            }
            Spacer(Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // 聚焦放大 1.08 与 3dp 描边会画到条目边界外，LazyRow 会裁掉，留 16dp 才不被切
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(history, key = { word -> "h-$word" }) { word ->
                    WordChip(text = word, onClick = { onPick(word) })
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
            SectionTitle("热词")
            Spacer(Modifier.width(12.dp))
            Text("点按直接搜索", style = TvCaption.copy(fontSize = 15.sp), color = ModaColors.TextDim)
        }
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            // hasFocus 覆盖整个子树：在条目之间移动焦点时不会误判成「焦点离开」而重启轮播
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
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
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1f),
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
            text = if (shown.isBlank()) "输入片名 / 演员…" else "搜索「$shown」",
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
                            "输入片名 / 演员…",
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
            Text("OK 搜索", style = TvCaption.copy(fontSize = 14.sp), color = Color.White.copy(alpha = 0.45f))
        }
    }
}
