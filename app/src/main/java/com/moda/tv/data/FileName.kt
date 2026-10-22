package com.moda.tv.data

/**
 * 文件名结构（照搬 app.js §2，vkey 依赖，不要改规则）：
 *   <guid>.f<档位>[.<片>].<ext> / <guid>.p<档位>[.<片>].<ext> /
 *   <guid>.<档位>[.<片>].<ext>   / <guid>.<ext>（裸名整片）
 */
data class ParsedName(
    val guid: String,
    val fno: String,
    val seg: String,
    val ext: String,
    val style: String,
)

object FileName {
    /** 老片分片档：这些档位必须带分片号 */
    val LEG_SEG_TIERS = listOf("201", "202", "203", "209", "212")

    fun isLegacyStyle(style: String): Boolean = style in listOf("p", "n", "none")
    fun legNeedsSeg(fno: String): Boolean = LEG_SEG_TIERS.contains(fno)

    /**
     * 模式顺序与 app.js 的 FN_PATTERNS 一一对应，不能调换：f/p 先认，再认老片手机档与纯数字档，
     * 最后的裸名整片会把剩下的全吞掉。
     */
    private val PATTERNS: List<Pair<String, String>> = listOf(
        "\\.f(\\d+)(?:\\.(\\d+))?" to "f",
        "\\.p(\\d+)(?:\\.(\\d+))?" to "p",
        "\\.([a-z]\\d+)(?:\\.(\\d+))?" to "n",
        "\\.(\\d+)(?:\\.(\\d+))?" to "n",
        "" to "none",
    )

    // 解析在 UI 线程上每逢换集都会走一次，编译好的正则只建一次
    private val REGEX: List<Pair<Regex, String>> = PATTERNS.map { (mid, style) ->
        Regex("^([^.]+)$mid\\.(mp4|m4a|ts)$", RegexOption.IGNORE_CASE) to style
    }

    fun parse(fn: String): ParsedName? {
        val s = fn.trim()
        if (s.isEmpty()) return null
        for ((re, style) in REGEX) {
            val m = re.find(s) ?: continue
            val g = m.groupValues
            // 裸名模式只有 [整个匹配, guid, ext] 三组，第 2 组是扩展名而不是档位，
            // 按位置通读会把 mp4 当成 fno，必须单独分支
            if (style == "none") {
                return ParsedName(
                    guid = g[1],
                    fno = "",
                    seg = "",
                    ext = g[2].lowercase(),
                    style = style,
                )
            }
            val fno = g[2]
            val rawSeg = g.getOrNull(3).orEmpty()
            return ParsedName(
                guid = g[1],
                fno = fno,
                // app.js 在 fileNameFor 里才给老片分片档补第 1 片；提前到解析这一步等价，
                // 生成的 fn 完全一致，省得每个调用点都记得补
                seg = if (rawSeg.isEmpty() && isLegacyStyle(style) && legNeedsSeg(fno)) "1" else rawSeg,
                ext = g[g.size - 1].lowercase(),
                style = style,
            )
        }
        return null
    }

    fun buildFileName(guid: String, fno: String, seg: String, ext: String, style: String): String {
        val s = if (seg.isNotEmpty()) ".$seg" else ""
        // 老片 p/none 档的 ts 源要改回 mp4 才能真正拉到流
        val e = if (isLegacyStyle(style) && style != "n" && ext == "ts") "mp4" else ext
        if (fno.isEmpty()) return guid + "." + if (isLegacyStyle(style)) "mp4" else ext
        return when (style) {
            "p", "none" -> guid + ".p" + fno + s + "." + e
            "n" -> guid + "." + fno + s + "." + e
            else -> guid + ".f" + fno + s + "." + ext
        }
    }

    /** 老片分片档时返回第 seg 片，其余原样 */
    fun forSeg(p: ParsedName, fno: String, seg: String? = null): String {
        // app.js fileNameFor：none 档换档时必须落成 p 档，否则拼不出合法文件名
        val style = if (p.style == "none") "p" else p.style
        val useSeg = seg ?: if (isLegacyStyle(p.style)) {
            if (legNeedsSeg(fno)) p.seg.ifEmpty { "1" } else ""
        } else {
            p.seg
        }
        return buildFileName(p.guid, fno, useSeg, p.ext, style)
    }
}
