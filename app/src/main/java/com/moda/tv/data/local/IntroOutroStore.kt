package com.moda.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 一部剧的片头/片尾标记：毫秒时间点，0 表示还没记录。
 * 按 cid 存，**不分集**——片头片尾是片源自带的，同一部剧每集都一样。
 */
data class IntroOutro(
    val introMs: Long = 0L,
    val outroMs: Long = 0L,
)

/**
 * 片头片尾标记的存储。
 *
 * 不复用 [LibraryEntry] / 播放记录：那份记录会被「最多 60 条」裁剪、也会被「清空历史」一起清掉，
 * 而片头片尾是用户手工量出来的，被顺手清掉就白记了。所以单开一个 DataStore。
 */
interface IntroOutroStore {
    suspend fun get(cid: String): IntroOutro
    /** ms <= 0 表示清除，把这一项删掉而不是写 0 */
    suspend fun setIntro(cid: String, ms: Long)
    suspend fun setOutro(cid: String, ms: Long)
}

// 与收藏/历史/播放设置并列的第四个 DataStore，委托保证同一 context 复用一个实例
private val Context.introOutroDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_intro_outro")

/**
 * 键名带上 cid：Preferences 的键名在运行时拼是允许的，
 * 这样不用再手写一套「cid → 标记」的 JSON 编解码，也不会因为 cid 里有特殊字符而解析失败。
 */
private fun introKey(cid: String) = longPreferencesKey("intro_$cid")
private fun outroKey(cid: String) = longPreferencesKey("outro_$cid")

object IntroOutroStoreImpl {
    @Volatile
    private var instance: IntroOutroStore? = null

    fun create(context: Context): IntroOutroStore =
        instance ?: synchronized(this) {
            instance ?: DataStoreIntroOutroStore(context.applicationContext.introOutroDataStore)
                .also { instance = it }
        }
}

private class DataStoreIntroOutroStore(
    private val dataStore: DataStore<Preferences>,
) : IntroOutroStore {

    /** 读失败（第一次启动、文件损坏）按「没记录」处理，播放页不该因此进错误态 */
    override suspend fun get(cid: String): IntroOutro {
        if (cid.isBlank()) return IntroOutro()
        val prefs = try {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
        } catch (e: Exception) {
            return IntroOutro()
        }
        return IntroOutro(
            introMs = prefs[introKey(cid)] ?: 0L,
            outroMs = prefs[outroKey(cid)] ?: 0L,
        )
    }

    // 清除走同一个写入口：写 0 与「没有记录」在读取端等价，但删键更干净，
    // 免得 DataStore 里长期躺着一堆 0
    override suspend fun setIntro(cid: String, ms: Long) {
        if (cid.isBlank()) return
        dataStore.edit { prefs ->
            if (ms > 0L) prefs[introKey(cid)] = ms else prefs.remove(introKey(cid))
        }
    }

    override suspend fun setOutro(cid: String, ms: Long) {
        if (cid.isBlank()) return
        dataStore.edit { prefs ->
            if (ms > 0L) prefs[outroKey(cid)] = ms else prefs.remove(outroKey(cid))
        }
    }
}
