package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 版权声明的「已确认到哪个版本」。
 *
 * 只存一个 versionCode：等于当前 [com.moda.tv.BuildConfig.VERSION_CODE] 就说明
 * 这个版本的声明已经弹过、用户点过确认；不想用布尔值，是因为布尔值在升级后不会自己变，
 * 每次升级都得额外写一遍「清标记」的逻辑，而版本号本身天然带着这个信息。
 */
interface CopyrightNoticeStore {
    /** 已确认过的 versionCode；从未确认过（首次安装）返回 0 */
    suspend fun acknowledgedVersion(): Int

    suspend fun acknowledge(versionCode: Int)
}

// 与 moda_api_settings 等同款：按 applicationContext 缓存实例，
// 重复 create 不会触发 multiple DataStores active 崩溃
private val Context.copyrightNoticeDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_copyright_notice")

private val ACK_VERSION_KEY = intPreferencesKey("ack_version")

object CopyrightNoticeStoreImpl {
    @Volatile
    private var instance: CopyrightNoticeStore? = null

    fun create(context: Context): CopyrightNoticeStore =
        instance ?: synchronized(this) {
            instance ?: CopyrightNoticePreferencesStore(context.applicationContext.copyrightNoticeDataStore)
                .also { instance = it }
        }
}

private class CopyrightNoticePreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : CopyrightNoticeStore {

    override suspend fun acknowledgedVersion(): Int =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[ACK_VERSION_KEY] ?: 0

    override suspend fun acknowledge(versionCode: Int) {
        dataStore.edit { prefs -> prefs[ACK_VERSION_KEY] = versionCode }
    }
}
