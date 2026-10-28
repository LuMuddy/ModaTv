package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 用户「忽略这个版本」的选择。
 *
 * 为什么存版本号而不是布尔值：布尔值在装上新版本后不会自己变，每次升级都得再补一段
 * 「清标记」的逻辑；而版本号本身带着「忽略到哪一版」的信息——下次检查拿到新版
 * 只需和它比一比，被忽略的那个版本过去之后判断自然失效，不需要任何后台清理。
 *
 * 只在 [com.moda.tv.data.UpdateSource] 与 UI 之间做读写，不含业务判断（该不该弹窗由 ViewModel 决定）。
 */
interface UpdateCheckStore {

    /** 被忽略的版本名（不含前导 v）；从未忽略过返回空串 */
    suspend fun ignoredVersion(): String

    suspend fun ignore(version: String)

    /** 恢复默认 = 删键，不是写空串：写空串会让「从没忽略过」多出一种无法区分的表示 */
    suspend fun clearIgnored()
}

// 一个存储域一个 DataStore 文件。preferencesDataStore 的委托按「文件」持有进程内单例，
// 两个域共用一个文件就等于共用同一个实例：任一域的写入损坏（IOException）会让另一个域的读
// 一起抛错；而「恢复默认」是按域删键的，混在一个文件里既说不清哪些键归谁，也删不干净。
private val Context.updateCheckDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_update_check")

private val IGNORED_VERSION_KEY = stringPreferencesKey("ignored_version")

object UpdateCheckStoreImpl {
    @Volatile
    private var instance: UpdateCheckStore? = null

    fun create(context: Context): UpdateCheckStore =
        instance ?: synchronized(this) {
            instance ?: UpdateCheckPreferencesStore(context.applicationContext.updateCheckDataStore)
                .also { instance = it }
        }
}

private class UpdateCheckPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : UpdateCheckStore {

    // 键缺失 = 用户从未忽略过，静默退回空串，不抛异常（存储约定：缺键即默认值，不迁移、不报错）
    override suspend fun ignoredVersion(): String =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[IGNORED_VERSION_KEY] ?: ""

    override suspend fun ignore(version: String) {
        dataStore.edit { prefs -> prefs[IGNORED_VERSION_KEY] = version }
    }

    override suspend fun clearIgnored() {
        dataStore.edit { prefs -> prefs.remove(IGNORED_VERSION_KEY) }
    }
}
