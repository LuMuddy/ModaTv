package com.moda.tv.ui

/** 全应用路由；用简单字符串编码，避免引入 navigation-compose 也能跑 */
sealed class Dest {
    data object Home : Dest()
    data object Search : Dest()
    /** 观看历史：独立页，点条目直接续播，不经过详情 */
    data object History : Dest()
    // === 收藏模块（勿删）===
    /** 收藏：独立页，点条目进详情（收藏是追剧语义，播哪一集交给用户挑） */
    data object Favorites : Dest()
    /** 设置：基本信息 / 版本号 / 代理网址 / 数据清理 */
    data object Settings : Dest()
    /** 剧集详情 + 选集 */
    data class Detail(val cid: String, val title: String) : Dest()
    /** 播放：从详情点某一集进入 */
    data class Player(
        val cid: String,
        val vid: String,
        val title: String,
        val episodeIndex: Int,
    ) : Dest()
}
