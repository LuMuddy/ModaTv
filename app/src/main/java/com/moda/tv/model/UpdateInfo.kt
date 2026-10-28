package com.moda.tv.model

/**
 * 一次版本检查的结果：UI 只读这三个字段，不需要再回头查任何接口。
 *
 * 为什么强调 [versionName] 不带前导 v：比较（[com.moda.tv.data.local.VersionRules]）
 * 与 APK 直链（镜像前缀 + "/releases/download/v<version>/ModaTv-<version>.apk"）都以它为准，
 * 而 atom feed 的标题是带 v 的 "v1.2.3"。剥一次、放在这里，下游就不会出现
 * "有的地方带 v 有的不带"这种只在某些版本上才炸的拼接错误。
 */
data class UpdateInfo(
    /** 不含前导 v，例如 "1.2.3" */
    val versionName: String,
    /** 剥掉 HTML 标签后的纯文本更新日志，允许为空串 */
    val notes: String,
    /**
     * GitHub **直连**的 APK 地址（不带任何镜像前缀）。
     * 镜像前缀由 [com.moda.tv.data.UpdateSource.apkUrls] 在发起下载时拼——
     * 镜像会换、会死，那是取数层的策略，不是「这一版是什么」的事实。
     */
    val apkUrl: String,
    /**
     * 远端那一版的 Android versionCode，来自同一次 Release 挂的 version.json。
     * 老版本（v1.0.0 及更早）没有这个文件，所以是 null——「不清楚」和「等于 0」不能混成同一个值，
     * 否则判定会拿一个不存在的版本码去比，把「没数据」读成「比本机旧」。
     */
    val versionCode: Int? = null,
)
