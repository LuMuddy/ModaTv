package com.moda.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.moda.tv.BuildConfig
import com.moda.tv.data.Repo
import com.moda.tv.data.local.CopyrightNoticeStoreImpl
import com.moda.tv.data.local.SearchHistoryStoreImpl
import com.moda.tv.model.LibraryEntry
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.theme.ModaColors

/** 全应用唯一入口：路由栈在 TvViewModel 里，这里只按栈顶渲染 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val vm: TvViewModel = viewModel { TvViewModel(context.applicationContext) }

    // 首页按返回不直接退出应用：电视上误触返回就闪退很恼人，先弹一次确认。
    // 非首页仍然直接退一层。
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = true) {
        if (vm.stack.size > 1) {
            vm.pop()
        } else {
            showExitDialog = true
        }
    }

    // 版权声明：每次版本升级后（含首次安装）启动时自动弹一次。
    // 判据是「上次确认过的 versionCode」，不是布尔开关——升级后版本号天然不同，
    // 不需要任何「记得重置标记」的额外逻辑。
    // 初始值 false：不能默认 true，否则首次安装的用户在读完 DataStore 之前就看到了弹窗闪烁；
    // 也不能默认 true 后再关，那会让弹窗一闪而过。宁可晚一帧出现。
    val noticeStore = remember(context) {
        CopyrightNoticeStoreImpl.create(context.applicationContext)
    }
    var showCopyright by remember { mutableStateOf(false) }
    // 设置页手动打开的那一次不受版本判定约束，两条来源共用同一个弹窗
    var manualCopyright by remember { mutableStateOf(false) }
    val noticeScope = rememberCoroutineScope()
    LaunchedEffect(noticeStore) {
        // 读失败（极少数 IO 异常）就当没确认过，宁可多弹一次也不漏掉声明
        val acked = runCatching { noticeStore.acknowledgedVersion() }.getOrDefault(0)
        showCopyright = acked != BuildConfig.VERSION_CODE
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        when (val dest = vm.current) {
            is Dest.Home -> HomeRoute(vm)
            is Dest.Search -> SearchRoute(vm)
            is Dest.History -> HistoryRoute(vm)
            is Dest.Favorites -> FavoritesRoute(vm)
            is Dest.Settings -> SettingsRoute(vm, onOpenCopyright = { manualCopyright = true })
            is Dest.Detail -> DetailRoute(vm, dest)
            is Dest.Player -> PlayerRoute(vm)
        }
    }

    if (showCopyright || manualCopyright) {
        CopyrightNoticeDialog(
            onDismiss = {
                val wasUpgradePrompt = showCopyright && !manualCopyright
                showCopyright = false
                manualCopyright = false
                // 只有启动自动弹出的那次才记「已确认当前版本」；
                // 从设置页主动重看的，不该影响下次升级的提示。
                if (wasUpgradePrompt) {
                    noticeScope.launch {
                        runCatching { noticeStore.acknowledge(BuildConfig.VERSION_CODE) }
                    }
                }
            },
        )
    }

    if (showExitDialog) {
        ExitConfirmDialog(
            onConfirm = {
                showExitDialog = false
                (context as? Activity)?.finish()
            },
            onDismiss = { showExitDialog = false },
        )
    }
}

/**
 * 退出确认框。
 * 电视端的「退出」只该有一种结果：真的关掉应用，而不是回到某个后台栈。
 * 所以确认后直接 finish() 当前 Activity；取消则只是关掉弹窗，焦点回到首页。
 */
@Composable
private fun ExitConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("退出 ModaTv？", style = TextStyle(fontSize = 30.sp), color = ModaColors.Text)
            Spacer(Modifier.height(12.dp))
            Text(
                "按「确认」关闭应用，按「返回」继续观看",
                style = TextStyle(fontSize = 18.sp),
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(28.dp))
            Row {
                ActionButton(text = "取消", onClick = onDismiss, primary = true)
                Spacer(Modifier.width(16.dp))
                ActionButton(text = "确认退出", onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun HomeRoute(vm: TvViewModel) {
    HomeScreen(
        rows = vm.homeRows,
        loading = vm.homeLoading,
        error = vm.homeError,
        onRetry = vm::loadHome,
        onOpenDetail = { cid, title -> vm.push(Dest.Detail(cid, title)) },
        onOpenSearch = { vm.push(Dest.Search) },
        onOpenPlayer = { cid, vid, title, index ->
            vm.push(Dest.Player(cid, vid, title, index))
        },
        onOpenHistory = { vm.push(Dest.History) },
        onOpenFavorites = { vm.push(Dest.Favorites) },
        onOpenSettings = { vm.push(Dest.Settings) },
        carousel = vm.homeCarousel,
    )
}

/** 历史页：条目流来自 viewModelScope 里的 ViewModel，屏幕本身无状态 */
@Composable
private fun HistoryRoute(vm: TvViewModel) {
    val entries by vm.historyEntries.collectAsStateWithLifecycle()
    HistoryScreen(
        entries = entries,
        onResume = vm::resumeFromHistory,
        onDelete = vm::removeHistory,
        onClear = vm::clearHistory,
        onBack = { vm.pop() },
    )
}

// === 收藏模块（勿删）===
/**
 * 收藏页：与本文件其它 Route 一样只做「流 → 屏幕」的搬运。
 * 点条目进详情而不是直接播放——收藏是追剧语义，没有"该看哪一集"的答案。
 */
@Composable
private fun FavoritesRoute(vm: TvViewModel) {
    val entries by vm.favoriteEntries.collectAsStateWithLifecycle()
    FavoritesScreen(
        entries = entries,
        onOpen = { entry -> vm.push(Dest.Detail(entry.cid, entry.title)) },
        onDelete = vm::removeFavorite,
        onClear = vm::clearFavorites,
        onBack = { vm.pop() },
    )
}

/**
 * 设置页：当前代理网址来自 [TvViewModel.apiBase] 这条 Flow，改完立刻回灌到屏幕，
 * 所以保存之后不需要重启应用——屏幕上的地址就是正在用的地址。
 */
@Composable
private fun SettingsRoute(vm: TvViewModel, onOpenCopyright: () -> Unit) {
    val apiBase by vm.apiBase.collectAsStateWithLifecycle()
    SettingsScreen(
        apiBase = apiBase,
        onSaveApiBase = { raw -> vm.saveApiBase(raw) },
        onCommitApiBase = { raw -> vm.commitApiBase(raw) },
        onResetApiBase = { vm.resetApiBase() },
        onClearHistory = vm::clearHistory,
        onClearFavorites = vm::clearFavorites,
        onClearSearchHistory = vm::clearSearchHistory,
        onOpenCopyright = onOpenCopyright,
        onBack = { vm.pop() },
    )
}

@Composable
private fun SearchRoute(vm: TvViewModel) {
    // 搜索历史是搜索页私有的状态，不进 ViewModel：只有这一个屏幕读写，
    // 放进 VM 只会让 VM 多背一份与它其它职责无关的流。
    val context = LocalContext.current
    val historyStore = remember(context) { SearchHistoryStoreImpl.create(context.applicationContext) }
    val history by historyStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    SearchScreen(
        onSearch = { query -> Repo.search(query) },
        onOpenDetail = { cid, title -> vm.push(Dest.Detail(cid, title)) },
        onBack = { vm.pop() },
        loadHotWords = { Repo.hotWords() },
        history = history,
        onRecordSearch = { word -> scope.launch { historyStore.add(word) } },
        onClearHistory = { scope.launch { historyStore.clear() } },
    )
}

@Composable
private fun DetailRoute(vm: TvViewModel, dest: Dest.Detail) {
    // detailCid 已在 push 时切到 dest.cid，所以从收藏页点进来的第一帧拿到的就是这部剧的收藏态
    val isFavorite by vm.detailIsFavorite.collectAsStateWithLifecycle()
    DetailScreen(
        detail = vm.detail,
        loading = vm.detailLoading,
        error = vm.detailError,
        onPlay = { episode ->
            vm.push(
                Dest.Player(
                    cid = dest.cid,
                    vid = episode.vid,
                    title = dest.title,
                    episodeIndex = episode.index,
                ),
            )
        },
        onTierSelect = vm::selectDetailTier,
        selectedTier = vm.detailTier,
        onBack = { vm.pop() },
        // 用接口返回的真实剧情简介；取不到就留空让详情页隐藏该行。
        // 绝不能再拿剧名顶替——那会和标题完全重复，一眼就看出来不对
        description = vm.detail?.description.orEmpty(),
        onRetry = vm::retryDetail,
        isFavorite = isFavorite,
        onToggleFavorite = {
            val series = vm.detail ?: return@DetailScreen
            vm.toggleFavorite(
                LibraryEntry(
                    cid = series.cid,
                    vid = series.episodes.firstOrNull()?.vid.orEmpty(),
                    title = series.title,
                    // 详情页宣传图（eps.meta.pic 原图）就是收藏卡最该用的图
                    cover = series.cover,
                    episodeIndex = 0,
                    positionMs = 0,
                    durationMs = 0,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        },
    )
}

@Composable
private fun PlayerRoute(vm: TvViewModel) {
    when (val state = vm.player) {
        is PlayerUi.Loading -> LoadingState("正在准备播放…")
        is PlayerUi.Error -> ErrorState(state.message, onRetry = vm::retryPlayer)
        is PlayerUi.Ready -> PlayerScreen(
            url = state.url,
            title = state.title,
            cid = state.cid,
            episodeLabel = state.episodeLabel,
            tiers = state.tiers,
            selectedTier = state.selectedTier,
            danmaku = state.danmaku,
            startPositionMs = state.startPositionMs,
            onTierChange = vm::onTierChangeFromPlayer,
            onProgress = vm::onProgress,
            onEnded = vm::onEnded,
            onBack = {
                vm.flushHistory()
                vm.pop()
            },
            hlsUrl = state.hlsUrl,
            episodes = state.episodes,
            currentEpisodeIndex = state.episodeIndex,
            onSelectEpisode = { episode -> vm.selectEpisodeFromPlayer(state.cid, episode) },
        )
    }
}