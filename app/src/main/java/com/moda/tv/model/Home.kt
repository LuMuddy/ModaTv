package com.moda.tv.model

/**
 * 首页顶部的分类 tab。顺序即工具栏顺序，固定不变（推荐在最左，冷启动默认选中它）。
 *
 * [title] 是给用户看的标签，不进任何存储键——存储用 [name]（Recommend/Tv/...），
 * 这样改中文文案不会让旧缓存失联。
 */
enum class HomeTab(val title: String) {
    Recommend("推荐"),

    // ---- 以下顺序取自 v.qq.com 首页导航栏的真实排列 ----
    Tv("电视剧"),
    Movie("电影"),
    Variety("综艺"),
    Cartoon("动漫"),

    /**
     * 少儿。腾讯站内分类名就是「少儿」，不是「儿童」（见 /channel/child 的 title）。
     */
    Child("少儿"),

    Doco("纪录片"),
    Choice("精选"),
    Music("音乐"),
    Sports("体育"),
    Nba("NBA"),
    Game("游戏"),
    Auto("汽车"),
    Fashion("时尚"),
    Life("生活"),
    Art("艺术"),
    Pet("萌宠"),
    Podcast("播客"),
    FreeList("免费");

    companion object {
        /** tab 栏按这个顺序铺，不要自行排序 */
        val entries: List<HomeTab> get() = HomeTab.entries
        fun of(name: String): HomeTab? = HomeTab.entries.firstOrNull { it.name == name }
    }
}

/**
 * 一个 tab 的首页流状态。
 *
 * [fromCache] 让 UI 能把「现在是缓存内容」和「刚拉到的新内容」区分开
 * （例如刷新时不清空列表），[fetchedAt] 是**数据被拉下来的时刻**，
 * 缓存读回来的也是当初那个时间，不会被读盘那一刻覆盖掉。
 *
 * 没有 page / hasMore：首页每个 tab 只有一屏，数据源 v.qq.com 的推荐位与频道页
 * 都没有可解析的分页结构，「还有下一页」这个概念在首页不成立。
 * [items] 为空只表示这个 tab 还没拉到东西。
 */
data class HomeFeed(
    val items: List<VideoItem> = emptyList(),
    val fetchedAt: Long = 0L,
    val fromCache: Boolean = false,
)

/** 一次播放所需的全部信息：决定用哪个 vid、哪个档位、拼出哪个文件名 */
data class PlayPlan(
    val vid: String,
    val filename: String,
    val url: String,
    val tier: Tier,
    val episodeIndex: Int,
    val seg: Int = 0,
    val vkey: String = "",
    /** 整片 m3u8；空白表示走 MP4 直链，播放器据此决定是否用 HLS */
    val hlsUrl: String = "",
)

/** info 接口解析结果 */
data class EpisodePlayInfo(
    val vid: String,
    val title: String,
    val tiers: List<Tier> = emptyList(),
    /** 整片 m3u8；空白表示该集没有 HLS，播放器回退 MP4 */
    val hlsUrl: String = "",
)
