package com.moda.tv.data

import android.util.Log
import com.moda.tv.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 首页各行的数据来源：v.qq.com 官方频道页。
 *
 * 为什么不用搜索词当推荐位：`Api.search("热门")` 实际是搜「热门」这两个字，
 * 拿到的只是标题里含该词的结果（实测 total 仅 30 条），既不是榜单也不是分类。
 * 而 `https://v.qq.com/channel/<频道>` 是腾讯自己的频道页，推荐位就在 HTML 的
 * `/x/cover/<cid>` 链接里——和首页轮播是同一套稳定可解析的结构。
 *
 * 抓取/补全的实现全部复用 [CarouselSource]：不复制第二份 OkHttp 客户端、
 * 也不复制 UA 与正则。这里只负责「频道 → 行」的编排与失败回退。
 */
object ChannelSource {

    private const val TAG = "ChannelSource"

    /** 一个频道 = 一行。顺序即首页行序，固定不变，不要排序 */
    enum class Channel(val title: String, val url: String, val fallbackWord: String) {
        /** [fallbackWord] 是频道页抓不到时回退到的搜索词，也是原 HOME_WORDS 的成员 */
        TV("电视剧", "https://v.qq.com/channel/tv", "电视剧"),
        MOVIE("电影", "https://v.qq.com/channel/movie", "电影"),
        VARIETY("综艺", "https://v.qq.com/channel/variety", "综艺"),
        CARTOON("动漫", "https://v.qq.com/channel/cartoon", "动漫"),
    }

    /**
     * 每行取多少个 cid。频道页实测：tv 15 / movie 12 / variety 17 / cartoon 11 个 unique cid，
     * 18 是「有就多拿，没有也不强求」的上限，实际每行 11~18 张卡对 TV 端一屏刚好。
     */
    private const val ROW_CIDS = 18

    /**
     * 整页补全的并发上限。eps 是重接口（一次回整部剧的 vids），60 个 cid 同时打后端
     * 会明显拖慢首屏甚至被限流；4 路实测能在几秒内补完整个首页。
     * 注意这是「整页」共享的信号量，不是每频道各 4 路——否则四行叠起来就是 16 路。
     */
    private const val PARALLEL = 4

    /** 一个频道行的抓取结果；[items] 为空表示该频道页失败或全部补全失败 */
    data class Row(val channel: Channel, val items: List<VideoItem>)

    /**
     * 抓全部频道。整体不抛异常：单个频道失败只让该行的 [Row.items] 为空，
     * 调用方（Repo.homeRows）据此把该行回退到搜索方案。
     */
    suspend fun loadAll(): List<Row> = coroutineScope {
        // 抓 HTML 和补全 meta 用同一个整页信号量：抓 4 个频道页 + 补 60 个 cid
        // 全部排在 4 个许可后面，任何时刻对外的并发请求数都不会超过 PARALLEL
        val sem = Semaphore(PARALLEL)
        val rows = Channel.entries.map { ch ->
            async(Dispatchers.IO) {
                val items = try {
                    loadChannel(ch, sem)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 网络 / 改版 / 解析异常都在这里落地，不外抛
                    Log.w(TAG, "频道「${ch.title}」抓取失败：${e.message}")
                    emptyList()
                }
                Row(ch, items)
            }
        }.awaitAll()
        Log.i(TAG, "频道行：" + rows.joinToString("，") { "${it.channel.title}=${it.items.size}" })
        rows
    }

    /** 单频道：频道页 → cid 列表 → 并发补全；单条失败只跳过该条 */
    private suspend fun loadChannel(ch: Channel, sem: Semaphore): List<VideoItem> {
        val cids = sem.withPermit { CarouselSource.fetchCids(ch.url, ROW_CIDS) }
        if (cids.isEmpty()) {
            Log.w(TAG, "频道「${ch.title}」未解析出任何 cid（页面结构可能已改版）")
            return emptyList()
        }
        val items = coroutineScope {
            cids.map { cid ->
                async(Dispatchers.IO) { sem.withPermit { CarouselSource.enrich(cid) } }
            }.awaitAll()
        }.filterNotNull()
        Log.i(
            TAG,
            "频道「${ch.title}」：${cids.size} 个 cid，补全 ${items.size} 条，" +
                "前三 ${items.take(3).joinToString("/") { it.title }}"
        )
        return items
    }
}
