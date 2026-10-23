package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moda.tv.model.LibraryEntry
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 已看完：DataStore Preferences，最多保留 [MAX] 条，按 updatedAt 倒序。
 *
 * 为什么不复用 moda_history：需求是「看完的归已完成、不归历史记录」，两者是互斥的两张表。
 * 共用一份文件就得在每条读取路径上过滤 completed 字段，任何一处漏过滤，
 * 已看完的剧就会同时出现在两个页面里——在数据层拆开比在 UI 层过滤可靠。
 *
 * 去重口径是 (cid + episodeIndex)：一部剧可以看完好几集（用户跳着看）。
 */
interface CompletedStore {
    val entries: Flow<List<LibraryEntry>>
    suspend fun upsert(entry: LibraryEntry)
    suspend fun remove(cid: String, episodeIndex: Int)
    /** 整部剧的全部已看完集一起删（删历史 / 取消追剧时联动） */
    suspend fun removeSeries(cid: String)
    suspend fun clear()
    /** 详情页给选集网格打「看完」角标 */
    suspend fun completedIndexes(cid: String): Set<Int>

    companion object { const val MAX = 500 }
}

// 与历史/收藏/片头片尾并列的又一个 DataStore，委托保证同一 context 复用一个实例
private val Context.completedDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_completed")

private val ENTRIES_KEY = stringPreferencesKey("entries")

object CompletedStoreImpl {
    @Volatile
    private var instance: CompletedStore? = null

    fun create(context: Context): CompletedStore =
        instance ?: synchronized(this) {
            instance ?: CompletedPreferencesStore(context.applicationContext.completedDataStore)
                .also { instance = it }
        }
}

private class CompletedPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : CompletedStore {

    // 与 HistoryStore 同理：DataStore 的 edit 本身串行，但 read-modify-write 跨多次访问仍需互斥
    private val mutex = Mutex()

    override val entries: Flow<List<LibraryEntry>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        // 按集去重（decodeEpisodes），不能走 HistoryStore 那个按 cid 去重的 decode
        .map { prefs -> LibraryOps.sortDesc(LibraryJson.decodeEpisodes(prefs[ENTRIES_KEY])) }
        .distinctUntilChanged()

    override suspend fun upsert(entry: LibraryEntry) {
        mutex.withLock {
            dataStore.edit { prefs ->
                val current = LibraryJson.decodeEpisodes(prefs[ENTRIES_KEY])
                val next = LibraryOps.upsertEpisode(
                    current,
                    // 落到这张表里的条目一律是「看完了」；调用方不必自己记得置位
                    entry.copy(completed = true),
                    CompletedStore.MAX,
                    System.currentTimeMillis(),
                )
                prefs[ENTRIES_KEY] = LibraryJson.encode(next)
            }
        }
    }

    override suspend fun remove(cid: String, episodeIndex: Int) {
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs[ENTRIES_KEY] = LibraryJson.encode(
                    LibraryOps.removeEpisode(
                        LibraryJson.decodeEpisodes(prefs[ENTRIES_KEY]),
                        cid,
                        episodeIndex,
                    ),
                )
            }
        }
    }

    override suspend fun removeSeries(cid: String) {
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs[ENTRIES_KEY] = LibraryJson.encode(
                    LibraryOps.remove(LibraryJson.decodeEpisodes(prefs[ENTRIES_KEY]), cid),
                )
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            dataStore.edit { prefs -> prefs.remove(ENTRIES_KEY) }
        }
    }

    /**
     * 详情页只需要角标，不需要整表；但 DataStore 没有「按条件查」，
     * 只能取一次快照再过滤。这个方法在详情页进页时调一次，不挂在 Flow 上，
     * 所以不会把 500 条的列表一直留在内存里。
     */
    override suspend fun completedIndexes(cid: String): Set<Int> {
        if (cid.isBlank()) return emptySet()
        return entries.first()
            .asSequence()
            .filter { it.cid == cid }
            .map { it.episodeIndex }
            .toSet()
    }
}
