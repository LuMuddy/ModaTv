package com.moda.tv.data.local

import com.moda.tv.model.LibraryEntry

/**
 * 手写极简 JSON 编解码：条目结构固定且字段全是标量，引入 kotlinx.serialization
 * 属于新增依赖，超出本模块的依赖约束，因此不依赖任何 Android/第三方 API。
 */
internal object LibraryJson {

    fun encode(entries: List<LibraryEntry>): String {
        val sb = StringBuilder(64 + entries.size * 128)
        sb.append('[')
        entries.forEachIndexed { index, e ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"cid\":").str(e.cid).append(',')
            sb.append("\"vid\":").str(e.vid).append(',')
            sb.append("\"title\":").str(e.title).append(',')
            sb.append("\"cover\":").str(e.cover).append(',')
            sb.append("\"episodeIndex\":").append(e.episodeIndex).append(',')
            sb.append("\"positionMs\":").append(e.positionMs).append(',')
            sb.append("\"durationMs\":").append(e.durationMs).append(',')
            sb.append("\"updatedAt\":").append(e.updatedAt).append(',')
            // 布尔写成 JSON 的 true/false（不加引号）：与旧数据的差别只有这一个键，
            // 旧版本读到未知键会走 skipValue()，因此新数据在老版本上至少还能读回其它字段
            sb.append("\"completed\":").append(e.completed)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** 解析失败一律退化为空列表：本地缓存损坏不该让播放记录变成 crash */
    fun decode(raw: String?): List<LibraryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            normalizeByCid(Parser(raw).parseEntries())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 已看完列表专用：去重口径是 (cid + episodeIndex)，不是 cid。
     *
     * 与 [decode] 拆成两个入口而不是加一个参数，是因为这两种去重一旦用错，
     * 症状完全相反却都很难查：历史按 cid 去重，用错成按集去重 → 同一部剧堆出几十条；
     * 已看完按集去重，用错成按 cid 去重 → 一部剧只剩最后一集，其它集全被吃掉。
     */
    fun decodeEpisodes(raw: String?): List<LibraryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            normalizeByEpisode(Parser(raw).parseEntries())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 同 cid 只留 updatedAt 最新的一条，防止历史脏数据把去重前提破坏掉 */
    private fun normalizeByCid(list: List<LibraryEntry>): List<LibraryEntry> {
        val byCid = LinkedHashMap<String, LibraryEntry>(list.size)
        for (e in list) {
            if (e.cid.isBlank()) continue
            val prev = byCid[e.cid]
            byCid[e.cid] = if (prev == null || e.updatedAt >= prev.updatedAt) e else prev
        }
        return byCid.values.toList()
    }

    /** 同 (cid, episodeIndex) 只留 updatedAt 最新的一条；已看完列表用这个口径 */
    private fun normalizeByEpisode(list: List<LibraryEntry>): List<LibraryEntry> {
        val byEp = LinkedHashMap<String, LibraryEntry>(list.size)
        for (e in list) {
            if (e.cid.isBlank()) continue
            val key = e.cid + "#" + e.episodeIndex
            val prev = byEp[key]
            byEp[key] = if (prev == null || e.updatedAt >= prev.updatedAt) e else prev
        }
        return byEp.values.toList()
    }

    private const val HEX = "0123456789abcdef"

    private fun StringBuilder.str(v: String): StringBuilder {
        append('"')
        for (c in v) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c < ' ' || c == '\u2028' || c == '\u2029' -> {
                    val code = c.code
                    append("\\u")
                        .append(HEX[(code shr 12) and 0xF])
                        .append(HEX[(code shr 8) and 0xF])
                        .append(HEX[(code shr 4) and 0xF])
                        .append(HEX[code and 0xF])
                }
                else -> append(c)
            }
        }
        append('"')
        return this
    }

    /** 逐字符下降解析，只认本模块自己写出的结构，遇到未知字段直接跳过 */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseEntries(): List<LibraryEntry> {
            val out = ArrayList<LibraryEntry>()
            ws()
            expect('[')
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                ws()
                out += parseEntry()
                ws()
                when (next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> fail()
                }
            }
        }

        private fun parseEntry(): LibraryEntry {
            var cid = ""
            var vid = ""
            var title = ""
            var cover = ""
            var episodeIndex = 0
            var positionMs = 0L
            var durationMs = 0L
            var updatedAt = 0L
            // 旧数据没有这个键，缺省就是 false——向后兼容靠这一行，不要改成必填
            var completed = false
            ws()
            expect('{')
            ws()
            if (peek() == '}') {
                i++
                return LibraryEntry(
                    cid, vid, title, cover, episodeIndex, positionMs, durationMs, updatedAt, completed,
                )
            }
            while (true) {
                ws()
                val key = parseString()
                ws()
                expect(':')
                ws()
                when (key) {
                    "cid" -> cid = asString()
                    "vid" -> vid = asString()
                    "title" -> title = asString()
                    "cover" -> cover = asString()
                    "episodeIndex" -> episodeIndex = asLong().toInt()
                    "positionMs" -> positionMs = asLong()
                    "durationMs" -> durationMs = asLong()
                    "updatedAt" -> updatedAt = asLong()
                    "completed" -> completed = asBool()
                    else -> skipValue()
                }
                ws()
                when (next()) {
                    ',' -> Unit
                    '}' -> return LibraryEntry(
                        cid, vid, title, cover, episodeIndex, positionMs, durationMs, updatedAt, completed,
                    )
                    else -> fail()
                }
            }
        }

        private fun asString(): String {
            val c = peek()
            if (c == '"') return parseString()
            if (c == 'n') { skipLiteral(); return "" }
            return readNumberLike()
        }

        private fun asLong(): Long {
            val c = peek()
            if (c == '"') return parseString().toLongOrNull() ?: 0L
            return readNumberLike().toLongOrNull() ?: 0L
        }

        /** 布尔容忍裸 true/false 与字符串 "true"：旧数据里两种写法都可能出现 */
        private fun asBool(): Boolean {
            val raw = if (peek() == '"') parseString() else readNumberLike()
            return raw.equals("true", ignoreCase = true) || raw == "1"
        }

        private fun readNumberLike(): String {
            val start = i
            while (i < s.length && s[i] !in ",}] \n\r\t".toCharArray()) i++
            return s.substring(start, i)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun skipValue() {
            when (peek()) {
                '"' -> parseString()
                '{' -> skipBracket('{', '}')
                '[' -> skipBracket('[', ']')
                else -> skipLiteral()
            }
        }

        private fun skipBracket(open: Char, close: Char) {
            expect(open)
            var depth = 1
            while (i < s.length && depth > 0) {
                val c = s[i++]
                when {
                    c == '"' -> { i--; parseString() }
                    c == open -> depth++
                    c == close -> depth--
                }
            }
            if (depth != 0) fail()
        }

        private fun skipLiteral() {
            while (i < s.length && s[i] !in ",}] \n\r\t".toCharArray()) i++
        }

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail()

        private fun next(): Char = if (i < s.length) s[i++] else fail()

        private fun expect(c: Char) {
            if (next() != c) fail()
        }

        private fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")
    }
}

/**
 * 纯逻辑层：不碰 DataStore，便于在 JVM 侧直接验证去重 / 裁剪 / 排序三项规则。
 */
internal object LibraryOps {

    fun sortDesc(entries: List<LibraryEntry>): List<LibraryEntry> =
        entries.sortedWith(compareByDescending<LibraryEntry> { it.updatedAt }.thenBy { it.cid })

    /** 按 cid 去重覆盖，刷新 updatedAt，超出 [max] 时丢弃最旧的 */
    fun upsert(existing: List<LibraryEntry>, entry: LibraryEntry, max: Int, now: Long): List<LibraryEntry> {
        val stamped = entry.copy(updatedAt = maxOf(entry.updatedAt, now))
        val merged = ArrayList<LibraryEntry>(existing.size + 1)
        var replaced = false
        for (e in existing) {
            if (e.cid == stamped.cid) {
                if (!replaced) {
                    merged += stamped
                    replaced = true
                }
            } else {
                merged += e
            }
        }
        if (!replaced) merged += stamped
        return sortDesc(merged).take(max)
    }

    fun remove(existing: List<LibraryEntry>, cid: String): List<LibraryEntry> =
        existing.filterNot { it.cid == cid }

    /**
     * 已看完列表的写入：去重口径是 **cid + episodeIndex**，不是 cid。
     *
     * 一部剧可能看完好几集（用户跳着看），按 cid 去重会把前面几集全吃掉，
     * 详情页的「看完」角标就只剩最后一集。其余规则与 [upsert] 完全一致：
     * 刷 updatedAt、按 updatedAt 倒序、超 [max] 裁剪最旧的。
     *
     * [LibraryEntry.completed] 由调用方决定，这里不强行改写成 true——
     * 这条数据将来若也要承载「看过但没看完」，硬编码会把语义写死在这一层。
     */
    fun upsertEpisode(
        existing: List<LibraryEntry>,
        entry: LibraryEntry,
        max: Int,
        now: Long,
    ): List<LibraryEntry> {
        val stamped = entry.copy(updatedAt = maxOf(entry.updatedAt, now))
        val merged = ArrayList<LibraryEntry>(existing.size + 1)
        var replaced = false
        for (e in existing) {
            if (e.cid == stamped.cid && e.episodeIndex == stamped.episodeIndex) {
                if (!replaced) {
                    merged += stamped
                    replaced = true
                }
            } else {
                merged += e
            }
        }
        if (!replaced) merged += stamped
        return sortDesc(merged).take(max)
    }

    /** 删一集：只删 (cid, episodeIndex) 这一条，同剧其它已看完的集不动 */
    fun removeEpisode(existing: List<LibraryEntry>, cid: String, episodeIndex: Int): List<LibraryEntry> =
        existing.filterNot { it.cid == cid && it.episodeIndex == episodeIndex }
}
