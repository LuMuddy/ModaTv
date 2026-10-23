package com.moda.tv.data

import com.moda.tv.model.Danmaku
import com.moda.tv.model.EpisodeMeta
import com.moda.tv.model.PlayLink
import com.moda.tv.model.Tier
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端响应解析。字段名以真实响应为准（联调实测）：
 *   eps     → { ok, cid, vids[], vip[], meta{ title, category_map } }
 *   meta    → { ok, items[{ id, fields{ title, series_part_title, category_map } }] }
 *   info    → { ok, vid, cid, ti, fn, guid, dtiers[{ fno, fn, defn, res, avail, fc, segs }] }
 *   barrage → { ok, vid, from, to, barrage_list[{ time_offset, content, track_type, content_style }] }
 */
internal object Parser {

    /** 花絮类分类关键词：命中即非正片（app.js EXTRA） */
    private val EXTRA_CATS = listOf(
        "预告", "花絮", "彩蛋", "片头", "片尾", "宣传", "独家", "幕后", "采访", "速看", "前瞻",
    )

    // ---------- eps ----------

    fun vids(eps: JSONObject): List<String> {
        val arr = eps.optJSONArray("vids") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) out += v
        }
        return out
    }

    fun vipSet(eps: JSONObject): Set<String> {
        val arr = eps.optJSONArray("vip") ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) out += v
        }
        return out
    }

    fun seriesTitle(eps: JSONObject): String {
        val meta = eps.optJSONObject("meta") ?: return ""
        return meta.optString("title").ifBlank { meta.optString("series_name") }
    }

    /** 真实剧情简介：eps 的 meta.description；缺失时退回 second_title，别拿剧名顶替 */
    fun seriesDescription(eps: JSONObject): String {
        val meta = eps.optJSONObject("meta") ?: return ""
        return meta.optString("description").ifBlank { meta.optString("second_title") }
    }

    /** 主演/常驻嘉宾；接口给的是字符串数组，缺就返回空 */
    fun seriesActors(eps: JSONObject): List<String> {
        val meta = eps.optJSONObject("meta") ?: return emptyList()
        val arr = meta.optJSONArray("leading_actor") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim().removePrefix("[").removeSuffix("]").trim()
            if (v.isNotBlank()) out += v
        }
        return out
    }

    /** 集号前缀：「第1期上：」「第1季 第01集」「12集」这类，只剥开头 */
    private val EpisodeNumbering = Regex("^(第\\s*\\d+\\s*[季集期话回]|\\d+\\s*[季集期话回])\\s*[上下]?\\s*[：:、]?\\s*")

    /** 电视剧的单集标题就是「剧名_01」，末尾这截全是集号，没有内容信息 */
    private val EpisodeIndexSuffix = Regex("_\\s*\\d+$")

    /**
     * 分集副标题：meta 的 fields.title。
     * 综艺是真正的当期看点（「第1期上：黄渤被邓超坑入伙」），电视剧只有「赴山海_01」。
     * 剥掉集号后什么都不剩，说明这一集没有可展示的描述，返回空串让 UI 整行不画。
     * 注意不要拿 fields.desc 顶替：实测它与整剧简介逐字相同，会和详情页顶部那段重复。
     */
    fun episodeSummary(title: String, seriesName: String): String {
        if (title.isBlank()) return ""
        // 剧名的空格不可靠：eps 写「大侦探波洛 第九季」，单集标题写「大侦探波洛第九季_01」，
        // 直接比对会漏掉这一集，把剧名当成看点显示出来
        val series = seriesName.filterNot { it.isWhitespace() }
        fun isSeries(s: String) = series.isNotEmpty() && s.filterNot { it.isWhitespace() } == series
        val stripped = EpisodeIndexSuffix.replace(title.trim(), "").trim()
        // 只有「剧名_01」这种纯集号形态才会命中；此时剥出来的就是剧名，同样不该显示
        if (stripped.isEmpty()) return ""
        if (EpisodeIndexSuffix.containsMatchIn(title) && isSeries(stripped)) return ""
        var s = stripped
        while (true) {
            val next = EpisodeNumbering.replaceFirst(s, "").trim()
            if (next == s) break
            s = next
        }
        if (s.isEmpty() || isSeries(s) || s.all { it.isDigit() }) return ""
        return s
    }

    /** 「综艺 · 内地」这类分类行，两个字段都可能缺 */
    fun seriesCategory(eps: JSONObject): String {
        val meta = eps.optJSONObject("meta") ?: return ""
        return listOf(meta.optString("type_name"), meta.optString("area_name"))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    // ---------- meta ----------

    fun metaMap(json: JSONObject): Map<String, EpisodeMeta> {
        val items = json.optJSONArray("items") ?: return emptyMap()
        val out = HashMap<String, EpisodeMeta>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val fields = it.optJSONObject("fields") ?: JSONObject()
            val vid = it.optString("id").ifBlank { fields.optString("vid") }
            if (vid.isBlank()) continue
            out[vid] = EpisodeMeta(
                vid = vid,
                title = fields.optString("title"),
                seriesPartTitle = fields.optString("series_part_title"),
                category = categoryName(fields),
            )
        }
        return out
    }

    /**
     * category_map 实测是数组 [id, 名称, id, 名称, id, 名称]，名称可能落在 1/3/5 任一位；
     * 个别站点返回单段字符串，此时按空白切第二段（app.js catOf 的双形态兜底）。
     */
    fun categoryName(fields: JSONObject): String {
        return when (val cm = fields.opt("category_map")) {
            is JSONArray -> listOf(1, 3, 5)
                .map { cm.optString(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty()
            is JSONObject -> listOf("1", "3", "5")
                .map { cm.optString(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty()
            is String -> cm.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
            else -> ""
        }
    }

    /** 正片排最前，花絮/预告次之，其余垫底（app.js catOf 的 rank） */
    fun categoryRank(name: String): Int = when {
        name == "正片" -> 0
        EXTRA_CATS.any { name.contains(it) } -> 1
        else -> 2
    }

    // ---------- info ----------

    /**
     * 只返回接口认为可用的档位：avail=false 或 fn 为空的档位直接丢弃，不进列表。
     * 产品要求「不可用就不要显示」——灰掉的档位对用户是噪音，点下去还会报错，
     * 所以过滤发生在解析层，下游（UI/播放器）拿到的每一条都是理论可播放的。
     */
    fun tiers(info: JSONObject): List<Tier> {
        val arr = info.optJSONArray("dtiers") ?: return emptyList()
        val out = ArrayList<Tier>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val fn = d.optString("fn")
            if (fn.isBlank()) continue
            if (!d.optBoolean("avail", true)) continue
            out += Tier(
                fno = d.optString("fno"),
                fn = fn,
                defn = d.optString("defn"),
                res = d.optString("res"),
                available = true,
                segCount = d.optInt("fc", 0),
            )
        }
        return out
    }

    /** info 的标题字段是 ti，没有 title */
    fun infoTitle(info: JSONObject): String = info.optString("ti")

    /** hls_url 只出现在 a=info 的响应里（vkey 响应没有该字段），是整片 m3u8 */
    fun infoHlsUrl(info: JSONObject): String = info.optString("hls_url")

    /** info 的 guid，用来把 hls_url 关联到档位所属的剧集 */
    fun infoGuid(info: JSONObject): String = info.optString("guid")

    fun playLink(json: JSONObject, fallbackFilename: String, hlsUrl: String = ""): PlayLink = PlayLink(
        filename = json.optString("filename").ifBlank { fallbackFilename },
        url = json.optString("url"),
        vkey = json.optString("vkey"),
        capped = json.optBoolean("capped", false),
        hlsUrl = hlsUrl,
    )

    // ---------- barrage ----------

    fun danmaku(json: JSONObject): List<Danmaku> {
        val arr = json.optJSONArray("barrage_list") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val times = LongArray(arr.length())
        val items = ArrayList<Danmaku>(arr.length())
        var n = 0
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val text = b.optString("content")
            if (text.isBlank()) continue
            // content_style 是内嵌 JSON 串，坏了就当没有样式，不能让整批弹幕失败
            val cs = b.optString("content_style").takeIf { it.isNotBlank() }?.let {
                try {
                    JSONObject(it)
                } catch (e: Exception) {
                    null
                }
            }
            times[n] = b.optLong("time_offset", 0L)
            items += Danmaku(
                id = b.optString("id").ifBlank { i.toString() },
                text = text,
                timeMs = times[n],
                mode = b.optInt("track_type", 0),
                color = parseColor(cs?.optString("color").orEmpty()),
                size = cs?.optInt("fontsize", 1) ?: 1,
            )
            n++
        }
        if (n == 0) return emptyList()
        // 同一毫秒的弹幕会挤成一坨，在 140ms 内均匀铺开（照搬 app.js dmFetch）
        spread(times, n)
        val order = (0 until n).sortedBy { times[it] }
        return order.map { items[it].copy(timeMs = times[it]) }
    }

    private fun spread(times: LongArray, n: Int) {
        var i = 0
        while (i < n) {
            val base = times[i]
            var j = i + 1
            while (j < n && times[j] == base) j++
            val cnt = j - i
            if (cnt >= 2) {
                for (k in 0 until cnt) times[i + k] = base + (k * 140L) / cnt
            }
            i = j
        }
    }

    /** content_style.color 是不带 # 的十六进制；解析不了就退回白色 */
    private fun parseColor(raw: String): Int {
        val s = raw.removePrefix("#").trim()
        if (s.isEmpty()) return 0xFFFFFF
        return s.toIntOrNull(16)?.and(0xFFFFFF) ?: 0xFFFFFF
    }
}
