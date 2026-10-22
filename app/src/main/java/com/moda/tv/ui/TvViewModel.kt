package com.moda.tv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moda.tv.data.Api
import com.moda.tv.data.Repo
import com.moda.tv.data.SaveOutcome
import com.moda.tv.data.local.ApiEndpoint
import com.moda.tv.data.local.ApiSettingsStore
import com.moda.tv.data.local.ApiSettingsStoreImpl
import com.moda.tv.data.local.FavoritesStore
import com.moda.tv.data.local.FavoritesStoreImpl
import com.moda.tv.data.local.HistoryStore
import com.moda.tv.data.local.HistoryStoreImpl
import com.moda.tv.data.local.SearchHistoryStoreImpl
import com.moda.tv.model.Danmaku
import com.moda.tv.model.Episode
import com.moda.tv.model.HomeRow
import com.moda.tv.model.LibraryEntry
import com.moda.tv.model.SeriesDetail
import com.moda.tv.model.Tier
import com.moda.tv.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 播放页的三种装配结果；Composable 只按状态渲染，不再自己判断加载时序 */
sealed interface PlayerUi {
    data object Loading : PlayerUi
    data class Error(val message: String) : PlayerUi
    data class Ready(
        val cid: String,
        val vid: String,
        val title: String,
        val episodeIndex: Int,
        val episodeLabel: String,
        val url: String,
        val hlsUrl: String?,
        val tiers: List<Tier>,
        val selectedTier: Tier?,
        val danmaku: List<Danmaku>,
        val startPositionMs: Long,
        // 选集浮层用的集数表；默认空表，不影响其它构造点
        val episodes: List<Episode> = emptyList(),
    ) : PlayerUi
}

/**
 * 详情页的历史落点：历史里有这部剧时给出那一集的 vid 与下标，没有则 [episodeIndex] = -1。
 *
 * [loaded] 用来区分「历史还没读到」和「读到了、但这剧没记录」。
 * 详情页要等它落定再决定首焦点，否则会先把焦点抢到第 1 集，
 * 历史到货后选中态和光标就对不上了。[cid] 让详情页能丢掉上一部剧的残留值。
 */
data class DetailResume(
    val cid: String = "",
    val vid: String = "",
    val episodeIndex: Int = -1,
    val loaded: Boolean = false,
)

/**
 * 页面栈 + 各屏幕的数据装载。
 * 所有网络/IO 异常都在这里落成 error 状态：屏幕层拿不到 throw，也就不会白屏或崩溃。
 */
class TvViewModel(private val app: Context) : ViewModel() {

    private val history: HistoryStore = HistoryStoreImpl.create(app)
    // ============ 收藏模块（勿删）============
    /** 收藏与历史是两套独立 DataStore（moda_favorites / moda_history），互不读写 */
    private val favorites: FavoritesStore = FavoritesStoreImpl.create(app)

    /**
     * 历史页的数据源。DataStore 的 IO 异常在 store 内部已兜成空表，
     * 这里再给个 emptyList 初值，保证首帧就是"空历史"而不是 null。
     */
    val historyEntries: StateFlow<List<LibraryEntry>> = history.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 收藏页的数据源；与 historyEntries 同构，store 内部已兜住 IO 异常并倒序 */
    val favoriteEntries: StateFlow<List<LibraryEntry>> = favorites.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前详情页被观察的那个 cid。用 StateFlow 而不是一进详情就置一次，
     * 是因为 flatMapLatest 需要能跟着 Dest.Detail 的变化切流：
     * 从收藏页点进 A、返回、再点进 B，detailIsFavorite 必须跟着换剧，不能停在 A。
     */
    private val detailCid = MutableStateFlow("")

    /**
     * 当前详情剧是否已收藏。cid 为空（没有详情）时直接给 false，
     * 不去碰 DataStore——否则从收藏页删掉当前剧时这里会白闪一下。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val detailIsFavorite: StateFlow<Boolean> = detailCid
        .flatMapLatest { cid -> if (cid.isBlank()) flowOf(false) else favorites.isFavorite(cid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 当前详情剧的历史落点。与 [detailIsFavorite] 同构，跟着 [detailCid] 切流，
     * 从收藏页点进 A、返回、再点进 B 时不会把 A 的集号带过去。
     *
     * 直接映射 [HistoryStore.entries] 而不是调一次 get()：详情页停留期间历史若被
     * 后台补封面改写，这里能跟着刷新，而 StateFlow 对相等的值不重复发射，
     * 已经落定的光标不会被无谓地重新抢一次。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val detailResume: StateFlow<DetailResume> = detailCid
        .flatMapLatest { cid ->
            if (cid.isBlank()) {
                flowOf(DetailResume())
            } else {
                history.entries.map { list ->
                    val entry = list.firstOrNull { it.cid == cid }
                    if (entry == null) {
                        DetailResume(cid = cid, loaded = true)
                    } else {
                        DetailResume(
                            cid = cid,
                            vid = entry.vid,
                            episodeIndex = entry.episodeIndex,
                            loaded = true,
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailResume())

    /** 页面栈；栈底永远是 Home，因此 size > 1 就等于"按返回键还能退一层" */
    val stack = mutableStateListOf<Dest>(Dest.Home)
    val current: Dest get() = stack.lastOrNull() ?: Dest.Home

    // ---- 首页 ----
    var homeRows by mutableStateOf<List<HomeRow>>(emptyList())
        private set
    var homeLoading by mutableStateOf(false)
        private set
    var homeError by mutableStateOf<String?>(null)
        private set
    private var homeJob: Job? = null

    /**
     * 首页横幅轮播条目（v.qq.com 官方首页推荐位）。空表 == 没有推荐位可用，
     * HomeScreen 据此回退到 rows[0].items[0]，保证首页横幅永远不空。
     * 与 homeRows 用两个独立 job：抓网页比拉行慢得多，首屏不能被它挡住。
     */
    var homeCarousel by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    private var carouselJob: Job? = null

    // ---- 详情 ----
    var detail by mutableStateOf<SeriesDetail?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var detailTier by mutableStateOf<Tier?>(null)
        private set
    private var detailJob: Job? = null

    // ---- 播放 ----
    var player by mutableStateOf<PlayerUi>(PlayerUi.Loading)
        private set
    private var playerJob: Job? = null
    /** 当前剧集的集数表，连播靠它找下一集 */
    private var episodes: List<Episode> = emptyList()
    private var playTitle: String = ""
    private var lastPosMs = 0L
    private var lastDurMs = 0L
    private var lastWriteAt = 0L

    /** 已取到的封面：cid → uri。写历史时同步读这里，绝不为了封面等网络 */
    private val covers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 正在取封面的 cid；用 Set 语义去重，抢输的调用方直接返回 */
    private val coverInFlight: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 后台补一次封面，拿到后回写。与写记录完全解耦：记录该落盘就落盘，
     * 封面晚到不会拖慢它，取不到也不会让它失败。
     */
    private fun ensureCover(cid: String) {
        if (covers.containsKey(cid) || !coverInFlight.add(cid)) return
        viewModelScope.launch {
            val cover = try {
                Repo.hiResCover(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            if (cover.isBlank()) return@launch
            covers[cid] = cover
            patchCover(cid, cover)
        }.also { job ->
            // 任务结束就释放去重位，失败后下次播放还能重试
            job.invokeOnCompletion { coverInFlight.remove(cid) }
        }
    }

    /** 只补封面字段：重新读一次记录，避免用旧快照把进度回退 */
    private suspend fun patchCover(cid: String, cover: String) {
        try {
            val now = history.get(cid) ?: return
            if (now.cover.isNotBlank()) return
            history.upsert(now.copy(cover = cover))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 补图失败无所谓，下次播放还会再试
        }
    }

    init {
        loadHome()
    }

    // ---------------- 导航 ----------------

    fun push(dest: Dest) {
        when (dest) {
            is Dest.Home -> Unit
            is Dest.Search -> stack.add(dest)
            // 历史页只是读 DataStore 的 Flow，进栈即可，不需要额外装载任务
            is Dest.History -> stack.add(dest)
            // === 收藏模块（勿删）=== 条目流由 favoriteEntries 常驻，进栈即可，无装载任务
            is Dest.Favorites -> stack.add(dest)
            // 设置页只读本地状态（版本号/设备信息/代理网址），没有网络装载任务
            is Dest.Settings -> stack.add(dest)
            is Dest.Detail -> {
                stack.add(dest)
                // === 收藏模块（勿删）=== 必须在 loadDetail 之前切 cid：详情未到也能先显示正确的收藏态
                detailCid.value = dest.cid
                loadDetail(dest)
            }
            is Dest.Player -> {
                stack.add(dest)
                startPlayer(dest)
            }
        }
    }

    /** 退一层；栈底再按返回返回 false，交给系统退出应用 */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        when (stack.removeAt(stack.lastIndex)) {
            is Dest.Player -> {
                // 播放页离开即销毁播放器，装配任务也要一起取消
                playerJob?.cancel()
                player = PlayerUi.Loading
            }
            is Dest.Detail -> {
                detailJob?.cancel()
                // === 收藏模块（勿删）=== 退详情后没有"当前剧"了，收藏态回到 false，避免下一部剧首帧闪旧值
                detailCid.value = ""
            }
            else -> Unit
        }
        return true
    }

    // ---------------- 首页 ----------------

    fun loadHome() {
        homeJob?.cancel()
        homeLoading = true
        homeError = null
        homeJob = viewModelScope.launch {
            try {
                homeRows = Repo.homeRows()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeError = e.message ?: "首页加载失败"
            } finally {
                homeLoading = false
            }
        }
        loadCarousel()
    }

    /**
     * 轮播单独装载：先让首页用已有行渲染出来，横幅随后补齐。
     * 失败一律静默（Repo.homeCarousel 已吞掉异常并返回空表），不弹错误、不阻塞首页。
     */
    private fun loadCarousel() {
        carouselJob?.cancel()
        carouselJob = viewModelScope.launch {
            homeCarousel = try {
                Repo.homeCarousel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 最后一道保险：横幅失败绝不能让首页进错误态
                emptyList()
            }
        }
    }

    // ---------------- 详情 ----------------

    private fun loadDetail(dest: Dest.Detail) {
        detailJob?.cancel()
        detail = null
        detailError = null
        detailTier = null
        detailLoading = true
        detailJob = viewModelScope.launch {
            try {
                val series = Repo.series(dest.cid)
                detail = series
                // 进详情就把最佳档位选中，免得用户先看到一屏没有高亮的清晰度
                detailTier = Repo.pickBestTier(series.tiers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detailError = e.message ?: "剧集加载失败"
            } finally {
                detailLoading = false
            }
        }
    }

    fun selectDetailTier(tier: Tier) {
        detailTier = tier
    }

    fun retryDetail() {
        (current as? Dest.Detail)?.let { loadDetail(it) }
    }

    // ---------------- 历史 ----------------

    /**
     * 一步续播：直接压播放路由，不经过详情页。
     * entry 里的 vid/episodeIndex/positionMs 本来就是同一条记录写出来的，
     * 所以 startPlayer 内部按 vid 回算出的真实集号一定等于 entry.episodeIndex，
     * resumePosition(cid, episodeIndex) 的等值判断成立，断点能被 seek 到。
     */
    fun resumeFromHistory(entry: LibraryEntry) {
        push(
            Dest.Player(
                cid = entry.cid,
                vid = entry.vid,
                title = entry.title,
                episodeIndex = entry.episodeIndex,
            ),
        )
    }

    fun removeHistory(cid: String) {
        viewModelScope.launch {
            try {
                history.remove(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 删不掉至多留一条记录，不能让历史页崩掉
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                history.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同上：清空失败不弹错、不崩溃
            }
        }
    }

    // ============ 收藏模块（勿删）开始：流与操作 ============

    /**
     * 收藏的唯一写入口。真正的去重/取反在 store 的 toggle 里做（同一把 mutex 下的读改写），
     * 这里只负责把异常收住：收藏失败至多是一次点击没生效，不该让详情页崩掉。
     */
    fun toggleFavorite(entry: LibraryEntry) {
        viewModelScope.launch {
            try {
                favorites.toggle(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同上：静默失败，UI 状态由 DataStore 的真实内容决定，不会出现假高亮
            }
        }
    }

    fun removeFavorite(cid: String) {
        viewModelScope.launch {
            try {
                favorites.remove(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 删不掉至多留一条收藏
            }
        }
    }

    /**
     * store 接口没有 clear()（存储已定稿，不改它），这里按当前快照逐条删。
     * 收藏列表规模是"用户手动点的数量"，几十条的串行删除在一帧之外完成，不需要批量接口。
     */
    fun clearFavorites() {
        viewModelScope.launch {
            try {
                favorites.entries.first().forEach { favorites.remove(it.cid) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 清空失败不弹错、不崩溃
            }
        }
    }

    // ============ 收藏模块（勿删）结束 ============

    // ---------------- 播放 ----------------

    private fun startPlayer(dest: Dest.Player) {
        playerJob?.cancel()
        player = PlayerUi.Loading
        lastPosMs = 0L
        lastDurMs = 0L
        lastWriteAt = 0L
        playerJob = viewModelScope.launch {
            try {
                // a) 集数表：连播、集标签都依赖它，必须先拿到
                val series = Repo.series(dest.cid)
                episodes = series.episodes
                // 首页"从头播放"只给 vid，集号未必对得上；以集数表里的真实下标为准，否则连播会跳集
                val episodeIndex = series.episodes.firstOrNull { it.vid == dest.vid }?.index
                    ?: dest.episodeIndex
                val title = dest.title.ifBlank { series.title }
                playTitle = series.title.ifBlank { title }
                // b) 档位 + 整片 hls
                val info = Repo.episodeInfo(dest.vid)
                // c) 没有可用档位就是错误态；硬造一个档位只会得到空直链
                val tier = Repo.pickBestTier(info.tiers)
                    ?: throw IllegalStateException("没有可用的清晰度，无法播放")
                // d) 档位换直链
                val link = Repo.playLink(tier)
                // e) 弹幕失败不能阻塞播放：Repo 内部已降级，这里再兜一层
                val danmaku = try {
                    Repo.danmaku(dest.vid, 0L, DANMAKU_WINDOW_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                player = PlayerUi.Ready(
                    cid = dest.cid,
                    vid = dest.vid,
                    title = title,
                    episodeIndex = episodeIndex,
                    episodeLabel = labelOf(episodeIndex, dest.vid),
                    url = link.url,
                    // f) 空串要让播放器走 MP4 直链，而不是拿空地址去建 HLS
                    hlsUrl = link.hlsUrl.ifBlank { null },
                    tiers = info.tiers,
                    selectedTier = tier,
                    danmaku = danmaku,
                    startPositionMs = resumePosition(dest.cid, episodeIndex),
                    episodes = series.episodes,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                player = PlayerUi.Error(e.message ?: "播放装配失败")
            }
        }
    }

    fun retryPlayer() {
        (current as? Dest.Player)?.let { startPlayer(it) }
    }

    /** 只有同一集的断点才值得续播，换集从头开始 */
    private suspend fun resumePosition(cid: String, episodeIndex: Int): Long {
        val entry = try {
            history.get(cid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (entry != null && entry.episodeIndex == episodeIndex) {
            entry.positionMs.coerceAtLeast(0L)
        } else {
            0L
        }
    }

    private fun labelOf(episodeIndex: Int, vid: String): String {
        val label = episodes.firstOrNull { it.index == episodeIndex }?.label.orEmpty()
        // 没有元数据时 label 会退化成 vid，直接展示会是一串乱码
        return if (label.isNotBlank() && label != vid) label else "第 ${episodeIndex} 集"
    }

    /**
     * 换档位：PlayerScreen 以 url 为 key 重建播放器，所以这里必须把当前进度当作
     * 新的 startPositionMs 传回去，否则切一次清晰度就回到片头。
     */
    fun changeTier(tier: Tier) {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (snapshot.selectedTier?.fno == tier.fno && snapshot.selectedTier.fn == tier.fn) return
        val resumeAt = lastPosMs.coerceAtLeast(0L)
        // 先更新选中态，OSD 立刻有反馈，旧播放流继续跑，等新地址到了再换
        player = snapshot.copy(selectedTier = tier)
        playerJob?.cancel()
        playerJob = viewModelScope.launch {
            try {
                val link = Repo.playLink(tier)
                val now = player as? PlayerUi.Ready ?: return@launch
                player = now.copy(
                    url = link.url,
                    hlsUrl = link.hlsUrl.ifBlank { null },
                    startPositionMs = resumeAt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 期间用户可能已经离开播放页，此时不能再把已销毁的播放状态写回去
                val now = player as? PlayerUi.Ready ?: return@launch
                // 新档位拿不到直链就退回上一档，别把已能播的画面弄丢
                player = now.copy(selectedTier = snapshot.selectedTier)
            }
        }
    }

    fun onTierChangeFromPlayer(tier: Tier) = changeTier(tier)

    /**
     * 播放页切集：与连播走同一条路——替换栈顶的播放路由而不是压栈，
     * 返回键仍然一步回到详情。换集一律从片头起播：resumePosition 只在
     * 历史里的 episodeIndex 与目标集一致时才给断点，换集自然得到 0。
     */
    fun selectEpisodeFromPlayer(cid: String, episode: Episode) {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (snapshot.episodeIndex == episode.index && snapshot.vid == episode.vid) return
        // 切走前把这一集的进度落盘，否则这一集的观看位置会丢
        writeHistory(snapshot)
        val dest = Dest.Player(cid, episode.vid, snapshot.title, episode.index)
        if (stack.lastOrNull() is Dest.Player) stack[stack.lastIndex] = dest else stack.add(dest)
        startPlayer(dest)
    }

    /** PlayerScreen 每几秒回调一次；这里再按 5 秒节流，避免 DataStore 被高频写 */
    fun onProgress(positionMs: Long, durationMs: Long) {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (positionMs > 0L) lastPosMs = positionMs
        if (durationMs > 0L) lastDurMs = durationMs
        val now = System.currentTimeMillis()
        if (now - lastWriteAt < WRITE_INTERVAL_MS) return
        lastWriteAt = now
        writeHistory(snapshot)
    }

    /** 退出播放页/播完一集时补一次记录，避免只留下节流窗口之前的旧进度 */
    fun flushHistory() {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (lastPosMs <= 0L) return
        writeHistory(snapshot)
    }

    private fun writeHistory(snapshot: PlayerUi.Ready) {
        // 快照在进协程前就取好：协程真正跑起来时 lastPosMs 可能已经被下一次回调改掉
        val entry = LibraryEntry(
            cid = snapshot.cid,
            vid = snapshot.vid,
            title = playTitle.ifBlank { snapshot.title },
            cover = "",
            episodeIndex = snapshot.episodeIndex,
            positionMs = lastPosMs,
            durationMs = lastDurMs,
            updatedAt = System.currentTimeMillis(),
        )
        // 新一轮播放开始前，把已知封面挂上；真正取封面放后台，不挡记录落盘
        val known = covers[snapshot.cid].orEmpty()
        viewModelScope.launch {
            try {
                history.upsert(entry.copy(cover = known))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 记录写失败不该影响播放
            }
        }
        ensureCover(snapshot.cid)
    }

    /** 自动连播；已经是最后一集就退回详情 */
    fun onEnded() {
        val snapshot = player as? PlayerUi.Ready ?: return
        // 连播会立刻覆写 player 状态，先把这一集的进度落盘
        writeHistory(snapshot)
        val next = episodes.firstOrNull { it.index == snapshot.episodeIndex + 1 }
        if (next == null) {
            pop()
            return
        }
        val dest = Dest.Player(snapshot.cid, next.vid, snapshot.title, next.index)
        // 连播不压栈：替换栈顶的播放路由，返回键仍然一步回到详情
        if (stack.lastOrNull() is Dest.Player) stack[stack.lastIndex] = dest else stack.add(dest)
        startPlayer(dest)
    }

    // ---------------- 设置 ----------------

    private val apiSettings: ApiSettingsStore = ApiSettingsStoreImpl.create(app)

    /** 设置页"当前代理网址"的数据源；store 里没存过时给出的就是 Api.DEFAULT_BASE */
    val apiBase: StateFlow<String> = apiSettings.baseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Api.BASE)

    /**
     * 校验 → 探活 → 落盘，三步都过才写。
     * 返回 null 表示已生效；否则是给用户看的原因，且此时**什么都没写**（回滚天然成立：
     * 失败路径上既没有 store 写入也没有改 [Api.BASE]，旧地址原样继续用）。
     */
    suspend fun saveApiBase(raw: String): String? = when (val r = commitApiBase(raw)) {
        is SaveOutcome.Ok -> null
        is SaveOutcome.Fail -> r.reason
    }

    /**
     * 与 [saveApiBase] 同一套校验，但返回结构化结果。
     * 局域网弹窗用它拿到"规范化后的实际地址"，从而回显真正生效的那一条。
     */
    suspend fun commitApiBase(raw: String): SaveOutcome {
        val normalized = ApiEndpoint.normalize(raw)
            ?: return SaveOutcome.Fail("地址格式不对：必须以 http:// 或 https:// 开头，且包含主机名")
        val failure = ApiEndpoint.probe(normalized)
        if (failure != null) return SaveOutcome.Fail("地址不可用：$failure")
        return try {
            apiSettings.save(normalized)
            // 先落盘再切内存值：保存失败时用户看到的仍是旧地址，不会出现"界面新、请求旧"
            Api.BASE = normalized
            SaveOutcome.Ok(normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveOutcome.Fail("保存失败：${e.message ?: "本地存储不可写"}")
        }
    }

    /** 恢复默认：删键 + 立刻注入默认地址，不需要重启 */
    suspend fun resetApiBase() {
        try {
            Api.BASE = apiSettings.reset()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 删键失败也要把内存值掰回默认，否则界面显示默认、请求还打旧地址
            Api.BASE = Api.DEFAULT_BASE
            throw e
        }
    }

    // ---------------- 数据管理 ----------------

    /** 搜索历史是搜索页私有的 store（不进 VM），设置页要清它只能自己拿一个实例 */
    fun clearSearchHistory() {
        viewModelScope.launch {
            try {
                SearchHistoryStoreImpl.create(app).clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 清空失败不弹错、不崩溃
            }
        }
    }

    private companion object {
        const val WRITE_INTERVAL_MS = 5_000L
        const val DANMAKU_WINDOW_MS = 30 * 60 * 1000L
    }
}