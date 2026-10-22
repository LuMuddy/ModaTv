package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** 搜索历史：DataStore Preferences，最多 [MAX] 条，最近搜的排最前 */
interface SearchHistoryStore {
    val entries: Flow<List<String>>
    suspend fun add(word: String)
    suspend fun remove(word: String)
    suspend fun clear()

    companion object { const val MAX = 20 }
}

// 与 moda_history / moda_favorites 一样按 applicationContext 缓存 DataStore，
// 重复 create 不会触发 multiple DataStores active 崩溃
private val Context.searchHistoryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_search_history")

/**
 * 整个列表存在一个键里：顺序就是语义（最近在前），stringSet 是无序的，不能用。
 * 键缺失（首次启动 / 清空后）DataStore 返回 null，解码器把它退化成空列表。
 */
private val WORDS_KEY = stringPreferencesKey("words")

object SearchHistoryStoreImpl {
    @Volatile
    private var instance: SearchHistoryStore? = null

    fun create(context: Context): SearchHistoryStore =
        instance ?: synchronized(this) {
            instance ?: SearchPreferencesStore(context.applicationContext.searchHistoryDataStore)
                .also { instance = it }
        }
}

private class SearchPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : SearchHistoryStore {

    // 去重要 read-modify-write：DataStore 只保证单次 edit 原子，跨步骤仍需互斥
    private val mutex = Mutex()

    override val entries: Flow<List<String>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> SearchWordsJson.decode(prefs[WORDS_KEY]) }
        .distinctUntilChanged()

    override suspend fun add(word: String) {
        val w = word.trim()
        // 空串与纯空格不该占位置，这是 store 自己的不变量，调用方漏判也不会脏数据
        if (w.isEmpty()) return
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs[WORDS_KEY] = SearchWordsJson.encode(
                    SearchWordsOps.add(SearchWordsJson.decode(prefs[WORDS_KEY]), w, SearchHistoryStore.MAX),
                )
            }
        }
    }

    override suspend fun remove(word: String) {
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs[WORDS_KEY] = SearchWordsJson.encode(
                    SearchWordsOps.remove(SearchWordsJson.decode(prefs[WORDS_KEY]), word),
                )
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            dataStore.edit { prefs -> prefs.remove(WORDS_KEY) }
        }
    }
}

/**
 * 纯逻辑层：不碰 DataStore，去重 / 提顶 / 裁剪三条规则都在这里，
 * 语义与 LibraryOps 保持一致的前提下换成了字符串列表。
 */
internal object SearchWordsOps {

    /** 同词只留一条并提到最前，超出 [max] 时丢掉最旧的 */
    fun add(existing: List<String>, word: String, max: Int): List<String> {
        val w = word.trim()
        if (w.isEmpty()) return existing
        val out = ArrayList<String>(minOf(existing.size + 1, max))
        out += w
        for (e in existing) {
            if (e == w) continue
            out += e
            if (out.size >= max) break
        }
        return out
    }

    fun remove(existing: List<String>, word: String): List<String> =
        existing.filterNot { it == word }
}

/**
 * 手写极简编解码：条目就是字符串数组，androidx 的 JSONArray 已经能处理转义，
 * 不走 kotlinx.serialization 是为了不新增依赖（与 StoreJson.kt 同款取舍）。
 */
internal object SearchWordsJson {

    fun encode(words: List<String>): String {
        val arr = JSONArray()
        for (w in words) arr.put(w)
        return arr.toString()
    }

    /** 解析失败一律退化成空列表：本地缓存损坏不该让搜索页崩掉 */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val w = arr.optString(i, "")
                if (w.isNotBlank()) out += w
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
