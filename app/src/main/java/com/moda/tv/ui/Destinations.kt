package com.moda.tv.ui

import androidx.annotation.StringRes
import com.moda.tv.R

/** 全应用路由；用简单字符串编码，避免引入 navigation-compose 也能跑 */
sealed class Dest {
    data object Home : Dest()
    data object Search : Dest()
    /** 观看历史：独立页，点条目直接续播，不经过详情 */
    data object History : Dest()
    /**
     * 已看完：与历史并列的独立页。
     * 单独一个路由而不是给 History 加参数：两页的条目来源、删除粒度（整剧 vs 单集）、
     * 点击行为（续播 vs 从头重播）都不同，塞进一个路由只会在页面里长出 if-else。
     */
    data object Completed : Dest()
    // === 收藏模块（勿删）===
    /** 收藏：独立页，点条目进详情（收藏是追剧语义，播哪一集交给用户挑） */
    data object Favorites : Dest()
    /** 设置：从平铺一屏改成「菜单 + 子页」后的菜单页，只放三个分组入口 */
    data object Settings : Dest()
    /** 设置子页：三个分组各自一页 */
    data class SettingsGroup(val group: SettingsSectionId) : Dest()
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

/**
 * 设置的三个分组。
 *
 * 放进 Destinations.kt 而不是 SettingsScreen.kt：路由要按它决定进哪一页，
 * 而路由不该反向依赖某个屏幕的私有实现——两边都只引用这里，依赖方向才是单向的。
 * 标题与说明是分组的固有属性，菜单页与子页标题都取这一份，不会出现两套说法。
 * 存资源 id 而不是字符串：文案要跟系统语言走，字符串在构造枚举时就固化了语言。
 */
enum class SettingsSectionId(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
) {
    Playback(R.string.settings_group_playback_title, R.string.settings_group_playback_desc),
    Network(R.string.settings_group_network_title, R.string.settings_group_network_desc),
    About(R.string.settings_group_about_title, R.string.settings_group_about_desc),
}
