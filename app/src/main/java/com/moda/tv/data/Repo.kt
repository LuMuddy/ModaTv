package com.moda.tv.data

import android.util.Log
import com.moda.tv.BuildConfig
import com.moda.tv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 业务编排层（数据侧唯一门面）。UI 与播放器只调这里，不直接碰 Api。
 * 这里的签名是跨模块契约：实现可以改，签名不要改。
 */
object Repo {

    private const val TAG = "Repo"

    /** meta 单批上限：接口对 ids 长度敏感，app.js 也是 30 */
    private const val META_BATCH = 30

    /**
     * hls_url 带 IP 与设备签名，腾讯侧有效期只有十几分钟；缓存太久会让每次起播都先撞一次
     * 失效地址再回退，用户白等一次失败。20 分钟保守覆盖正常观看，又能在过期前主动放弃。
     */
    private const val HLS_TTL_MS = 20 * 60_000L

    /**
     * guid → 整片 m3u8 + 写入时刻。Tier 只带 fn，播放阶段拿不到 vid 也就再拉不到 info，
     * 所以只能在 episodeInfo 拿到 info JSON 时按 guid 存下来给 playLinkSeg 用。
     */
    private data class HlsEntry(val url: String, val at: Long)

    private val hlsByGuid = java.util.concurrent.ConcurrentHashMap<String, HlsEntry>()

    /**
     * 运行期档位黑名单：fn → 记入时刻。
     * fn 里带 guid/style/ext，全局唯一且跨剧集稳定，所以直接拿它当指纹。
     *
     * 取舍：接口说 avail=true 只代表「理论上该档位存在」，实测仍可能取不到 vkey
     * （版权抽风 / 老片片源下线）。这种档位继续留在列表里，用户点一次就报一次错，
     * 所以一旦失败就拉黑、从列表消失。
     *
     * 但黑名单必须带 TTL（10 分钟）：失败绝大多数是网络抖动或 vkey 服务瞬时 5xx，
     * 若永久拉黑，一次抖动就会让某个档位在整个进程生命周期内消失，用户再也选不回来。
     * 10 分钟足够覆盖一次连续观看，又远短于用户的耐心窗口，过期后重新信任该档位自动复现。
     */
    private const val TIER_BLACK_TTL_MS = 10 * 60_000L

    private val tierBlacklist = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 记一次「实测取不到」；并发写同 key 无害，后写覆盖先写只是把 TTL 往后顺延 */
    private fun blacklistTier(tier: Tier) {
        val fn = tier.fn
        if (fn.isBlank()) return
        tierBlacklist[fn] = System.currentTimeMillis()
    }

    /** 是否仍在黑名单有效期内；过期的条目顺手移除，集合因此不会无界增长 */
    private fun isTierBlacklisted(tier: Tier): Boolean {
        val fn = tier.fn
        if (fn.isBlank()) return false
        val at = tierBlacklist[fn] ?: return false
        if (System.currentTimeMillis() - at > TIER_BLACK_TTL_MS) {
            tierBlacklist.remove(fn)
            return false
        }
        return true
    }

    /** 剔掉实测取不到的档位（TTL 内的） */
    private fun withoutBlacklisted(tiers: List<Tier>): List<Tier> =
        tiers.filter { !isTierBlacklisted(it) }

    // ---------- 高清封面 ----------

    /**
     * 搜索接口给的地址是缩略图：`.../<id>/260`，实测 260x364 / 约 35KB。
     * 尺寸段不能直接改大——/400 /540 /720 /1080 全部 HTTP 400，只有 /0 与 /260 是有效路由。
     * 原图在 /0（实测 770x1080 / 约 214KB，HTTP 200），所以高清封面只能靠它。
     *
     * 缓存 raw uri 字符串即可：调用方拼 URL 时只做一次字符串操作，比缓存 Bitmap 省事，
     * 也不会和 RemoteImage 自己的 LRU 抢内存。
     */
    private data class CoverEntry(val uri: String, val at: Long)

    /**
     * 高清封面不设 TTL 那么严格：封面地址由 cid + 时间戳构成，换海报才换地址，
     * 不会像 hls_url 那样带时效签名。30 分钟的窗口足够覆盖一次首页浏览，
     * 又能让「换了海报的长剧」在下次冷启动后重新取到新图。
     */
    private const val COVER_TTL_MS = 30 * 60_000L

    /** cid → 高清封面 uri；取值失败也会记成空串，避免同一 cid 反复打接口 */
    private val coverByCid = java.util.concurrent.ConcurrentHashMap<String, CoverEntry>()

    /** 记录高清封面请求失败、避免对同一张卡反复发请求的冷却窗口 */
    private const val COVER_MISS_TTL_MS = 10 * 60_000L

    /**
     * 取一张封面可用的最高清地址。三级回退由调用方（RemoteImage）完成，
     * 这里只负责「能不能拿到比 /260 更好的那一档」：
     *   1. eps 的 meta.pic（原图 /0）
     *   2. 拿不到就返回空串，调用方退回 item.cover（/260）
     *
     * 取舍：eps 会返回整部剧的全部 vids，是重接口。所以只有横幅 hero 和首屏少量卡片
     * 会调它预取；小尺寸卡片本来就用 /260 显示，放大倍数不到 1x，不需要高清图。
     * 大图才需要高清，小卡用缩略图省流量与时间——这个取舍是故意的，不是遗漏。
     */
    suspend fun hiResCover(cid: String): String {
        if (cid.isBlank()) return ""
        val now = System.currentTimeMillis()
        coverByCid[cid]?.let { e ->
            if (now - e.at <= (if (e.uri.isBlank()) COVER_MISS_TTL_MS else COVER_TTL_MS)) return e.uri
            coverByCid.remove(cid)
        }
        val uri = try {
            // 只读 eps 的 meta.pic：Parser 不在本次修复的边界内，解析就地写在这里，
            // 顺带把 /260 之外的非原图尺寸段统一收敛成 /0（见上面 hiRes 的说明）
            Api.eps(cid).optJSONObject("meta")?.optString("pic").orEmpty()
        } catch (e: Exception) {
            // 高清图是「更好看」而不是「能看」，失败一律静默降级到 /260，不能影响首页
            Log.w(TAG, "高清封面获取失败（$cid）：${e.message}")
            ""
        }
        coverByCid[cid] = CoverEntry(uri, now)
        return uri
    }

    /**
     * 推荐 tab 展示的条数。
     *
     * 实测 v.qq.com 首页只暴露 14 个推荐位（/channel/choice 精选页与它完全重合），
     * 这就是推荐位的真实供给上限。写 20 是留余量——腾讯某天加位能自动吃进来，
     * 但今天实际拿到的仍是 14 条。
     */
    const val RECOMMEND_SIZE = 20

    /**
     * 首页每个 tab 的一屏数据。
     *
     * **首页的数据源有且只有 v.qq.com**：推荐 tab 走首页推荐位，其余 tab 走各自的频道页。
     * 自研后端的 search 接口不参与首页——它搜的是「热门」「电视剧」这几个字本身，
     * 拿到的只是标题里含该词的结果，既不是榜单也不是分类，混进首页就是把别处的数据
     * 伪装成首页内容。这也意味着首页没有第 2 页：v.qq.com 的推荐位与频道页都没有
     * 可解析的分页结构（/channel/xxx?page=2 实测返回同一批内容）。
     *
     * **这是首页唯一的取数入口**：19 个 tab 全部经由这里，没有第二套抓取逻辑——
     * 两套并存的代价是「缓存优先」要写两遍，而两套里必然有一套先腐坏。
     *
     * 失败一律返回 emptyList（异常在下面各处已落地，绝不向 UI 抛）：
     * 「抓不到」这件事由上层用持久化缓存顶上，Repo 不替它做决定。
     */
    suspend fun homeFeed(tab: HomeTab): List<VideoItem> = when (tab) {
        HomeTab.Recommend -> recommendFeed()
        else -> channelFeed(tab)
    }

    /**
     * 推荐 tab：取官方首页推荐位的前 [RECOMMEND_SIZE] 条。20 只是留给自己改代码的余量，
     * 页面实际只给 14 个位（见 [RECOMMEND_SIZE]），take() 今天等于全取。
     *
     * 推荐位抓不到就返回空表，交由上层用持久化缓存顶上——**没有**搜索兜底。
     * 首页只能有一个来源：[CarouselSource]。搜索热词不属于「首页推荐位」，
     * 拿它顶上来，用户看到的是一屏搜「热门」的结果，而不是腾讯编辑的推荐。
     */
    private suspend fun recommendFeed(): List<VideoItem> = try {
        homeCarousel().take(RECOMMEND_SIZE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "推荐位失败：${e.message}")
        emptyList()
    }

    /** HomeTab → 频道页；Recommend 没有对应频道，返回 null */
    private fun channelOf(tab: HomeTab): ChannelSource.Channel? = when (tab) {
        HomeTab.Recommend -> null
        HomeTab.Tv -> ChannelSource.Channel.TV
        HomeTab.Movie -> ChannelSource.Channel.MOVIE
        HomeTab.Variety -> ChannelSource.Channel.VARIETY
        HomeTab.Cartoon -> ChannelSource.Channel.CARTOON
        HomeTab.Child -> ChannelSource.Channel.CHILD
        HomeTab.Doco -> ChannelSource.Channel.DOCO
        HomeTab.Choice -> ChannelSource.Channel.CHOICE
        HomeTab.Music -> ChannelSource.Channel.MUSIC
        HomeTab.Sports -> ChannelSource.Channel.SPORTS
        HomeTab.Nba -> ChannelSource.Channel.NBA
        HomeTab.Game -> ChannelSource.Channel.GAME
        HomeTab.Auto -> ChannelSource.Channel.AUTO
        HomeTab.Fashion -> ChannelSource.Channel.FASHION
        HomeTab.Life -> ChannelSource.Channel.LIFE
        HomeTab.Art -> ChannelSource.Channel.ART
        HomeTab.Pet -> ChannelSource.Channel.PET
        HomeTab.Podcast -> ChannelSource.Channel.PODCAST
        HomeTab.FreeList -> ChannelSource.Channel.FREE_LIST
    }

    /**
     * 频道 tab：该频道在 v.qq.com 频道页上的真实分类推荐位。
     *
     * 频道页没有可解析的分页结构（/channel/xxx?page=2 实测返回同一批内容），
     * 所以这里只给一屏；频道页挂了就是空表，由上层用持久化缓存顶上。
     */
    private suspend fun channelFeed(tab: HomeTab): List<VideoItem> {
        val channel = channelOf(tab) ?: return emptyList()
        return channelRows()[channel].orEmpty()
    }

    /**
     * 整页全部频道页的抓取结果，成功缓存 10 分钟、全空只缓存 1 分钟（断网恢复后能自愈）。
     *
     * 缓存的是「整页所有频道」而不是单个频道：ChannelSource.loadAll 一次就把全部频道页
     * 连同上百个 cid 的补全都做完了，按 tab 分别缓存等于把同一份工作做十九遍。
     * 用户从「电视剧」切到「电影」时应当立刻有内容，而不是再等一次频道页抓取。
     */
    private const val CHANNEL_TTL_MS = 10 * 60_000L
    private const val CHANNEL_MISS_TTL_MS = 60_000L

    private data class ChannelEntry(
        val rows: Map<ChannelSource.Channel, List<VideoItem>>,
        val at: Long,
    )

    private val channelCache = java.util.concurrent.atomic.AtomicReference<ChannelEntry?>(null)

    private val channelLock = Mutex()

    private fun channelTtl(entry: ChannelEntry) =
        if (entry.rows.values.all { it.isEmpty() }) CHANNEL_MISS_TTL_MS else CHANNEL_TTL_MS

    private suspend fun channelRows(): Map<ChannelSource.Channel, List<VideoItem>> {
        channelCache.get()?.let { e ->
            if (System.currentTimeMillis() - e.at <= channelTtl(e)) return e.rows
        }
        return channelLock.withLock {
            // 等锁期间可能已被别人拉完，再查一次避免重复抓
            val again = channelCache.get()
            if (again != null && System.currentTimeMillis() - again.at <= channelTtl(again)) {
                return@withLock again.rows
            }
            val rows = try {
                ChannelSource.loadAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ChannelSource 内部已逐频道兜底，这里是最后一道保险
                Log.w(TAG, "频道页加载异常：${e.message}")
                emptyList()
            }
            val entry = ChannelEntry(rows.associate { it.channel to it.items }, System.currentTimeMillis())
            // 一个频道都没拿到也照写缓存（走 60 秒的短 TTL），否则断网时每次切 tab 都要重抓一遍
            channelCache.set(entry)
            entry.rows
        }
    }

    // ---------- 首页轮播 ----------

    private data class CarouselEntry(val items: List<VideoItem>, val at: Long)

    /**
     * 推荐位成功结果缓存 10 分钟：首页刷新/返回都会重新拉整页，没必要每次都去敲 v.qq.com。
     * 空结果只缓存 1 分钟——那样失败后下一次进首页还有机会自愈，
     * 但也不会在断网时被每次刷新反复拖慢。
     */
    private const val CAROUSEL_TTL_MS = 10 * 60_000L
    private const val CAROUSEL_MISS_TTL_MS = 60_000L

    private val carouselCache = java.util.concurrent.atomic.AtomicReference<CarouselEntry?>(null)

    /** 同一时刻只允许一次抓取：首屏与重试并发进来时不重复打首页 */
    private val carouselLock = Mutex()

    /**
     * 首页推荐位条目（v.qq.com 首页）。语义上等同"可能为空"：
     * 抓取/解析/补全任一环节整体失败都返回 emptyList，由 ViewModel 用持久化缓存顶上，
     * 没有横幅回退这类第二来源。这里不抛异常是契约的一部分——抓不到不能让整个首页报错。
     */
    suspend fun homeCarousel(): List<VideoItem> {
        val now = System.currentTimeMillis()
        carouselCache.get()?.let { e ->
            val ttl = if (e.items.isEmpty()) CAROUSEL_MISS_TTL_MS else CAROUSEL_TTL_MS
            if (now - e.at <= ttl) return e.items
        }
        return carouselLock.withLock {
            // 等锁期间可能已被别人拉完，再查一次缓存避免重复抓
            val again = carouselCache.get()
            if (again != null && System.currentTimeMillis() - again.at <=
                (if (again.items.isEmpty()) CAROUSEL_MISS_TTL_MS else CAROUSEL_TTL_MS)
            ) {
                return@withLock again.items
            }
            val items = try {
                CarouselSource.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // CarouselSource 内部已兜底，这里是最后一道保险
                Log.w(TAG, "首页轮播加载异常：${e.message}")
                emptyList()
            }
            carouselCache.set(CarouselEntry(items, System.currentTimeMillis()))
            items
        }
    }

    /**
     * 让下一次抓取真正打网络：把两张整页缓存置空即可。
     *
     * 为什么不给 [homeFeed] 加一个 force 参数：手动刷新是 19 个 tab 并发进来，
     * 每个 tab 都带着 force 就会各自把 channelRows() 走到底，也就是并发打 19 次频道页。
     * 整页缓存存在的意义正是「这 19 个 tab 共用同一份抓取结果」，force 参数恰好绕过了它。
     * 置空之后：第一个进来的重新抓、其余在 [channelLock] 里排队，等锁释放后自然复用
     * 它刚写回的缓存，整页仍然只抓一次——既刷新了数据，也没有把并发变成 19 倍流量。
     *
     * 只碰整页缓存。hlsByGuid / tierBlacklist / coverByCid 是播放与封面用的，
     * 与「首页整页刷新」无关，清掉它们只会让用户白等一次重新解析。
     */
    fun invalidateHomeCache() {
        channelCache.set(null)
        carouselCache.set(null)
    }

    // ---------- 搜索热词 ----------

    /**
     * 腾讯热词接口（pbaccess）没有对应的 Api action，只能直连。
     * 这里复用 Api.http 而不是新建 OkHttpClient：每个 client 自带连接池与线程池，
     * 为一次热词请求多养一套纯属浪费，超时策略也保持全应用一致。
     */
    private val hotWordsUrl: String
        get() = "https://pbaccess.video.qq.com/trpc.universal_backend_service.hot_word_info" +
            ".HttpHotWordRecall/GetHotWords?appID=" + BuildConfig.HOT_WORDS_APP_ID +
            "&appKey=" + BuildConfig.HOT_WORDS_APP_KEY +
            "&platform=2&channelID=0&v=2987741"

    private data class HotWordsEntry(val words: List<String>, val at: Long)

    /**
     * 成功结果缓存 10 分钟：热词是运营位，变化粒度以小时计，
     * 每次进搜索页都去打一次腾讯接口既慢又没必要。
     * 空结果只缓存 60 秒——断网/接口抽风不该把搜索页永久钉成"没有热词"。
     */
    private const val HOT_WORDS_TTL_MS = 10 * 60_000L
    private const val HOT_WORDS_MISS_TTL_MS = 60_000L

    private val hotWordsCache = java.util.concurrent.atomic.AtomicReference<HotWordsEntry?>(null)

    /** 同一时刻只允许一次抓取：搜索页反复进出时不重复打接口 */
    private val hotWordsLock = Mutex()

    /**
     * 搜索页热词。语义上等同"可能为空"：请求/HTTP/解析任一环节失败都返回 emptyList，
     * 调用方据此整块隐藏热词区。不抛异常是契约的一部分——热词挂掉只该少一个区块，
     * 不能让搜索页显示错误，更不能拖慢输入框拿焦点。
     */
    suspend fun hotWords(): List<String> {
        val now = System.currentTimeMillis()
        hotWordsCache.get()?.let { e ->
            val ttl = if (e.words.isEmpty()) HOT_WORDS_MISS_TTL_MS else HOT_WORDS_TTL_MS
            if (now - e.at <= ttl) return e.words
        }
        return hotWordsLock.withLock {
            // 等锁期间可能已被别人拉完，再查一次避免重复请求
            val again = hotWordsCache.get()
            if (again != null && System.currentTimeMillis() - again.at <=
                (if (again.words.isEmpty()) HOT_WORDS_MISS_TTL_MS else HOT_WORDS_TTL_MS)
            ) {
                return@withLock again.words
            }
            val words = try {
                fetchHotWords()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "热词拉取失败：${e.message}")
                emptyList()
            }
            hotWordsCache.set(HotWordsEntry(words, System.currentTimeMillis()))
            words
        }
    }

    /** 解析 data.hotWordList[].searchWord；subTitle 实测全为空，不参与展示 */
    private suspend fun fetchHotWords(): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(hotWordsUrl)
            // 与实测可用的参考请求保持同款头：腾讯侧对非常规 UA 有风控，缺省 UA 不保证长期可通
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://v.qq.com/")
            .get()
            .build()
        val text = Api.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("热词接口 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (text.isBlank()) throw ApiException("热词接口为空")
        val list = org.json.JSONObject(text)
            .optJSONObject("data")
            ?.optJSONArray("hotWordList")
            ?: return@withContext emptyList()
        val out = ArrayList<String>(list.length())
        for (i in 0 until list.length()) {
            val w = list.optJSONObject(i)?.optString("searchWord").orEmpty().trim()
            if (w.isNotEmpty() && !out.contains(w)) out += w
        }
        out
    }

    suspend fun search(query: String, page: Int = 1, pageSize: Int = 30): List<VideoItem> =
        Api.search(query, page, pageSize)

    /** 剧集详情：eps 出集数条，info 出档位表 */
    suspend fun series(cid: String, seedVid: String = ""): SeriesDetail {
        var vids: List<String> = emptyList()
        var vip: Set<String> = emptySet()
        var title = ""
        var description = ""
        var actors: List<String> = emptyList()
        var category = ""
        try {
            val eps = Api.eps(cid)
            vids = Parser.vids(eps)
            vip = Parser.vipSet(eps)
            title = Parser.seriesTitle(eps)
            description = Parser.seriesDescription(eps)
            actors = Parser.seriesActors(eps)
            category = Parser.seriesCategory(eps)
        } catch (e: Exception) {
            // 剧集接口失败时，详情页至少还能播 seedVid 这一集
            if (seedVid.isBlank()) {
                throw ApiException("获取剧集失败：${e.message ?: "接口无响应"}")
            }
            vids = listOf(seedVid)
            Log.w(TAG, "eps 失败，回退单集模式：${e.message}")
        }
        if (vids.isEmpty() && seedVid.isNotBlank()) vids = listOf(seedVid)
        if (vids.isEmpty()) throw ApiException("该剧集没有可取用的视频（可能是单片或已下架）")

        val episodes = buildEpisodes(vids, fetchMetaMap(vids), vip, title)
        val seed = if (seedVid.isBlank()) "" else seedVid
        val tiers = runCatching {
            episodeInfo(seed.ifBlank { episodes.firstOrNull()?.vid.orEmpty() }).tiers
        }.getOrDefault(emptyList())
        // 封面复用已实现的 hiResCover（eps.meta.pic 原图）；失败返回空串，详情页回落到色块，
        // 不能让「配图好看」这件事影响「剧集能不能播」
        val cover = runCatching { hiResCover(cid) }.getOrDefault("")
        return SeriesDetail(
            cid = cid,
            title = title,
            episodes = episodes,
            tiers = tiers,
            cover = cover,
            description = description,
            actors = actors,
            category = category,
        )
    }

    /** eps 的 vid 是全集顺序；meta 分批取，单批失败不影响其余集 */
    private suspend fun fetchMetaMap(vids: List<String>): Map<String, EpisodeMeta> {
        val out = HashMap<String, EpisodeMeta>(vids.size)
        var i = 0
        while (i < vids.size) {
            val batch = vids.subList(i, minOf(i + META_BATCH, vids.size))
            try {
                out += Parser.metaMap(Api.meta(batch))
            } catch (e: Exception) {
                Log.w(TAG, "meta 批次失败（${batch.size} 条）：${e.message}")
            }
            i += META_BATCH
        }
        return out
    }

    /** 单集档位表（返回前剔除实测取不到的档位） */
    suspend fun episodeInfo(vid: String): EpisodePlayInfo {
        if (vid.isBlank()) return EpisodePlayInfo(vid = vid, title = "", tiers = emptyList())
        val info = try {
            Api.info(vid)
        } catch (e: Exception) {
            // 缺档位比直接抛异常更可恢复：播放器可以退回默认文件名再试
            Log.w(TAG, "info 失败（$vid）：${e.message}")
            return EpisodePlayInfo(vid = vid, title = "", tiers = emptyList())
        }
        val hls = Parser.infoHlsUrl(info)
        val guid = Parser.infoGuid(info)
        if (guid.isNotBlank()) {
            // 空串也照旧落缓存（表示该集确实没有 HLS），同样按 TTL 处理，逻辑保持统一
            hlsByGuid[guid] = HlsEntry(hls, System.currentTimeMillis())
        }
        return EpisodePlayInfo(
            vid = vid,
            title = Parser.infoTitle(info),
            // 解析层已过滤 avail=false 的档位，这里再剔除运行期实测取不到的那些，
            // 用户看到的清晰度列表 == 现在真的能播的档位
            tiers = withoutBlacklisted(Parser.tiers(info)),
            hlsUrl = hls,
        )
    }

    /** 选最佳档位（app.js pickBestTier：avail!=false；f0/fno=0 优先；否则按分辨率降序） */
    fun pickBestTier(tiers: List<Tier>): Tier? {
        // 黑名单双保险：即使调用方手里的 tiers 是过滤前的旧列表，也不会选到取不到链接的档位
        val ok = tiers.filter { it.fn.isNotBlank() && it.available && !isTierBlacklisted(it) }
        if (ok.isEmpty()) return null
        ok.firstOrNull { it.defn == "f0" || it.fno == "0" }?.let { return it }
        return ok.sortedByDescending { resNum(it) }.first()
    }

    private fun resNum(t: Tier): Int {
        val raw = t.res.ifBlank { Tier.DEFN_RES[t.defn] ?: "" }
        return Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: 0
    }

    /** 档位 → 播放直链（内部拼文件名再打 vkey） */
    suspend fun playLink(tier: Tier): PlayLink = playLinkSeg(tier, -1)

    /** 老片分片档的第 seg 片直链 */
    suspend fun playLinkSeg(tier: Tier, seg: Int): PlayLink {
        val fn = fileNameFor(tier, seg)
        val json = try {
            Api.vkey(fn)
        } catch (e: Exception) {
            // vkey 失败 = 该档位实测取不到链接，拉黑后它会从清晰度列表消失，
            // 下次 episodeInfo/选档就会自动降级到下一个可用档位。
            // 异常语义保持不变：集成层仍据这条消息向用户报错。
            blacklistTier(tier)
            throw ApiException("生成播放链接失败：${e.message ?: "接口无响应"}")
        }
        // hls_url 只在 a=info 的响应里，vkey 响应没有这个字段；分片档按 seg 拼整片 m3u8 没有意义，
        // 所以只有非分片场景才去取，取不到就是空串，播放器自然回退 MP4 直链
        val nonSeg = seg < 0 || !FileName.LEG_SEG_TIERS.contains(tier.fno)
        val hls = if (nonSeg) fetchHlsUrl(tier) else ""
        val link = Parser.playLink(json, fn, hls)
        // 拿到 200 但 url 为空，同样是这一档位实测取不到，一并拉黑
        if (link.url.isBlank()) {
            blacklistTier(tier)
            throw ApiException("播放链接为空（$fn）")
        }
        return link
    }

    /**
     * 按 tier.fn 里的 guid 取 episodeInfo 缓存下来的整片 m3u8。
     * 没缓存、或已过期，都返回空串让播放器直接走 MP4——宁可没有 HLS，也不要拿失效地址起播。
     * 过期条目顺手移除，缓存因此不会无界增长。
     */
    private fun fetchHlsUrl(tier: Tier): String {
        val guid = FileName.parse(tier.fn)?.guid ?: return ""
        val entry = hlsByGuid[guid] ?: return ""
        if (System.currentTimeMillis() - entry.at > HLS_TTL_MS) {
            hlsByGuid.remove(guid)
            return ""
        }
        return entry.url
    }

    /**
     * Tier 只带 fn，guid/style/ext 必须从 fn 反解；分片号由调用方决定，
     * 老片分片档缺省取 1（app.js fileNameFor 同款规则）。
     */
    private fun fileNameFor(tier: Tier, seg: Int): String {
        val p = FileName.parse(tier.fn)
            ?: return tier.fn.ifBlank { throw ApiException("文件名不可用") }
        return if (seg <= 0) FileName.forSeg(p, tier.fno) else FileName.forSeg(p, tier.fno, seg.toString())
    }

    /** 弹幕：拉 [fromMs, toMs] */
    suspend fun danmaku(vid: String, fromMs: Long, toMs: Long): List<Danmaku> {
        if (vid.isBlank()) return emptyList()
        return try {
            Parser.danmaku(Api.barrage(vid, fromMs, toMs))
        } catch (e: Exception) {
            // 弹幕挂了不能拖垮播放，静默降级为空轨道
            Log.w(TAG, "弹幕拉取失败（$vid $fromMs~$toMs）：${e.message}")
            emptyList()
        }
    }

    /** 把 eps 的 vip 数组、meta 的分类等规整成剧集列表（正片在前） */
    fun buildEpisodes(
        vids: List<String>,
        metaMap: Map<String, EpisodeMeta>,
        vipSet: Set<String>,
        seriesName: String = "",
    ): List<Episode> {
        val rows = vids.map { vid ->
            val meta = metaMap[vid]
            Parser.categoryRank(meta?.category.orEmpty()) to
                Episode(
                    vid = vid,
                    index = 0,
                    label = meta?.seriesPartTitle?.takeIf { it.isNotBlank() }
                        ?: meta?.title?.takeIf { it.isNotBlank() }
                        ?: vid,
                    isVip = vipSet.contains(vid),
                    summary = Parser.episodeSummary(meta?.title.orEmpty(), seriesName),
                )
        }
        // rank 相同保持 eps 原顺序（sortedBy 是稳定排序）
        return rows.sortedBy { it.first }.mapIndexed { i, (_, ep) -> ep.copy(index = i) }
    }
}
