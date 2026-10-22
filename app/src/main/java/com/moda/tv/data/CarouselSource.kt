package com.moda.tv.data

import android.util.Log
import com.moda.tv.data.Parser
import com.moda.tv.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 首页轮播的数据来源：v.qq.com 官方首页。
 *
 * 为什么不走 protobuf 内部接口：v.qq.com 是纯 SPA，推荐位只藏在 pbaccess.video.qq.com
 * 的 protobuf 响应里，字段没有公开契约、随时会变；而首页 HTML 里的链接
 * `/x/cover/<cid>` 是稳定可解析的推荐位，改动风险低得多。宁可只拿 cid 再回自家接口补全，
 * 也不去逆向私有的二进制协议。
 */
object CarouselSource {

    private const val TAG = "CarouselSource"

    private const val HOME_URL = "https://v.qq.com/"

    /** v.qq.com 对无 UA / 爬虫 UA 会返回降级页（不含推荐位），必须装成正常浏览器 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 轮播条数：再多在电视上就成"翻不完"，10 条足够撑起一个循环 */
    private const val MAX_ITEMS = 10

    /**
     * 补全 meta 的并发上限。eps 是重接口（返回整部剧的全部 vids），
     * 同时打 10 个既浪费服务端也可能被限流；4 路能在 1~2 秒内补完 10 条。
     */
    private const val PARALLEL = 4

    private val CID_RE = Regex("/x/cover/([a-z0-9]+)")

    /**
     * 独立 client 而不是复用 Api.http：这里要的是 8 秒硬时限。
     * 首页 HTML 拿不到就该立刻放弃回退到旧横幅，不能让用户对着空横幅等 30 秒。
     */
    private val htmlClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 抓首页 → 提 cid → 并发补全。任何一步整体失败都返回空列表：
     * 轮播是"更好看"，不是"能看"，调用方（Repo.homeCarousel）据此回退旧横幅。
     * 单条失败只丢这一条，不影响其它条目。
     */
    suspend fun load(): List<VideoItem> {
        val cids = try {
            fetchCids(HOME_URL, MAX_ITEMS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 断网 / 超时 / 改版导致的解析异常都在这里落地，不向 UI 抛
            Log.w(TAG, "首页 HTML 抓取失败：${e.message}")
            return emptyList()
        }
        if (cids.isEmpty()) {
            Log.w(TAG, "首页 HTML 未解析出任何 cid（页面结构可能已改版）")
            return emptyList()
        }
        val sem = Semaphore(PARALLEL)
        val items = coroutineScope {
            cids.map { cid ->
                async(Dispatchers.IO) { sem.withPermit { enrich(cid) } }
            }.awaitAll()
        }.filterNotNull()
        Log.i(TAG, "首页轮播：解析到 ${cids.size} 个 cid，成功补全 ${items.size} 条，首条「${items.firstOrNull()?.title.orEmpty()}」")
        return items
    }

    /**
     * 拉任意 v.qq.com 页面的 HTML，按出现顺序去重后取前 [limit] 个 cid。
     * 顺序即推荐位权重，不能排序。首页轮播与 ChannelSource 的频道页共用这一份实现：
     * UA / 超时 / 正则有且只有一处，v.qq.com 改版时只改这里。
     */
    internal suspend fun fetchCids(url: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val html = htmlClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (html.isBlank()) error("$url 返回空 HTML")
        CID_RE.findAll(html).map { it.groupValues[1] }.distinct().take(limit).toList()
    }

    /** 单个 cid → 条目；拿不到标题就算失败（没有标题的卡片没法看）。频道行复用同一段 */
    internal suspend fun enrich(cid: String): VideoItem? = try {
        val eps = Api.eps(cid)
        val meta = eps.optJSONObject("meta")
        val title = meta?.optString("series_name").orEmpty()
            .ifBlank { meta?.optString("title").orEmpty() }
        if (title.isBlank()) {
            Log.w(TAG, "cid $cid 没有标题，跳过")
            null
        } else {
            VideoItem(
                cid = cid,
                // 取第一集的 vid：首页主视觉要用它拼横版剧照（vpic_cover/{vid}），
                // 竖版海报和横版剧照是两张不同的图，官网也是这么用的。
                // 进详情页仍然由详情页自己选集，这里只是拿来当封面来源。
                vid = Parser.vids(eps).firstOrNull().orEmpty(),
                title = title,
                cover = meta?.optString("pic").orEmpty(),
                subtitle = meta?.optString("second_title").orEmpty(),
                desc = meta?.optString("description").orEmpty(),
                badge = meta?.optString("type_name").orEmpty(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "cid $cid 补全失败：${e.message}")
        null
    }
}
