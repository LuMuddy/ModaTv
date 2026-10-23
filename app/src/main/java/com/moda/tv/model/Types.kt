package com.moda.tv.model

import androidx.annotation.StringRes
import com.moda.tv.R

/** 搜索/信息流条目（来自后端搜索响应的 items[].videoInfo.videoDoc/coverDoc） */
data class VideoItem(
    val cid: String,
    val vid: String,
    val title: String,
    val cover: String,
    val subtitle: String = "",
    val desc: String = "",
    val badge: String = "",
) {
    /**
     * 横版大图（首页主视觉专用）。
     *
     * 官网的背景是**节目剧照**（真实画面，1280x720），缩略图是**竖版海报**，
     * 两者是不同类型的两张图，不是同一张裁出来的。腾讯图床对每个 vid 都有横版剧照：
     *     http://puui.qpic.cn/vpic_cover/{vid}/{vid}_hz.jpg/0   → 实测 1280x720
     * 没有 vid 时退回竖版海报（单条剧或老片可能取不到 vid），保证任何情况下都有图。
     */
    val wideCover: String get() = when {
        vid.isNotBlank() -> "http://puui.qpic.cn/vpic_cover/$vid/${vid}_hz.jpg/0"
        else -> cover
    }

    /** 竖版缩略图：主视觉下方的小图用这块裁切，与大图不是同一张构图 */
    val thumbCover: String get() = when {
        cover.isBlank() -> ""
        cover.contains("imageMogr2") -> cover
        else -> "$cover?imageMogr2/thumbnail/260x360!"
    }
}

/** 单集元数据（来自后端批量元数据响应的 items[].fields） */
data class EpisodeMeta(
    val vid: String,
    val title: String = "",
    val seriesPartTitle: String = "",
    val category: String = "",
)

/** 一集 */
data class Episode(
    val vid: String,
    val index: Int,
    val label: String,
    val isVip: Boolean = false,
    /**
     * 分集副标题（meta 的 fields.title 剥掉集号后的内容）。
     * 综艺是当期看点，电视剧剥完为空串——空串表示这一集没有描述，选集卡整行不画。
     */
    val summary: String = "",
) {
    /**
     * 选集缩略图。腾讯图床的地址只由 vid 决定：
     * http://puui.qpic.cn/vpic_cover/<vid>/<vid>_hz.jpg/<尺寸>
     * /0 是原图（实测 1280x720），/160 是列表用缩略图；/320、/480 都是 404。
     * 因此不需要为每一集额外打一次 meta 接口，拼字符串即可——976 集的剧也扛得住。
     */
    val cover: String get() = if (vid.isBlank()) "" else
        "http://puui.qpic.cn/vpic_cover/$vid/${vid}_hz.jpg/0"
}

/**
 * 清晰度档位的显示名。
 *
 * 两种来源必须分开表达：上游 res（"804P" 这类）是数据，原样上屏、不翻译；
 * 本地档位名（原码 / 整片）是文案，只能以资源 id 的形态出现——
 * 数据层一旦固化成品句子，这句就再也没法跟着改词/多语言走。
 */
sealed interface TierName {
    /** 上游给的原文，属于数据 */
    data class Upstream(val text: String) : TierName

    /** 需要按资源渲染的档位名 */
    data class Local(@param:StringRes val resId: Int) : TierName
}

/** 清晰度档位（info.dtiers[]） */
data class Tier(
    val fno: String,
    val fn: String,
    val defn: String = "",
    val res: String = "",
    val available: Boolean = true,
    val segCount: Int = 0,
) {
    /**
     * 只回答「用哪条资源 / 哪段上游文本」，不返回成品句子。
     * 句子由 UI 侧的 tierNameText 用 stringResource 取；不要在这里另写一份
     * defn → 文案 的映射，那等于往数据层塞回第二份中文字典。
     */
    val display: TierName get() = when {
        defn == "f0" -> TierName.Local(R.string.tier_name_source)
        res.isNotBlank() -> TierName.Upstream(res)
        defn == "p5" -> TierName.Local(R.string.tier_name_full)
        // 上游既没给 res 也没有已知 defn 时退「自动」：f0/f1 这类内部代号不是给用户看的
        else -> DEFN_RES[defn]?.let(TierName::Upstream) ?: TierName.Local(R.string.player_auto)
    }

    companion object {
        /** 上游没给 res 时的分辨率兜底串：这是数据（270P…），不进资源、不翻译 */
        val DEFN_RES = mapOf(
            "sd" to "270P", "hd" to "480P", "shd" to "720P", "fhd" to "1080P",
        )
    }
}

/** 剧集详情 */
data class SeriesDetail(
    val cid: String,
    val title: String,
    val episodes: List<Episode>,
    val tiers: List<Tier> = emptyList(),
    /** 详情页宣传图：eps 的 meta.pic 原图；取不到时为空串，UI 回落到色块 */
    val cover: String = "",
    /** 真实剧情简介（eps.meta.description）；不要再用剧名兜底，那会让简介与标题重复 */
    val description: String = "",
    /** 主演，综艺里是常驻嘉宾 */
    val actors: List<String> = emptyList(),
    /** 「综艺 · 内地」 */
    val category: String = "",
)

/** 播放直链 */
data class PlayLink(
    val filename: String,
    val url: String,
    val vkey: String,
    val capped: Boolean = false,
    val hlsUrl: String = "",
)

/** 一条弹幕 */
data class Danmaku(
    val id: String,
    val text: String,
    val timeMs: Long,
    val mode: Int = 0,
    val color: Int = 0xFFFFFF,
    val size: Int = 1,
)

/**
 * 播放记录 / 收藏 / 已看完共用条目。
 *
 * [completed] 是「这一集已经看完」的标记，只有 [com.moda.tv.data.local.CompletedStore] 里的条目
 * 会把它置 true（历史与收藏写进来的一律是默认 false）。
 * 之所以放在条目上而不是另建一套数据结构：看完的那一集同时需要剧名、封面、时长，
 * 这些字段与历史记录完全一致，复用同一份 [LibraryJson] 编解码就不必再维护第二套手写 JSON。
 *
 * 注意向后兼容：旧版本写出的 JSON 里没有这个字段，解析必须落成 false 而不是报错。
 */
data class LibraryEntry(
    val cid: String,
    val vid: String,
    val title: String,
    val cover: String,
    val episodeIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    val completed: Boolean = false,
)
