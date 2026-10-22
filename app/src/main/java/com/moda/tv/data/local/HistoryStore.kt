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

/** 播放记录：DataStore Preferences，最多保留 [MAX] 条，按 updatedAt 倒序 */
interface HistoryStore {
    val entries: Flow<List<LibraryEntry>>
    suspend fun upsert(entry: LibraryEntry)
    suspend fun remove(cid: String)
    suspend fun clear()
    suspend fun get(cid: String): LibraryEntry?

    companion object { const val MAX = 60 }
}

// 委托本身按 applicationContext 缓存 DataStore，重复 create 不会出现 multiple DataStores active
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "moda_history")

private val ENTRIES_KEY = stringPreferencesKey("entries")

object HistoryStoreImpl {
    @Volatile
    private var instance: HistoryStore? = null

    fun create(context: Context): HistoryStore =
        instance ?: synchronized(this) {
            instance ?: HistoryPreferencesStore(context.applicationContext.historyDataStore)
                .also { instance = it }
        }
}

private class HistoryPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : HistoryStore {

    // DataStore 的 edit 本身串行，但 read-modify-write 跨多次访问时仍需互斥
    private val mutex = Mutex()

    override val entries: Flow<List<LibraryEntry>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> LibraryOps.sortDesc(LibraryJson.decode(prefs[ENTRIES_KEY])) }
        .distinctUntilChanged()

    override suspend fun upsert(entry: LibraryEntry) {
        mutex.withLock {
            dataStore.edit { prefs ->
                val current = LibraryJson.decode(prefs[ENTRIES_KEY])
                val next = LibraryOps.upsert(current, entry, HistoryStore.MAX, System.currentTimeMillis())
                prefs[ENTRIES_KEY] = LibraryJson.encode(next)
            }
        }
    }

    override suspend fun remove(cid: String) {
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs[ENTRIES_KEY] = LibraryJson.encode(
                    LibraryOps.remove(LibraryJson.decode(prefs[ENTRIES_KEY]), cid),
                )
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            dataStore.edit { prefs -> prefs.remove(ENTRIES_KEY) }
        }
    }

    override suspend fun get(cid: String): LibraryEntry? =
        entries.first().firstOrNull { it.cid == cid }
}
