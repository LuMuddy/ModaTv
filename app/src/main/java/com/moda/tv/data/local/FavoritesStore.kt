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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 收藏/追剧：DataStore Preferences，按 updatedAt 倒序 */
interface FavoritesStore {
    val entries: Flow<List<LibraryEntry>>
    suspend fun add(entry: LibraryEntry)
    suspend fun remove(cid: String)
    suspend fun toggle(entry: LibraryEntry): Boolean
    fun isFavorite(cid: String): Flow<Boolean>
}

// 与 HistoryStore 分文件存放；委托保证同一 context 复用同一 DataStore 实例
private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "moda_favorites")

private val ENTRIES_KEY = stringPreferencesKey("entries")

object FavoritesStoreImpl {
    @Volatile
    private var instance: FavoritesStore? = null

    fun create(context: Context): FavoritesStore =
        instance ?: synchronized(this) {
            instance ?: FavoritesPreferencesStore(context.applicationContext.favoritesDataStore)
                .also { instance = it }
        }
}

private class FavoritesPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : FavoritesStore {

    private val mutex = Mutex()

    override val entries: Flow<List<LibraryEntry>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> LibraryOps.sortDesc(LibraryJson.decode(prefs[ENTRIES_KEY])) }
        .distinctUntilChanged()

    override suspend fun add(entry: LibraryEntry) {
        mutex.withLock {
            dataStore.edit { prefs ->
                val current = LibraryJson.decode(prefs[ENTRIES_KEY])
                // Int.MAX_VALUE 等价于“不裁剪”，收藏列表不设上限
                prefs[ENTRIES_KEY] = LibraryJson.encode(
                    LibraryOps.upsert(current, entry, Int.MAX_VALUE, System.currentTimeMillis()),
                )
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

    override suspend fun toggle(entry: LibraryEntry): Boolean {
        var favorited = false
        mutex.withLock {
            dataStore.edit { prefs ->
                val current = LibraryJson.decode(prefs[ENTRIES_KEY])
                favorited = current.none { it.cid == entry.cid }
                val next = if (favorited) {
                    LibraryOps.upsert(current, entry, Int.MAX_VALUE, System.currentTimeMillis())
                } else {
                    LibraryOps.remove(current, entry.cid)
                }
                prefs[ENTRIES_KEY] = LibraryJson.encode(next)
            }
        }
        return favorited
    }

    override fun isFavorite(cid: String): Flow<Boolean> =
        entries.map { list -> list.any { it.cid == cid } }.distinctUntilChanged()
}
