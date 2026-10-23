package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moda.tv.model.VideoItem
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一个 tab 的首页快照；[fetchedAt] 是数据被拉下来的时刻，不是读盘时刻 */
data class HomeSnapshot(val items: List<VideoItem>, val fetchedAt: Long)

/**
 * 首页持久化缓存（需求 9「如果没有请求到，默认就用老的数据」）。
 *
 * 为什么不复用 Repo 里那份内存缓存：内存缓存随进程消失，冷启动断网时首页就是全屏空态。
 * 电视上最常见的场景恰恰是「昨晚还能看，今早打开发现断网」，这份缓存是那时唯一的画面来源。
 *
 * 每个 tab 独立一份，全部塞进同一个 string key：Preferences 是整体一次读出来的，
 * 拆成多个键并不会更快，反而多出一堆要继续演进的键名。
 */
interface HomeCacheStore {
    suspend fun load(tab: String): HomeSnapshot?

    /**
     * 整张表一次读出来。
     *
     * 不循环调 [load]：Preferences 是整体一次读出来的结构，按 tab 逐个读等于把同一份
     * 文件解码 19 遍——冷启动时那 19 次全在同一个 IO 线程上排队，首页第一帧会被这串
     * 重复劳动拖慢。这里读一次、解一次，后续「进首页 30 分钟门禁」判定也只需要这一份结果。
     *
     * 与 [load] 保持同一条语义：items 为空的行当作没有缓存（解码时已跳过）。
     * 读盘或解析失败返回空表，不抛——缓存坏了只该让首页多走一次网络。
     */
    suspend fun loadAll(): Map<String, HomeSnapshot>

    suspend fun save(tab: String, snapshot: HomeSnapshot)

    suspend fun clear()

    /** 整页最近一次刷新完成的时刻；0 表示从未记录（门禁据此要求先刷一轮） */
    suspend fun pageFetchedAt(): Long

    /** 记下整页刷新完成的时刻，单独一个 key：它是「整页」的属性，不属于任何单个 tab 的缓存行 */
    suspend fun savePageFetchedAt(at: Long)
}

private val Context.homeCacheDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_home_cache")

private val FEEDS_KEY = stringPreferencesKey("feeds")

/** 整页刷新完成时刻；与 feeds 分开存，它描述的是「整张表」，不是某一行的属性 */
private val PAGE_FETCHED_AT_KEY = longPreferencesKey("pageFetchedAt")

object HomeCacheStoreImpl {
    @Volatile
    private var instance: HomeCacheStore? = null

    fun create(context: Context): HomeCacheStore =
        instance ?: synchronized(this) {
            instance ?: HomeCachePreferencesStore(context.applicationContext.homeCacheDataStore)
                .also { instance = it }
        }
}

private class HomeCachePreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : HomeCacheStore {

    // 读-改-写的是整张表，必须互斥：否则两个 tab 同时保存时，后写的那个会把先写的整张表覆盖掉
    private val mutex = Mutex()

    /** 读失败（首次启动 / 文件损坏）一律当「没有缓存」，上层据此走网络 */
    override suspend fun load(tab: String): HomeSnapshot? {
        if (tab.isBlank()) return null
        val prefs = try {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
        } catch (e: Exception) {
            return null
        }
        val entry = FeedJson.decode(prefs[FEEDS_KEY])[tab] ?: return null
        // 空缓存等于没有缓存：让上层去网络拉，而不是拿一屏空白当「老数据」顶上
        if (entry.items.isEmpty()) return null
        return HomeSnapshot(entry.items, entry.fetchedAt)
    }

    /** 读-改-写整张表：单个 tab 的写入绝不能踩掉其它 tab 的数据 */
    override suspend fun save(tab: String, snapshot: HomeSnapshot) {
        if (tab.isBlank()) return
        mutex.withLock {
            dataStore.edit { prefs ->
                val table = FeedJson.decode(prefs[FEEDS_KEY]).toMutableMap()
                table[tab] = FeedEntry(snapshot.items, snapshot.fetchedAt)
                prefs[FEEDS_KEY] = FeedJson.encode(table)
            }
        }
    }

    /**
     * 整张表一次读盘：dataStore.data 只在这里订阅一次。
     *
     * 解码失败（表损坏）与读盘失败走同一条路：返回空表。上层据此当「没有缓存」去走网络，
     * 和 [load] 的取舍一致——缓存坏了只该慢一次，不该让首页报错。
     */
    override suspend fun loadAll(): Map<String, HomeSnapshot> {
        val prefs = try {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
        } catch (e: Exception) {
            return emptyMap()
        }
        val table = FeedJson.decode(prefs[FEEDS_KEY])
        if (table.isEmpty()) return emptyMap()
        return table.mapValues { (_, entry) -> HomeSnapshot(entry.items, entry.fetchedAt) }
    }

    override suspend fun pageFetchedAt(): Long {
        val prefs = try {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
        } catch (e: Exception) {
            return 0L
        }
        return prefs[PAGE_FETCHED_AT_KEY] ?: 0L
    }

    /**
     * 不加锁：这条 key 与 feeds 那张表互不相干，读-改-写冲突的对象根本不是一个。
     * 走 mutex 只会让「刷新完成」这个本该立刻落盘的时刻，去排在 19 个 tab 的保存后面。
     */
    override suspend fun savePageFetchedAt(at: Long) {
        dataStore.edit { prefs -> prefs[PAGE_FETCHED_AT_KEY] = at }
    }

    override suspend fun clear() {
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs.remove(FEEDS_KEY)
                // 缓存清空后「上次刷新完成时刻」也该一起消失，否则门禁会拿一条失效时间戳继续放行
                prefs.remove(PAGE_FETCHED_AT_KEY)
            }
        }
    }
}

/** 表里的一行：与 [HomeSnapshot] 同构，只去掉 hasMore / fromCache 这些纯运行期字段 */
private data class FeedEntry(val items: List<VideoItem>, val fetchedAt: Long)

/**
 * 整张表的 JSON 编解码：{ "Tv": { "fetchedAt": 1, "items": [ … ] }, … }
 *
 * 老版本写下的行里带着 page 字段（首页翻页时代的遗留）。decode 会直接忽略它——
 * 未知键不报错，升级上来的用户不会因为少读一个键而丢掉整屏缓存。
 *
 * 手写而不是引 kotlinx.serialization：与 LibraryJson 同一套约束（不新增依赖）。
 * 解析失败一律退化成空表——缓存坏了只该让首页多走一次网络，不该让应用起不来。
 */
private object FeedJson {

    fun encode(table: Map<String, FeedEntry>): String {
        val sb = StringBuilder(256)
        sb.append('{')
        var firstRow = true
        for ((tab, entry) in table) {
            if (!firstRow) sb.append(',')
            firstRow = false
            sb.append('"')
            escapeInto(sb, tab)
            sb.append("\":{")
            sb.append("\"fetchedAt\":").append(entry.fetchedAt).append(',')
            sb.append("\"items\":")
            VideoItemJson.encodeArray(sb, entry.items)
            sb.append('}')
        }
        sb.append('}')
        return sb.toString()
    }

    fun decode(raw: String?): Map<String, FeedEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val root = MiniJson.parseObject(raw)
            val out = LinkedHashMap<String, FeedEntry>(root.size)
            for ((tab, value) in root) {
                val row = value as? Map<*, *> ?: continue
                val itemsRaw = row["items"] as? List<*> ?: emptyList<Any?>()
                val items = itemsRaw.mapNotNull { VideoItemJson.fromObject(it) }
                if (items.isEmpty()) continue
                out[tab] = FeedEntry(
                    items = items,
                    fetchedAt = (row["fetchedAt"] as? Number)?.toLong() ?: 0L,
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun escapeInto(sb: StringBuilder, v: String) {
        for (c in v) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
    }
}

/**
 * VideoItem 的 JSON 编解码。
 *
 * 只序列化 7 个数据字段，**不序列化 wideCover / thumbCover**：那两个是 getter 派生值，
 * 写进磁盘既占体积，又会让「后来改了拼图规则」的旧缓存继续生效。
 */
internal object VideoItemJson {

    fun encodeArray(sb: StringBuilder, items: List<VideoItem>) {
        sb.append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(',')
            sb.append('{')
            quote(sb, "cid").append(':')
            quote(sb, item.cid).append(',')
            quote(sb, "vid").append(':')
            quote(sb, item.vid).append(',')
            quote(sb, "title").append(':')
            quote(sb, item.title).append(',')
            quote(sb, "cover").append(':')
            quote(sb, item.cover).append(',')
            quote(sb, "subtitle").append(':')
            quote(sb, item.subtitle).append(',')
            quote(sb, "desc").append(':')
            quote(sb, item.desc).append(',')
            quote(sb, "badge").append(':')
            quote(sb, item.badge)
            sb.append('}')
        }
        sb.append(']')
    }

    /** 缺字段按空串处理；cid 与 title 都为空说明这条缓存已经没有用，直接丢掉 */
    fun fromObject(raw: Any?): VideoItem? {
        val map = raw as? Map<*, *> ?: return null
        val cid = map["cid"] as? String ?: ""
        val title = map["title"] as? String ?: ""
        if (cid.isBlank() || title.isBlank()) return null
        return VideoItem(
            cid = cid,
            vid = map["vid"] as? String ?: "",
            title = title,
            cover = map["cover"] as? String ?: "",
            subtitle = map["subtitle"] as? String ?: "",
            desc = map["desc"] as? String ?: "",
            badge = map["badge"] as? String ?: "",
        )
    }

    private const val HEX = "0123456789abcdef"

    /** 写一个 JSON 字符串字面量；返回值方便连续 .append 键名 */
    private fun quote(sb: StringBuilder, v: String): StringBuilder {
        sb.append('"')
        for (c in v) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' || c == '\u2028' || c == '\u2029' -> {
                    val code = c.code
                    sb.append("\\u")
                        .append(HEX[(code shr 12) and 0xF])
                        .append(HEX[(code shr 8) and 0xF])
                        .append(HEX[(code shr 4) and 0xF])
                        .append(HEX[code and 0xF])
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb
    }
}

/**
 * 只够用就好的只读 JSON 解析：对象 → Map，数组 → List，标量 → String/Int/Long/Double/Boolean/null。
 *
 * 不复用 LibraryJson 里的 Parser：那个解析器认识的是「条目数组」这一种结构且带一堆
 * 针对播放记录的特化逻辑（归一化、asLong…）。首页缓存是嵌套的「表 → 行 → 条目」，
 * 为一个通用结构去改动已经跑通的播放记录解析，风险远大于在这里多写七十行。
 */
private object MiniJson {

    fun parseObject(raw: String): Map<String, Any?> {
        val p = Parser(raw)
        val v = p.value()
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: emptyMap()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(): Any? {
            ws()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't', 'f' -> bool()
                'n' -> { literal(); null }
                else -> if (c == '-' || c.isDigit()) num() else fail()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                val key = str()
                ws()
                expect(':')
                out[key] = value()
                ws()
                when (next()) {
                    ',' -> Unit
                    '}' -> return out
                    else -> fail()
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                when (next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> fail()
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                when (val c = s[i++]) {
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

        private fun bool(): Boolean {
            val lit = literal()
            return lit == "true"
        }

        /** 数字统一读成 Long（够装毫秒时间戳）或 Double；调用方按 Number 取 */
        private fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' ||
                    s[i] == '.' || s[i] == 'e' || s[i] == 'E')
            ) {
                i++
            }
            val raw = s.substring(start, i)
            if (raw.isEmpty()) fail()
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: 0L
        }

        private fun literal(): String {
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            return s.substring(start, i)
        }

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail()

        private fun next(): Char = if (i < s.length) s[i++] else fail()

        private fun expect(c: Char) {
            if (next() != c) fail()
        }

        private fun fail(): Nothing = throw IllegalArgumentException("bad json at " + i)
    }
}
