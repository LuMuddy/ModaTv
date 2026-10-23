package com.moda.tv.ui

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moda.tv.R
import com.moda.tv.data.Api
import com.moda.tv.data.ApiException
import com.moda.tv.data.Repo
import com.moda.tv.data.SaveOutcome
import com.moda.tv.data.local.ApiEndpoint
import com.moda.tv.data.local.ApiSettingsStore
import com.moda.tv.data.local.ApiSettingsStoreImpl
import com.moda.tv.data.local.CompletedStore
import com.moda.tv.data.local.CompletedStoreImpl
import com.moda.tv.data.local.FavoritesStore
import com.moda.tv.data.local.FavoritesStoreImpl
import com.moda.tv.data.local.HistoryStore
import com.moda.tv.data.local.HistoryStoreImpl
import com.moda.tv.data.local.PlaybackOverrideStoreImpl
import com.moda.tv.data.local.HomeCacheStore
import com.moda.tv.data.local.HomeCacheStoreImpl
import com.moda.tv.data.local.HomeSnapshot
import com.moda.tv.data.local.IntroOutroRules
import com.moda.tv.data.local.IntroOutroStore
import com.moda.tv.data.local.IntroOutroStoreImpl
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.data.local.PlayerSettingsStore
import com.moda.tv.data.local.PlayerSettingsStoreImpl
import com.moda.tv.data.local.SearchHistoryStoreImpl
import com.moda.tv.model.AppError
import com.moda.tv.model.Danmaku
import com.moda.tv.model.Episode
import com.moda.tv.model.HomeFeed
import com.moda.tv.model.HomeTab
import com.moda.tv.model.LibraryEntry
import com.moda.tv.model.SeriesDetail
import com.moda.tv.model.Tier
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 播放页的三种装配结果；Composable 只按状态渲染，不再自己判断加载时序 */
sealed interface PlayerUi {
    data object Loading : PlayerUi
    /** 装配失败只给错误码：文案由 UI 层按当前系统语言渲染（见 components/appErrorText） */
    data class Error(val error: AppError) : PlayerUi
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
 *
 * ## [positionMs] 的三条使用契约（用之前请先读完）
 *
 * 1. **[positionMs] 与 [vid] / [episodeIndex] 出自同一条历史记录，只对那一集有意义。**
 *    它不是「这部剧里任意一集的当前位置」。历史里没有这部剧时走
 *    \`DetailResume(cid, loaded = true)\` 分支，[positionMs] 保持默认 0。
 *
 * 2. **判断某集能不能续播，必须按 [vid] 匹配，不能按 [episodeIndex] 下标匹配。**
 *    集数表会随 upstream 重排（预告片插到前面、分集顺序调整），下标会漂移，
 *    vid 才是「那一集」的稳定身份。按 vid 找不到时才退而用下标兜底。
 *    另外还要校验 [loaded] 为 true 且 [cid] 等于当前详情剧的 cid，
 *    否则拿到的是上一部剧的残留值。
 *
 * 3. **[loaded] == false 表示历史还没从磁盘读到，此时不要基于 [positionMs] 做任何判断**
 *    （不要拿它决定按钮显示「继续播放」还是「播放」）。这时 [positionMs] 是 0，
 *    但 0 在这里的含义是「未知」而不是「没有断点」，据此渲染会先闪一帧错文案。
 *    详情页现有的做法是先等几帧（RESUME_WAIT_FRAMES），等不到就按「没记录」处理。
 *
 * 反例（写的时候踩过）：只判断 \`positionMs > 0\` 就显示「继续播放」，
 * 会让用户在每一集上都看到同一串时间；而真正起播时
 * \`startPlayer → resumePosition(cid, episodeIndex)\` 只会在
 * 「历史记录的 episodeIndex 恰好等于目标集」时才跳过去 ——
 * 于是显示的断点与实际起播位置对不上。
 */
data class DetailResume(
    val cid: String = "",
    val vid: String = "",
    val episodeIndex: Int = -1,
    val loaded: Boolean = false,
    /**
     * 历史里**那一集**的断点位置（与 [cid] / [vid] / [episodeIndex] 同源同一条记录）。
     * [loaded] 为 false 时它是 0，含义是「未知」而不是「没有断点」。
     */
    val positionMs: Long = 0L,
)

/**
 * 页面栈 + 各屏幕的数据装载。
 * 所有网络/IO 异常都在这里落成 error 状态：屏幕层拿不到 throw，也就不会白屏或崩溃。
 */
/**
 * 一次整页刷新的结果提示。
 *
 * [tick] 每发一条就 +1：连续两次刷新失败，文案一模一样，没有这个序号
 * UI 会把它当成「状态没变」而不重新弹出——用户第二次就再也看不到提示了。
 */
data class HomeRefreshNotice(
    val success: Boolean,
    /** 文案资源 id 而不是字符串：ViewModel 不持有任何语言，UI 用 stringResource 解析 */
    @param:StringRes val messageRes: Int,
    val tick: Int,
)

/**
 * 异常 → 错误码。全 ViewModel 只有这一处做归一。
 *
 * 屏幕层拿不到 throw，所以「失败分类」必须在这里就落到 [AppError] 上；
 * 一旦这里把 e.message 原样带出去，数据层的技术文本（甚至中文）就会被直接上屏，
 * 系统语言切到英文时又收不回来。原始异常只进日志。
 */
private fun Throwable.toAppError(): AppError = when (this) {
    is ApiException -> error
    is IOException -> AppError.Network
    else -> AppError.Unknown
}

class TvViewModel(private val app: Context) : ViewModel() {

    private val history: HistoryStore = HistoryStoreImpl.create(app)
    /** 播放页里按剧改过内核/软硬解的覆盖值；生命周期跟着观看记录走 */
    private val playbackOverrideStore = PlaybackOverrideStoreImpl.create(app)
    // ============ 收藏模块（勿删）============
    /** 收藏与历史是两套独立 DataStore（moda_favorites / moda_history），互不读写 */
    private val favorites: FavoritesStore = FavoritesStoreImpl.create(app)

    /** 片头/片尾标记；与历史联动删除，所以 VM 也要持有一个实例 */
    private val introOutro: IntroOutroStore = IntroOutroStoreImpl.create(app)

    /**
     * 已看完。与 history 是两张独立的表（moda_completed / moda_history）：
     * 需求是「看完的归已完成、不归历史记录」，写在数据层比在 UI 层过滤可靠。
     */
    private val completed: CompletedStore = CompletedStoreImpl.create(app)

    /** 首页持久化缓存：冷启动断网时它是唯一的数据来源 */
    private val homeCache: HomeCacheStore = HomeCacheStoreImpl.create(app)

    /**
     * 历史页的数据源。DataStore 的 IO 异常在 store 内部已兜成空表，
     * 这里再给个 emptyList 初值，保证首帧就是"空历史"而不是 null。
     */
    val historyEntries: StateFlow<List<LibraryEntry>> = history.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 收藏页的数据源；与 historyEntries 同构，store 内部已兜住 IO 异常并倒序 */
    val favoriteEntries: StateFlow<List<LibraryEntry>> = favorites.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 已看完页的数据源；与其他两个列表同构（store 内部已按 updatedAt 倒序） */
    val completedEntries: StateFlow<List<LibraryEntry>> = completed.entries
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
                            // 断点必须跟着集号一起出来：详情页的「继续播放 xx:xx」两者缺一不可
                            positionMs = entry.positionMs.coerceAtLeast(0L),
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailResume())

    /**
     * 当前详情剧里「已看完」的集号集合，详情页给选集网格打角标。
     *
     * 直接用 detailCid × completedEntries 组合，而不是像 completedIndexes(cid) 那样查一次：
     * 详情页停留期间用户在播放页看完一集、再返回详情，角标必须立刻出现，
     * 一次性读的快照做不到这一点（它不会跟着 DataStore 变）。
     */
    val detailCompletedIndexes: StateFlow<Set<Int>> =
        combine(detailCid, completed.entries) { cid, list ->
            if (cid.isBlank()) emptySet() else list.filter { it.cid == cid }.map { it.episodeIndex }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())


    /** 页面栈；栈底永远是 Home，因此 size > 1 就等于"按返回键还能退一层" */
    val stack = mutableStateListOf<Dest>(Dest.Home)
    val current: Dest get() = stack.lastOrNull() ?: Dest.Home

    // ---- 首页 ----

    /**
     * 当前选中的 tab。需求 12：冷启动默认选中第 0 个（推荐）。
     * 用 mutableStateOf 而不是 StateFlow：它只影响首页自身的一次重组，
     * 没必要为它引一条要 collect 的流。
     */
    var homeTab by mutableStateOf(HomeTab.Recommend)
        private set

    /**
     * 每个 tab 当前的首页流状态。用 mutableStateMapOf：UI 直接按 tab 取，
     * 读到即为快照，不需要额外的一层 StateFlow 包装。
     * 写入口只有 [applyFeed] 一处，避免散落的 put 把 fromCache 之类的标记写歪。
     */
    // 对外只读：UI 一旦能直接 put，就会有人绕过 applyFeed 写进去，
    // fromCache 这个标记随即失真。写入口只有本类内部的 [feedState]
    private val feedState = mutableStateMapOf<HomeTab, HomeFeed>()
    val homeFeeds: Map<HomeTab, HomeFeed> get() = feedState

    /**
     * 当前 tab 是否在加载。按 tab 记录而不是一个全局 boolean：
     * 用户切走的那个 tab 还在后台拉时，新 tab 的首屏不能被它的 loading 状态顶住。
     */
    private val loadingTabs = mutableStateMapOf<HomeTab, Boolean>()

    /** 当前 tab 是否在加载（UI 直接读这个，不要自己去查 loadingTabs） */
    val homeLoading: Boolean get() = loadingTabs[homeTab] == true

    /**
     * 首页错误码（文案由 UI 层用 appErrorText 渲染）。**只有「缓存也没有 + 网络也失败」时才会非 null**：
     * 缓存在的时候再把错误糊到用户脸上没有意义，他看到的本来就是能用的内容。
     */
    var homeError by mutableStateOf<AppError?>(null)
        private set

    /**
     * 整页最近一次**成功**刷新的时刻，0 表示从未刷过。
     *
     * 它同时是工具栏显示的时间和 30 分钟门禁的判据，两者必须同源：一旦分开算，
     * 就会出现「屏幕刚显示刷新过、重进首页又刷一轮」，或者反过来显示一个
     * 并不代表这一屏的假时刻。所以只有整页刷新成功才写它，单个 tab 的补拉不写。
     */
    var homeFetchedAt by mutableStateOf(0L)
        private set

    /**
     * 是否正在整页刷新；工具栏的刷新图标靠它自转。
     *
     * 不能让 UI 直接读 [pageJob]?.isActive：那是普通字段，读它不触发重组，
     * 于是用户按下刷新后屏幕上不会有任何变化——「按了没反应」就是这么来的。
     * 一轮整页刷新在电视上要 3~4 秒，同一分钟内连点两次连时间戳都不会跳。
     */
    var homeRefreshing by mutableStateOf(false)
        private set

    /**
     * 最近一次整页刷新的结果，由首页弹一条提示。
     *
     * 不清空、不设超时：看得见的生命周期归 UI（它自己 2.5 秒后忽略），
     * 这样「刷新完成」这件事不会因为用户在别的页面而错过——回到首页仍能看到。
     */
    var homeRefreshNotice by mutableStateOf<HomeRefreshNotice?>(null)
        private set
    private var refreshNoticeTick = 0

    private val homeJobs = HashMap<HomeTab, Job>()

    /** 每个 tab 当前在跑的那次装载的身份牌；用来让被取消的旧任务别乱改新任务的状态 */
    private val feedTokens = HashMap<HomeTab, Any>()

    /**
     * 整页刷新任务（19 个 tab 一起）。
     *
     * 单独一个字段而不是复用 homeJobs：它是唯一有权重入判定的任务，
     * 也唯一有权改写 [homeFeeds] 里所有 tab。非 null 且 isActive 就是"正在整页刷新"。
     */
    private var pageJob: Job? = null

    // ---- 详情 ----
    var detail by mutableStateOf<SeriesDetail?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<AppError?>(null)
        private set
    var detailTier by mutableStateOf<Tier?>(null)
        private set
    private var detailJob: Job? = null

    // ---- 播放 ----
    var player by mutableStateOf<PlayerUi>(PlayerUi.Loading)
        private set
    private var playerJob: Job? = null

    /**
     * 当前滑动窗口内的弹幕，播放页的**唯一**来源。
     * PlayerUi.Ready 里不再留快照：同一份数据两个入口，换集时旧列表还会挂在 Ready 上，
     * 屏幕就会在新一集的画面上把上一集的弹幕画出来。
     */
    var danmakuWindow by mutableStateOf<List<Danmaku>>(emptyList())
        private set
    private var danmakuJob: Job? = null
    /** 已装载窗口的起点；[DANMAKU_NOT_LOADED] 表示当前还没有可用窗口 */
    private var danmakuWindowStart = DANMAKU_NOT_LOADED
    /** 已装载窗口属于哪一集：位置可能一样，集不一样，用来拦住跨集的滞后结果 */
    private var danmakuWindowVid: String? = null

    /** 当前剧集的集数表，连播靠它找下一集 */
    private var episodes: List<Episode> = emptyList()
    private var playTitle: String = ""
    private var lastPosMs = 0L
    private var lastDurMs = 0L
    private var lastWriteAt = 0L
    /** 已经处理过「播放结束」的 vid：片尾标记与 ExoPlayer 结束事件各来一次，必须只认一次 */
    private var endedVid: String? = null

    /**
     * 本次播放会话里已经结算过「看完」的 vid。
     * 用途是闸门而不是缓存：结算之后历史里那条已经被删掉，
     * 没有这个集合的话下一帧的进度上报会立刻把它写回去。
     */
    private val completedVids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
            // 已看完页同理：completedEntries 常驻，进栈即可，无装载任务
            is Dest.Completed -> stack.add(dest)
            // === 收藏模块（勿删）=== 条目流由 favoriteEntries 常驻，进栈即可，无装载任务
            is Dest.Favorites -> stack.add(dest)
            // 设置页只读本地状态（版本号/设备信息/代理网址），没有网络装载任务
            is Dest.Settings -> stack.add(dest)
            // 设置子页同理：内容全是本地状态，进栈即渲染，无装载任务
            is Dest.SettingsGroup -> stack.add(dest)
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

    /**
     * 首页的装载入口（init 里调一次）。
     *
     * 门槛判定全部交给 [ensureHomeLoaded]，这里不自己发任何网络。
     * 旧 UI 的四行与横幅曾经在这里无条件拉，那是绕过「窗口内零网络」的正门；
     * 现在 HomeScreen 只认 feeds，这两份数据没有任何消费者，一并下线。
     */
    fun loadHome() {
        ensureHomeLoaded()
    }

    /**
     * 进入首页时的装载入口（init 里调一次）。
     *
     * 需求 9 的时序：**先读持久化缓存灌进 homeFeeds，再打网络**。
     * 顺序反过来就等于"先给用户看一屏骨架屏，再换成缓存，再换成网络结果"，
     * 电视上冷启动会闪两次；缓存先落位则首帧就有内容。
     */
    fun ensureHomeLoaded() {
        viewModelScope.launch {
            // 灌缓存与取门禁时间是同一次读盘，不拆成两次
            val (cached, pageAt) = withContext(Dispatchers.IO) {
                homeCache.loadAll() to homeCache.pageFetchedAt()
            }
            // key 是 HomeTab.name（改中文文案不会让旧缓存失联），别拿 title 去对
            cached.forEach { (key, snapshot) ->
                HomeTab.of(key)?.let { tab ->
                    // 只填还空着的 tab：已经有内容的多半是本轮刚拿回来的新数据，比盘上的旧
                    if (homeFeeds[tab] == null) applyCache(tab, snapshot)
                }
            }
            val threshold = pageAt.takeIf { it > 0 } ?: cacheThreshold(cached)
            // 冷启动首帧工具栏显示的必须是「缓存当初的时刻」，而不是 0 对应的「尚未获取」，
            // 所以整页时间要先跟盘上的值对齐。已经有人刷完一轮整页时不能用旧盘值盖回去。
            if (homeFetchedAt == 0L) homeFetchedAt = threshold
            val expired = threshold == 0L || System.currentTimeMillis() - threshold > HOME_REFRESH_INTERVAL_MS
            // 让位判断必须放在**灌完缓存之后**。
            //
            // 原先它写在函数第一行（早于读盘），于是 init 的 ensureHomeLoaded 一旦
            // 撞上 AppRoot 里 checkHomeFreshness 抢先开的 pageJob，就直接 return 了——
            // 连缓存都没灌，首屏永远是空态。这就是「首页推荐暂无内容」的根因（实测复现）。
            //
            // 现在只有「要不要再开一轮网络」这件事让位；灌缓存与状态装配无论如何都要发生。
            if (pageJob?.isActive == true) return@launch
            if (expired) {
                // 这里只开一轮网络请求，就是下面这一条 refreshAllHome。
                // 不在它前面附加任何别的抓取：Repo 的整页缓存在 refreshAllHome 里被置空，
                // 抢先抓的东西随即作废，白白多打一整轮频道页
                //
                // 门禁判定为「该刷」，上一次留下的错误文案就该让位：
                // 新数据到了会覆盖它，失败时 refreshAllHome 会按「当前 tab 是否为空」重新决定
                homeError = null
                refreshAllHome("进首页超时")
            } else if (homeFeeds[homeTab]?.items.isNullOrEmpty()) {
                // 窗口内不打网络。唯一的例外是当前 tab 一份内容都没有：
                // 缓存恰好缺这一行时，用户会对着空列表干等满 30 分钟
                homeError = null
                loadFeed(homeTab)
            }
        }
    }

    /**
     * 门禁用的「上次整页刷新时刻」：优先 pageFetchedAt，没有就回退到缓存里最旧的一份。
     *
     * 老版本升上来没有 pageFetchedAt 这个 key，直接把它当 0 会让每次升级后的
     * 第一次进首页都白刷一轮。取最小值是保守的那一侧：最旧的一份过期了就该刷。
     */
    private fun cacheThreshold(cached: Map<String, HomeSnapshot>): Long =
        cached.values.minOfOrNull { it.fetchedAt } ?: 0L

    /**
     * 进首页路由时的门禁：只判「数据是不是老过 30 分钟」，不负责首次装载。
     *
     * 与 [ensureHomeLoaded] 的分工：那个是冷启动的一次性装载（读缓存 + 补首次），
     * 这个服务于「人从别的页面回到首页」。电视应用常驻，冷启动只判一次的话，
     * 一台开着三天的电视就再也不会刷新了。
     *
     * 调用方必须放进 LaunchedEffect(Unit) 保证「进页面才调一次」：
     * 摆在每次重组都会跑的位置，等于每帧判一次门禁。
     */
    fun checkHomeFreshness() {
        if (pageJob?.isActive == true) return
        viewModelScope.launch {
            // 这里只需要整页时间，不必把 19 份快照一起读上来
            val pageAt = withContext(Dispatchers.IO) { homeCache.pageFetchedAt() }
            // 读盘期间可能已经有人开了整页任务，进网络前再核一次
            if (pageJob?.isActive == true) return@launch
            // 判据就是工具栏显示的那个整页时间：读盘值优先（进程可能刚重启过），
            // 读不到才用内存里的 [homeFetchedAt]。两个入口各用一套时间，
            // 屏幕上显示的时刻与门禁的判定就会对不上。
            val threshold = pageAt.takeIf { it > 0 } ?: homeFetchedAt
            // 门槛为 0 = 压根没有缓存：那是冷启动该管的事，这里不抢，
            // 否则两个入口会各开一轮整页任务
            if (threshold > 0 && System.currentTimeMillis() - threshold > HOME_REFRESH_INTERVAL_MS) {
                refreshAllHome("回首页超时")
            }
        }
    }

    /** 切 tab：只有这个 tab 从来没装载过（feed 还是 null）才触发首次装载 */
    fun selectHomeTab(tab: HomeTab) {
        homeTab = tab
        homeError = null
        if (homeFeeds[tab] == null) loadFeed(tab)
    }

    /**
     * 下拉 / 刷新按钮：整页刷新，19 个 tab 一起。
     *
     * 只刷当前 tab 会让「首页时间」失去统一含义：工具栏和 30 分钟门禁读的都是
     * [homeFetchedAt]，只补一个 tab 却把它推新，等于向用户谎称另外 18 个 tab 也刚更新过。
     * 按钮不判缓存时间，无条件重拉（[Repo.invalidateHomeCache] 保证不会命中旧缓存）。
     * 参数保留是为了不让既有调用点跟着改，语义上一切按整页算。
     *
     * 不清空 homeFeeds 里的旧内容：刷新失败时用户看到的必须还是刚才那一屏，
     * 而不是一个突然空掉的页面。新数据到了 [applyFeed] 会整体覆盖。
     */
    @Suppress("UNUSED_PARAMETER")
    fun refreshHome(tab: HomeTab = homeTab) {
        homeError = null
        refreshAllHome("手动刷新")
    }

    /**
     * 整页刷新：19 个 tab 并发拉第 1 页，全部落定后记一个「整页完成时刻」。
     *
     * 为什么是整页而不是当前 tab：tab 栏有 19 个，用户切到哪一个都得等一次自己的
     * 首屏；一次全刷完，之后切换都是即时出内容。并发而不是串行：19 个串行请求
     * 在电视上的等待能到十几秒，期间用户看到的全是转圈。
     *
     * 失败语义：单个 tab 失败只影响它自己（记为空表、保留旧内容），
     * **一个 tab 都没拿到**时整页不算刷新过——不写 pageFetchedAt，
     * 否则断网一次就会把门禁推到 30 分钟之后，用户再怎么重进首页也不会重试。
     */
    fun refreshAllHome(reason: String) {
        // 防重入：一轮没跑完再点一次刷新，只会让这 19 个 tab 各自被打两遍
        if (pageJob?.isActive == true) return
        // 作废 Repo 的整页缓存（10 分钟 TTL），否则这一次「刷新」会原样返回
        // 缓存里的旧数据，用户点完按钮什么都不会变。
        // 放在 launch 之外同步执行：置空只是一次原子写，而放在协程体里就会和
        // 调用方后续的抓取抢先后——一旦晚一步清，刚抓回来的新数据又被抹掉、白抓一轮
        Repo.invalidateHomeCache()
        homeRefreshing = true
        pageJob = viewModelScope.launch {
            // 只给「本来就没内容」的 tab 置 loading。
            // 已经有内容的那些一律不进 loading 态：整页刷新期间那一屏必须原样留着，
            // 用户要的是「数据换成新的」，不是「页面重来一遍」——转圈、占位、列表闪一下
            // 都是对被刷新内容的打断，而这一屏本来就还能看。
            HomeTab.entries.forEach { loadingTabs[it] = homeFeeds[it]?.items.isNullOrEmpty() }
            // 整页失败时要给用户一个具体原因，但 19 个并发分支只会得到同一句话：
            // 用原子引用保留先到的那一条，不为了一个提示去改每个分支的返回结构
            val pageError = AtomicReference<AppError?>(null)
            // 每个 tab 的 (tab, 条目, 完成时刻)；失败或为空的 tab 条目为空，不进最终结果
            val fetched = try {
                coroutineScope {
                    // 全程在 IO 上跑：Repo.homeFeed 内部虽然有网络与解析，
                    // 但 19 个 tab 的解析不该挤在 Main 上排队
                    withContext(Dispatchers.IO) {
                        // 全局并发闸门：把 tab 扩到 19 个之后必须补的一条。
                        //
                        // 不加会怎样（实测踩过）：19 个 tab 各自调用 Repo.homeFeed，
                        // 每个频道页还要再补 11~17 个 cid，合计约 300 个请求同时打出去，
                        // 后端直接回「请求过于频繁，请稍后再试」，首页一条都拿不到。
                        //
                        // 6 路是权衡值：19 个 tab 全部走完约 3~4 秒（可接受），
                        // 又远低于后端限流阈值。不要为了快再往上调。
                        val gate = Semaphore(HOME_TAB_PARALLEL)
                        HomeTab.entries.map { tab ->
                            async {
                                // 单个 tab 的异常就地吞掉：一个频道页挂了
                                // 不能让另外 18 个 tab 的数据一起丢掉
                                val items = try {
                                    gate.withPermit { Repo.homeFeed(tab) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("TvViewModel", "整页刷新「${tab.name}」异常：${e.message}")
                                    pageError.compareAndSet(null, e.toAppError())
                                    emptyList()
                                }
                                Triple(tab, items, System.currentTimeMillis())
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                // 复位必须无条件：19 个 tab 里任何一个停在 loading 上，
                // 用户切过去看到的就是一个永远转圈的空页
                HomeTab.entries.forEach { loadingTabs[it] = false }
                // 转圈图标同理，取消/异常也得停：留一个永远在转的按钮
                // 比没有反馈更糟，用户会以为这一轮永远刷不完
                homeRefreshing = false
            }
            val ok = fetched.filter { it.second.isNotEmpty() }
            // 整页「完成」以全部 tab 落定为界：谁先回来都不算刷新完成，
            // 否则一个慢频道页会让时间戳落在半成品上
            val pageAt = System.currentTimeMillis()
            val keptOld = ok.isEmpty()
            if (!keptOld) {
                // 有任意一个 tab 拿到数据就算整页刷新过：内存里的整页时间立刻更新，
                // 工具栏与门禁读的是同一个值，不能等落盘成功才算数。
                homeFetchedAt = pageAt
                // 落盘失败只该让下次冷启动多刷一轮，不影响这一次的浏览，所以不改上面的内存值
                try {
                    withContext(Dispatchers.IO) { homeCache.savePageFetchedAt(pageAt) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("TvViewModel", "整页刷新时刻落盘失败：${e.message}")
                }
            }
            ok.forEach { (tab, items, at) ->
                // 唯一写入口不变：persist 在 applyFeed 里，落盘用各 tab 自己的完成时刻
                applyFeed(
                    tab,
                    HomeFeed(
                        items = items,
                        fetchedAt = at,
                        fromCache = false,
                    ),
                )
            }
            // 每 tab 的 fetchedAt 归一到整页时刻；它只是 HomeFeed 的内部字段，
            // 工具栏与门禁都不读它，读的是 [homeFetchedAt]
            if (!keptOld) patchFetchedAt(pageAt, ok.map { it.first })
            if (keptOld) {
                // 一个都没拿到（断网 / 全空）：不更新 homeFetchedAt、不写盘、旧内容原样留着，
                // 只有当前 tab 本来就没内容时才把错误摆到用户面前
                if (homeFeeds[homeTab]?.items.isNullOrEmpty()) {
                    homeError = pageError.get() ?: AppError.Unknown
                }
                Log.w("TvViewModel", "整页刷新（$reason）没有拿到任何数据，保留旧内容")
            } else {
                homeError = null
            }
            // 提示放在最后发：此时数据、时间戳、错误态都已经落定，
            // 用户看到「刷新完成」的时候，屏幕上的内容确实已经是新的
            refreshNoticeTick += 1
            homeRefreshNotice = HomeRefreshNotice(
                success = !keptOld,
                messageRes = if (keptOld) R.string.state_refresh_failed_kept else R.string.state_refresh_done,
                tick = refreshNoticeTick,
            )
        }
    }

    /**
     * 把这一轮真正刷新成功的 tab 的 fetchedAt 统一成整页完成时刻。
     *
     * 只动 [refreshed] 里的 tab：这一轮没拿到数据、只显示缓存的那些，
     * 时间戳必须停在它自己的旧值上——把它们的时刻也一起推新，
     * 等于向用户谎称「这一屏刚刚刷新过」。
     *
     * 直接写 feedState 而不是再过一遍 [applyFeed]：applyFeed 会顺带落盘，
     * 而这里要改的只是一个 HomeFeed 内部的时间戳——为此再触发 19 次 DataStore 写入
     * 换 19 个数字，代价明显不成比例。
     * 必须 copy 出新对象：HomeFeed 是 data class，原地改字段的话
     * Compose 的快照比较会认为「没变化」，读这个字段的 UI 就不会跟着动。
     */
    private fun patchFetchedAt(at: Long, refreshed: List<HomeTab>) {
        refreshed.forEach { tab ->
            val feed = feedState[tab] ?: return@forEach
            if (feed.items.isEmpty() || feed.fetchedAt == at) return@forEach
            feedState[tab] = feed.copy(fetchedAt = at)
        }
    }

    /**
     * 单个 tab 的装载流水线：缓存优先 → 网络覆盖。
     *
     * 首页没有翻页：每个 tab 只有一屏，数据源是 v.qq.com 的推荐位 / 频道页。
     * 因此这条流水线只有一个语义——「重拉这一屏并整体覆盖」。
     */
    private fun loadFeed(tab: HomeTab) {
        homeJobs[tab]?.cancel()
        loadingTabs[tab] = true
        // 自报家门的令牌：旧任务被 cancel 后它的 finally 会晚一步跑到，
        // 那时新任务已经在跑了；不核对令牌就会把新任务的 loading 状态抹成 false，
        // 表现为「刷新中」一闪而过、下拉转圈立刻停住
        val token = Any()
        feedTokens[tab] = token
        homeJobs[tab] = viewModelScope.launch {
            try {
                // 1) 缓存优先：只有"首次装载且还没有任何内容"时才灌缓存。
                //    刷新时列表里已经有内容了，再灌一次缓存只会把用户刚看到的
                //    新内容换回旧内容，那是一次可见的倒退。
                if (homeFeeds[tab] == null) {
                    withContext(Dispatchers.IO) { homeCache.load(tab.name) }
                        ?.let { snapshot -> applyCache(tab, snapshot) }
                }

                // 2) 网络：重拉这一屏并整体覆盖
                val items = Repo.homeFeed(tab)
                if (items.isEmpty()) {
                    // 网络没拿到东西：缓存有内容就**保持原样且不报错**（需求 9「默认用老的数据」），
                    // 缓存也没有才让用户看到错误
                    if (homeFeeds[tab]?.items.isNullOrEmpty()) {
                        // 一条都没拿到 = 这一屏这次确实是空的，与「断网」区分开：
                        // 用户看到「空」与「网络错」时该做的事不一样
                        homeError = AppError.EmptyPayload
                    }
                    Log.w("TvViewModel", "首页「${tab.name}」为空")
                    return@launch
                }

                // 这里**不动 [homeFetchedAt]**：单 tab 补拉是「缓存缺这一行」的补漏，
                // 不是整页刷新；把它算作刷新会向用户谎称另外 18 个 tab 也刚刚更新过
                val feed = HomeFeed(
                    items = items,
                    fetchedAt = System.currentTimeMillis(),
                    fromCache = false,
                )
                applyFeed(tab, feed)
                homeError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 仓储层已经吞过绝大多数异常，这里是最后一道保险：宁可少一次刷新也不能崩
                Log.w("TvViewModel", "首页「${tab.name}」加载异常：${e.message}")
                if (homeFeeds[tab]?.items.isNullOrEmpty()) homeError = e.toAppError()
            } finally {
                // 只有「当前这个任务」才有资格清 loading，见上面 token 的说明
                if (feedTokens[tab] === token) loadingTabs[tab] = false
            }
        }
    }

    /** 缓存灌入：fetchedAt 用缓存里当初的旧时间，UI 才能如实显示「这是老数据」 */
    private fun applyCache(tab: HomeTab, snapshot: HomeSnapshot) {
        applyFeed(
            tab,
            HomeFeed(
                items = snapshot.items,
                fetchedAt = snapshot.fetchedAt,
                fromCache = true,
            ),
            writeCache = false,
        )
    }

    /**
     * 唯一的 homeFeeds 写入口。
     *
     * 只碰 [feedState]：「一个 tab 一条流」与旧 UI 那种「一行一个频道」不是同一个东西，
     * 早先把推荐 tab 摊成一行写回去，直接把首页砍成了一行——肉眼可见的退化。
     */
    private fun applyFeed(tab: HomeTab, feed: HomeFeed, writeCache: Boolean = true) {
        feedState[tab] = feed
        if (writeCache) persist(tab, feed)
    }

    /** 落盘失败只该让下次冷启动少一份缓存，不该影响这一次浏览 */
    private fun persist(tab: HomeTab, feed: HomeFeed) {
        if (feed.items.isEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    homeCache.save(
                        tab.name,
                        HomeSnapshot(feed.items, feed.fetchedAt),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：缓存是锦上添花
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
                detailError = e.toAppError()
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
            // 历史没了，这部剧的片头片尾也一起清掉：标记是观看数据的附属品，
            // 单独留下会变成用户看不见、也删不掉的残留
            try {
                introOutro.remove(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 历史已删成功，联动失败只是留一条标记，不影响页面
            }
            // 本剧在播放页里单独设的内核/硬软解同理：历史没了，这条覆盖就再也找不到入口，
            // 留着只会变成一份用户看不见、也删不掉的残留
            try {
                playbackOverrideStore.remove(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同上：删不掉只是多一条有界的残留（有 MAX 兜底），不影响页面
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
            // 与单条删除同一套语义：清空历史 → 片头片尾标记、按剧的播放覆盖一并清空
            try {
                introOutro.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 历史已清空，标记清理失败不阻塞用户
            }
            try {
                playbackOverrideStore.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同上
            }
        }
    }

    // ---------------- 已看完 ----------------

    /**
     * 标记「这一集看完了」的唯一入口。两种来源共用它：
     *   1) 播放进度到 95%（完成线）；
     *   2) 命中片尾自动跳（跳片尾 = 看完，用户不必真的看到最后一秒）。
     *
     * 幂等：内部按 (cid, episodeIndex) 去重覆盖，重复调用只会把 updatedAt 往后推，
     * 不会在列表里堆出第二条。播放页每秒上报一次进度，没有幂等就是一场灾难。
     *
     * 连动删历史：需求「看完的归已完成，不归历史记录」——
     * 两条记录同时存在会让同一部剧既在历史里又在已完成里，用户删了一边另一边还在。
     * 删历史放在同一把 store 之外（两个 DataStore，没法真正原子），
     * 顺序上先写已完成、再删历史：中途失败最多是历史多留一条，而不是已看完丢一条。
     */
    fun markCompleted(
        cid: String,
        vid: String,
        title: String,
        cover: String,
        episodeIndex: Int,
        durationMs: Long,
    ) {
        if (cid.isBlank()) return
        val snapshot = player as? PlayerUi.Ready
        val entry = LibraryEntry(
            cid = cid,
            vid = vid,
            title = title,
            // 封面优先用播放页已知的那张：列表页没有网络也要有图
            cover = cover.ifBlank { covers[cid].orEmpty() },
            episodeIndex = episodeIndex,
            // 完成态的条目 positionMs 直接钉在片长上：已完成页不需要再显示断点，
            // 而「看完那一集的进度」本来就是 100%
            positionMs = durationMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
            completed = true,
        )
        // 播放页还开着时，顺手把封面补齐；拿不到也不影响这次落盘
        if (entry.cover.isBlank() && snapshot != null) ensureCover(cid)
        viewModelScope.launch {
            try {
                completed.upsert(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写失败至多让这一集少一个「看完」角标，不能让播放中断
                return@launch
            }
            try {
                history.remove(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 已完成已经写进去了，历史删不掉只是多一条重复记录，不该弹错
            }
        }
    }

    fun removeCompleted(cid: String, episodeIndex: Int) {
        viewModelScope.launch {
            try {
                completed.remove(cid, episodeIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 删不掉至多留一条记录，不能让已完成页崩掉
            }
        }
    }

    fun removeCompletedSeries(cid: String) {
        viewModelScope.launch {
            try {
                completed.removeSeries(cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同上
            }
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            try {
                completed.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 清空失败不弹错、不崩溃
            }
        }
    }

    /**
     * 已完成页点条目：直接续播。
     *
     * 复用 [resumeFromHistory]——LibraryEntry 结构与历史完全一致，没必要再写一条一样的路径。
     * 唯一的差别在这里处理：已完成条的 positionMs 已经等于片长，
     * 直接带过去会让播放器一进来就 seek 到结尾、立刻触发连播。所以已看完整集时从 0 重播。
     */
    fun resumeCompleted(entry: LibraryEntry) {
        val done = entry.durationMs > 0L && entry.positionMs >= entry.durationMs * 0.95f
        if (done) {
            push(
                Dest.Player(
                    cid = entry.cid,
                    vid = entry.vid,
                    title = entry.title,
                    episodeIndex = entry.episodeIndex,
                ),
            )
            // 断点归零只在本集：startPlayer 的 resumePosition 会去历史里查断点，
            // 而这条已经不在历史里了（markCompleted 删掉了），所以天然从 0 起播。
            return
        }
        resumeFromHistory(entry)
    }

    // ---------------- 播放设置 ----------------

    private val playerSettingsStore: PlayerSettingsStore = PlayerSettingsStoreImpl.create(app)

    /**
     * 播放设置的数据源。P2-C 的播放器设置页直接 collect 它渲染控件，
     * 保存走 [updatePlayerSettings]，不需要自己碰 DataStore。
     */
    val playerSettings: StateFlow<PlayerSettings> = playerSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerSettings())

    /**
     * 保存播放设置。乐观写：调用方已经在自己的 state 里改好并渲染了，
     * 这里只负责落盘；写失败只影响下次启动读到的值，不该让设置页卡住或报错。
     */
    fun updatePlayerSettings(s: PlayerSettings) {
        viewModelScope.launch {
            try {
                playerSettingsStore.save(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：设置写不进去最多是下次启动回到旧值
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
        // 换集/重进先清空弹幕：上一集的窗口既不该显示，也不该被复用
        resetDanmakuWindow()
        player = PlayerUi.Loading
        lastPosMs = 0L
        lastDurMs = 0L
        lastWriteAt = 0L
        // 换集/重进都要重新允许「播放结束」处理：旧 vid 的去重位不能带到新的播放会话
        endedVid = null
        // 完成结算的去重位同样要清：同一集重播必须能重新判定完成
        completedVids.clear()
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
                // c) 没有可用档位就是错误态；硬造一个档位只会得到空直链。
                //    这里直接落错误态而不是抛异常：需要的只是 AppError.NoVideo 这个分类，
                //    异常文本除了进日志没有第二个去处
                val tier = Repo.pickBestTier(info.tiers)
                if (tier == null) {
                    Log.w("TvViewModel", "没有可用的清晰度，无法播放：${dest.vid}")
                    player = PlayerUi.Error(AppError.NoVideo)
                    return@launch
                }
                // d) 档位换直链
                val link = Repo.playLink(tier)
                // e) 弹幕不再等在这里。旧实现要等一整段弹幕回来才发 Ready，
                //    后端一慢起播就跟着慢（而 30 分钟区间必然被拒，等于白等一场）。
                //    现在只算一下起播位置，Ready 立刻发出，弹幕窗口随后异步装（见 ensureDanmakuWindow）。
                val startPos = resumePosition(dest.cid, episodeIndex)
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
                    startPositionMs = startPos,
                    episodes = series.episodes,
                )
                // 画面已经在路上，弹幕只是补在后面：起播路径上不存在任何弹幕 await。
                // force = true：换集后位置可能和上一集一模一样，不能靠位置判断要不要装。
                ensureDanmakuWindow(dest.vid, startPos, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 原始 message 只进日志：上屏文案由 appErrorText 按系统语言渲染
                Log.w("TvViewModel", "起播装配失败：${e.message}")
                player = PlayerUi.Error(e.toAppError())
            }
        }
    }

    fun retryPlayer() {
        (current as? Dest.Player)?.let { startPlayer(it) }
    }

    /**
     * 装载/换窗。窗口 = [pos - LEAD, pos - LEAD + LENGTH)，总长 60s：
     * 后端单次 barrage 实测 120s 内可用、150s 起整单失败，60s 稳稳落在安全区，
     * 一次 HTTP 就装满，常态下连 Repo 的切片都不需要。
     *
     * 换窗条件是「进度越过窗口起点 30s」，不是「进度离开窗口末尾」：
     * 进度每秒上报一次，按窗口边界判断就变成每秒一次请求；而剩 30s 缓冲时再换，
     * 仍远大于一次请求的实测耗时（0.4~1.0s），片尾不会出现空档。
     *
     * [force] 给换集/重试用：位置可能和上一次窗口完全一样，内容却是别的集。
     */
    private fun ensureDanmakuWindow(vid: String, positionMs: Long, force: Boolean = false) {
        val pos = positionMs.coerceAtLeast(0L)
        // 起点不能为负（后端不接受负区间）：片头 15 秒内窗口自然收缩成 [0, pos+45s)
        val start = (pos - DANMAKU_LEAD_MS).coerceAtLeast(0L)
        val reusable = !force &&
            danmakuWindowVid == vid &&
            danmakuWindowStart != DANMAKU_NOT_LOADED &&
            pos >= danmakuWindowStart &&
            pos <= danmakuWindowStart + DANMAKU_REFRESH_WITHIN_MS
        if (reusable) return
        // 新窗口开始就取消上一片的请求：连按 seek 不该积一堆没人要的结果
        danmakuJob?.cancel()
        danmakuWindowVid = vid
        danmakuWindowStart = start
        danmakuJob = viewModelScope.launch {
            val list = try {
                Repo.danmaku(vid, start, start + DANMAKU_LENGTH_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Repo 内部已降级，这层只保证异常不会逃出协程
                emptyList()
            }
            // 结果回来时这一片可能已经不是当前窗口（用户 seek 走了）：丢掉结果，
            // 否则上一段位置的弹幕会飘进当前画面。
            if (danmakuWindowVid != vid || danmakuWindowStart != start) return@launch
            // 上一集的弹幕不许飘到这一集：播放状态必须还是同一个 vid
            if ((player as? PlayerUi.Ready)?.vid != vid) return@launch
            // 空列表同样要赋值：本窗口确实没有弹幕，留着旧值反而会画出已经播过的那一段
            danmakuWindow = list
        }
    }

    /** 清空窗口状态。换集 / 重试 / 重进播放页这些会话边界都要走这里 */
    private fun resetDanmakuWindow() {
        danmakuJob?.cancel()
        danmakuJob = null
        danmakuWindow = emptyList()
        danmakuWindowVid = null
        danmakuWindowStart = DANMAKU_NOT_LOADED
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
        // 兜底句走资源：它是固定文案，不属于接口元数据，藏成中文字面量会让英文系统露出破绽
        return if (label.isNotBlank() && label != vid) {
            label
        } else {
            app.getString(R.string.state_episode_index, episodeIndex)
        }
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

    /**
     * PlayerScreen 每几秒回调一次；这里再按 5 秒节流，避免 DataStore 被高频写。
     *
     * 这里是**完成判定的唯一落点**（需求 1「看到 95% 就标记为已经看完」）：
     * 播放器只负责上报进度，不自己判断「看完没看完」——否则 95% 与跳片尾两条来源
     * 会各自写一遍已完成，去重与连动删历史就得在播放器里再实现一次。
     */
    fun onProgress(positionMs: Long, durationMs: Long) {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (positionMs > 0L) lastPosMs = positionMs
        if (durationMs > 0L) lastDurMs = durationMs
        // 弹幕窗口只由这里驱动（进度每秒上报一次，seek 后的第一次上报也走它），
        // 不另起轮询。必须放在下面几条提前 return 之前：完成结算与去重那两条
        // 会直接 return，窗口一旦被它们挡住就会钉死在旧位置。
        ensureDanmakuWindow(snapshot.vid, lastPosMs)

        // 完成线一过就立刻结算，不等 5 秒节流窗口：这一瞬间之后就不再写历史了，
        // 拖到下一个窗口可能永远等不到（用户正好在 95% 处退出播放页）
        if (IntroOutroRules.isCompleted(lastPosMs, lastDurMs)) {
            markCompletedByProgress(snapshot)
            return
        }
        // 已结算过的这一集不许再写回历史：markCompleted 刚把它从历史里删掉，
        // 下一次上报又写回去，用户就会看到「已看完的剧又出现在历史里」并在两页之间反复横跳
        if (completedVids.contains(snapshot.vid)) return

        val now = System.currentTimeMillis()
        if (now - lastWriteAt < WRITE_INTERVAL_MS) return
        lastWriteAt = now
        writeHistory(snapshot)
    }

    /** 退出播放页/播完一集时补一次记录，避免只留下节流窗口之前的旧进度 */
    fun flushHistory() {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (lastPosMs <= 0L) return
        // 与 onProgress 同一条闸门：已结算的集不该在退出时又被写回历史
        if (completedVids.contains(snapshot.vid)) return
        if (IntroOutroRules.isCompleted(lastPosMs, lastDurMs)) {
            markCompletedByProgress(snapshot)
            return
        }
        writeHistory(snapshot)
    }

    /**
     * 进度达标的结算。同一个 vid 只结算一次（见 [completedVids]），
     * 播放器每秒一次的上报不会在这里堆出几十次写盘。
     */
    private fun markCompletedByProgress(snapshot: PlayerUi.Ready) {
        if (!completedVids.add(snapshot.vid)) return
        Log.i("TvViewModel", "达到完成线：${snapshot.title} 第 ${snapshot.episodeIndex + 1} 集")
        markCompleted(
            cid = snapshot.cid,
            vid = snapshot.vid,
            title = playTitle.ifBlank { snapshot.title },
            cover = covers[snapshot.cid].orEmpty(),
            episodeIndex = snapshot.episodeIndex,
            durationMs = lastDurMs,
        )
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

    /**
     * 自动连播；已经是最后一集就退回进入播放页之前的那一页。
     *
     * 去重是必须的：片尾标记命中会主动调一次 onEnded，ExoPlayer 真播完又会回调一次，
     * 两次都放行的话同一集会被连跳两集（最后一集则会连 pop 两层）。
     * 判据用「同一个 vid 只处理一次播放结束」——换集后 vid 变了，闸门自然打开。
     */
    fun onEnded() {
        val snapshot = player as? PlayerUi.Ready ?: return
        if (endedVid == snapshot.vid) return
        endedVid = snapshot.vid
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
     *
     * [SaveOutcome.Fail] 携带的仍是**本地化好的句子**而不是 [AppError]：这条 reason
     * 会被局域网页面直接拼进手机端 HTML，而 [AppError] 里没有承载「输入校验失败」的类别。
     * 所以三处失败原因一律取 string 资源——不写字面量，界面与手机页面才会同时有中英两份。
     */
    suspend fun commitApiBase(raw: String): SaveOutcome {
        val normalized = ApiEndpoint.normalize(raw)
            ?: return SaveOutcome.Fail(app.getString(R.string.state_settings_url_format))
        val failure = ApiEndpoint.probe(normalized)
        if (failure != null) {
            // probe 只带资源 id（数据层不持文案），句子在这里按当前语言取；
            // detail 是 HTTP 状态码 / okhttp 原文这类技术信息，只填进 %1$s 占位符
            val reason = failure.detail
                ?.let { app.getString(failure.messageRes, it) }
                ?: app.getString(failure.messageRes)
            return SaveOutcome.Fail(app.getString(R.string.state_settings_url_unreachable, reason))
        }
        return try {
            apiSettings.save(normalized)
            // 先落盘再切内存值：保存失败时用户看到的仍是旧地址，不会出现"界面新、请求旧"
            Api.BASE = normalized
            SaveOutcome.Ok(normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TvViewModel", "代理网址落盘失败：${e.message}")
            val reason = app.getString(R.string.state_settings_storage_unwritable)
            SaveOutcome.Fail(app.getString(R.string.state_settings_save_failed, reason))
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

        /** 「还没有可用窗口」的哨兵。0 是合法起点（片头），不能拿它表示未装载 */
        const val DANMAKU_NOT_LOADED = -1L

        /**
         * 窗口往前带 15s：滚动弹幕在屏寿命 8s 出头（DanmakuOverlay 的 ScrollDurationMs），
         * 15s 足够把「刚滚过去、屏幕上还看得见」的那几条一起带进来，换窗时画面不会凭空少一截。
         */
        const val DANMAKU_LEAD_MS = 15_000L

        /**
         * 窗口总长 60s。后端实测 120s 内可用、150s 起失败，60s 留足余量；
         * 同时小于 Repo 的单片上限（90s），所以常态下一次请求就是一片。
         */
        const val DANMAKU_LENGTH_MS = 60_000L

        /**
         * 换窗阈值：进度还在「窗口起点 + 30s」以内就继续用旧窗口。
         * 此时窗口里还剩 30s 内容没用上，远大于一次请求的实测耗时（0.4~1.0s）；
         * 若改成按窗口末尾判断，进度每秒上报一次就会变成每秒一次 HTTP。
         */
        const val DANMAKU_REFRESH_WITHIN_MS = 30_000L

        /** 整页刷新时同时最多几个 tab：见 refreshAllHome 的说明，别调大 */
        const val HOME_TAB_PARALLEL = 6

        /**
         * 进首页的整页数据保鲜窗口：超过它才允许打网络，窗口内一律用缓存。
         * 用户需求：缓存超过 30 分钟才自动拉新。
         */
        const val HOME_REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }
}