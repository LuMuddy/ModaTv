package com.moda.tv.model

import androidx.annotation.StringRes
import com.moda.tv.R

/**
 * 首页顶部的分类 tab。顺序即工具栏顺序，固定不变（推荐在最左，冷启动默认选中它）。
 *
 * [titleRes] 是 **资源 id** 而不是字符串：分类名属于界面文案，必须跟着系统语言走
 * （中文 values/、英文 values-en/）。这里若直接存中文，数据层就被中文绑死，
 * 系统语言是英文时首页 tab 栏仍然只能是中文。
 *
 * 存储与日志一律用 [name]（Recommend/Tv/...）：改文案不能让旧缓存失联，
 * 也不能让日志跟着语言变。
 *
 * 注解写成 `@param:` 是显式指定目标——构造器参数上的 @StringRes 目标未定，
 * 不写编译器会报 KT-73255 告警（与 PlayerSettingsStore 的 labelRes 写法一致）。
 */
enum class HomeTab(@param:StringRes val titleRes: Int) {
    Recommend(R.string.tab_recommend),

    // ---- 以下顺序取自 v.qq.com 首页导航栏的真实排列 ----
    Tv(R.string.tab_tv),
    Movie(R.string.tab_movie),
    Variety(R.string.tab_variety),
    Cartoon(R.string.tab_cartoon),

    /**
     * 少儿。腾讯站内分类名就是「少儿」，不是「儿童」（见 /channel/child 的 title）。
     */
    Child(R.string.tab_child),

    Doco(R.string.tab_doco),
    Choice(R.string.tab_choice),
    Music(R.string.tab_music),
    Sports(R.string.tab_sports),
    Nba(R.string.tab_nba),
    Game(R.string.tab_game),
    Auto(R.string.tab_auto),
    Fashion(R.string.tab_fashion),
    Life(R.string.tab_life),
    Art(R.string.tab_art),
    Pet(R.string.tab_pet),
    Podcast(R.string.tab_podcast),
    FreeList(R.string.tab_free);

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
