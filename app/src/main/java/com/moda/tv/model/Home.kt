package com.moda.tv.model

/** 首页一行横向卡片 */
data class HomeRow(val title: String, val items: List<VideoItem>)

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
